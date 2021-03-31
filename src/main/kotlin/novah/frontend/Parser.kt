/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend

import novah.Util.splitAt
import novah.ast.source.*
import novah.data.*
import novah.frontend.Token.*
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.CORE_MODULE
import novah.frontend.typechecker.coreImport
import novah.frontend.typechecker.primImport
import novah.frontend.error.Errors as E

class Parser(tokens: Iterator<Spanned<Token>>, private val sourceName: String = "Unknown") {
    private val iter = PeekableIterator(tokens, ::throwMismatchedIndentation)

    private var nested = false

    private var moduleName: String? = null

    private data class ModuleDef(val name: String, val span: Span, val comment: Comment?)

    fun parseFullModule(): Result<Module, CompilerProblem> {
        return try {
            Ok(innerParseFullModule())
        } catch (le: LexError) {
            // for now we just return the first error
            Err(CompilerProblem(le.msg, ProblemContext.PARSER, le.span, sourceName, moduleName))
        } catch (pe: ParserError) {
            Err(CompilerProblem(pe.msg, ProblemContext.PARSER, pe.span, sourceName, moduleName))
        }
    }

    private fun innerParseFullModule(): Module {
        val (mname, mspan, comment) = parseModule()
        moduleName = mname

        val imports = mutableListOf<Import>()
        val foreigns = mutableListOf<ForeignImport>()
        while (true) {
            when (iter.peek().value) {
                is ImportT -> imports += parseImport()
                is ForeignT -> foreigns += parseForeignImport()
                else -> break
            }
        }
        addDefaultImports(imports)

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            decls += parseDecl()
        }

        return Module(
            mname,
            sourceName,
            imports,
            foreigns,
            decls,
            mspan
        ).withComment(comment)
    }

    private fun parseModule(): ModuleDef {
        val m = expect<ModuleT>(withError(E.MODULE_DEFINITION))
        return ModuleDef(parseModuleName().joinToString("."), span(m.span, iter.current().span), m.comment)
    }

    private fun parseDeclarationRefs(): List<DeclarationRef> {
        expect<LParen>(withError(E.lparensExpected("import")))
        if (iter.peek().value is RParen) {
            throwError(withError(E.emptyImportExport("import"))(iter.peek()))
        }

        val exps = between<Comma, DeclarationRef> { parseDeclarationRef() }

        expect<RParen>(withError(E.rparensExpected("import")))
        return exps
    }

    private fun parseDeclarationRef(): DeclarationRef {
        val sp = iter.next()
        return when (sp.value) {
            is Ident -> DeclarationRef.RefVar(sp.value.v)
            is UpperIdent -> {
                if (iter.peek().value is LParen) {
                    expect<LParen>(noErr())
                    val ctors = if (iter.peek().value is Op) {
                        val op = expect<Op>(withError(E.DECLARATION_REF_ALL))
                        if (op.value.op != "..") throwError(withError(E.DECLARATION_REF_ALL)(op))
                        listOf()
                    } else {
                        between<Comma, UpperIdent> { parseUpperIdent() }.map { it.v }
                    }
                    expect<RParen>(withError(E.DECLARATION_REF_ALL))
                    DeclarationRef.RefType(sp.value.v, ctors)
                } else DeclarationRef.RefType(sp.value.v)
            }
            is Op -> DeclarationRef.RefVar(sp.value.op)
            else -> throwError(withError(E.EXPORT_REFER)(sp))
        }
    }

    private fun parseModuleName(): List<String> {
        return between<Dot, String> { expect<Ident>(withError(E.MODULE_NAME)).value.v }
    }

    /**
     * Adds the primitive and core imports if needed.
     */
    private fun addDefaultImports(imports: MutableList<Import>) {
        imports += primImport
        if (!imports.any { it.module == CORE_MODULE } && moduleName != CORE_MODULE) {
            imports += coreImport
        }
    }

    private fun parseImport(): Import {
        val impTk = expect<ImportT>(noErr())
        val mname = parseModuleName().joinToString(".")

        val import = when (iter.peek().value) {
            is LParen -> {
                val imp = parseDeclarationRefs()
                if (iter.peek().value is As) {
                    iter.next()
                    val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                    Import.Exposing(mname, imp, span(impTk.span, alias.span), alias.value.v)
                } else Import.Exposing(mname, imp, span(impTk.span, iter.current().span))
            }
            is As -> {
                iter.next()
                val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                Import.Raw(mname, span(impTk.span, alias.span), alias.value.v)
            }
            else -> Import.Raw(mname, span(impTk.span, iter.current().span))
        }

        return import.withComment(impTk.comment)
    }

    private fun parseForeignImport(): ForeignImport {
        val tkSpan = expect<ForeignT>(noErr()).span
        expect<ImportT>(withError(E.FOREIGN_IMPORT))

        fun mkspan() = span(tkSpan, iter.current().span)
        fun parseAlias(lower: Boolean = true): String? {
            if (iter.peek().value !is As) return null
            iter.next()
            return if (lower) expect<Ident>(withError(E.FOREIGN_ALIAS)).value.v
            else expect<UpperIdent>(withError(E.FOREIGN_TYPE_ALIAS)).value.v
        }

        fun parseStatic(): Boolean {
            val tk = iter.peek().value
            return if (tk is Colon) {
                iter.next()
                true
            } else false
        }

        fun forceAlias(ctx: String, lower: Boolean = true) =
            parseAlias(lower) ?: throwError(E.invalidForeign(ctx) to mkspan())

        fun parseFullName() = between<Dot, String>(::parseUpperOrLowerIdent).joinToString(".")
        fun parsePars(ctx: String): List<String> {
            expect<LParen>(withError(E.invalidForeign(ctx)))
            if (iter.peek().value is RParen) {
                iter.next()
                return listOf()
            }
            val pars = between<Comma, String>(::parseFullName)
            expect<RParen>(withError(E.rparensExpected("$ctx import")))
            return pars
        }

        val tk = iter.peek().value
        return when {
            tk is TypeT -> {
                iter.next()
                val fullName = parseFullName()
                if (fullName.split(".").last()[0].isLowerCase()) {
                    throwError(E.FOREIGN_TYPE_ALIAS to span(tkSpan, iter.current().span))
                }
                ForeignImport.Type(fullName, parseAlias(false), mkspan())
            }
            tk is Ident && tk.v == "new" -> {
                iter.next()
                val name = parseFullName()
                val pars = parsePars("constructor")
                ForeignImport.Ctor(name, pars, forceAlias("constructor"), mkspan())
            }
            tk is Ident && (tk.v == "get" || tk.v == "set") -> {
                iter.next()
                val maybename = parseFullName()
                val static = parseStatic()
                val idx = maybename.lastIndexOf('.')
                val (type, name) = if (!static) maybename.splitAt(idx) else maybename to parseUpperOrLowerIdent()
                if (tk.v == "get") {
                    val alias = parseAlias()
                    if (alias == null && name[0].isUpperCase()) {
                        throwError(E.FOREIGN_ALIAS to span(tkSpan, iter.current().span))
                    }
                    ForeignImport.Getter(type, name, static, alias, mkspan())
                } else ForeignImport.Setter(type, name, static, forceAlias("setter"), mkspan())
            }
            else -> {
                val maybename = parseFullName()
                val static = parseStatic()
                val idx = maybename.lastIndexOf('.')
                val (type, name) = if (!static) maybename.splitAt(idx) else maybename to parseUpperOrLowerIdent()
                val pars = parsePars("method")
                val alias = parseAlias()
                if (alias == null && name[0].isUpperCase()) {
                    throwError(E.FOREIGN_ALIAS to span(tkSpan, iter.current().span))
                }
                ForeignImport.Method(type, name, pars, static, alias, mkspan())
            }
        }
    }

    private fun parseDecl(): Decl {
        var tk = iter.peek()
        val comment = tk.comment
        var visibility: Token? = null
        var isInstance = false
        if (tk.value is PublicT || tk.value is PublicPlus) {
            iter.next()
            visibility = tk.value
            tk = iter.peek()
        }
        if (tk.value is Instance) {
            iter.next()
            isInstance = true
            tk = iter.peek()
        }
        val decl = when (tk.value) {
            is TypeT -> {
                if (isInstance) throwError(E.INSTANCE_ERROR to tk.span)
                parseTypeDecl(visibility)
            }
            is Opaque -> {
                if (isInstance) throwError(E.INSTANCE_ERROR to tk.span)
                parseOpaqueDecl(visibility)
            }
            is Ident -> parseVarDecl(visibility, isInstance)
            is LParen -> parseVarDecl(visibility, isInstance, true)
            is TypealiasT -> {
                if (isInstance) throwError(E.INSTANCE_ERROR to tk.span)
                parseTypealias(visibility)
            }
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseTypeDecl(visibility: Token?): Decl {
        val vis = if (visibility != null) Visibility.PUBLIC else Visibility.PRIVATE
        val typ = expect<TypeT>(noErr())
        return withOffside(typ.offside() + 1, false) {

            val name = expect<UpperIdent>(withError(E.DATA_NAME)).value.v

            val tyVars = parseListOf(::parseTypeVar) { it is Ident }

            expect<Equals>(withError(E.DATA_EQUALS))

            val ctors = mutableListOf<DataConstructor>()
            while (true) {
                ctors += parseDataConstructor(tyVars, visibility)
                if (iter.peekIsOffside() || iter.peek().value is EOF) break
                expect<Pipe>(withError(E.pipeExpected("constructor")))
            }
            Decl.TypeDecl(name, tyVars, ctors, vis, false)
                .withSpan(typ.span, iter.current().span)
        }
    }

    private fun parseOpaqueDecl(visibility: Token?): Decl {
        val vis = if (visibility != null) Visibility.PUBLIC else Visibility.PRIVATE
        val tk = expect<Opaque>(noErr())
        expect<TypeT>(withError(E.INVALID_OPAQUE))
        return withOffside(tk.offside() + 1, false) {
            val name = expect<UpperIdent>(withError(E.DATA_NAME)).value.v

            val tyVars = parseListOf(::parseTypeVar) { it is Ident }
            expect<Equals>(withError(E.DATA_EQUALS))

            val ctorvis = if (visibility != null && visibility is PublicPlus) Visibility.PUBLIC else Visibility.PRIVATE
            val type = parseType(true)
            val ctors = listOf(DataConstructor(name, listOf(type), ctorvis, type.span))
            Decl.TypeDecl(name, tyVars, ctors, vis, true)
                .withSpan(tk.span, iter.current().span)
        }
    }

    private fun parseVarDecl(visibility: Token?, isInstance: Boolean, isOperator: Boolean = false): Decl {
        fun parseName(name: String): Pair<Spanned<Token>, String> {
            return if (isOperator) {
                val tk = expect<LParen>(withError(E.INVALID_OPERATOR_DECL))
                val op = expect<Op>(withError(E.INVALID_OPERATOR_DECL))
                expect<RParen>(withError(E.INVALID_OPERATOR_DECL))
                tk to op.value.op
            } else {
                val id = expect<Ident>(withError(E.expectedDefinition(name)))
                id to id.value.v
            }
        }

        var vis = Visibility.PRIVATE
        if (visibility != null) {
            if (visibility is PublicPlus) throwError(E.PUB_PLUS to iter.current().span)
            vis = Visibility.PUBLIC
        }
        val (nameTk, name) = parseName("")
        return withOffside(nameTk.offside() + 1, false) {
            var type: Type? = null
            if (iter.peek().value is Colon) {
                type = parseTypeSignature()
                withOffside(nameTk.offside(), false) {
                    val (nameTk2, name2) = parseName(name)
                    if (name != name2) throwError(withError(E.expectedDefinition(name))(nameTk2))
                }
            }
            val vars = tryParseListOf { tryParsePattern(true) }

            expect<Equals>(withError(E.equalsExpected("function parameters/patterns")))

            val exp = parseExpression()
            Decl.ValDecl(name, vars, exp, type, vis, isInstance, isOperator).withSpan(nameTk.span, exp.span)
        }
    }

    private fun parseTypealias(visibility: Token?): Decl {
        var vis = Visibility.PRIVATE
        if (visibility != null) {
            if (visibility is PublicPlus) throwError(E.PUB_PLUS to iter.current().span)
            vis = Visibility.PUBLIC
        }
        val ta = expect<TypealiasT>(noErr())
        val name = expect<UpperIdent>(withError(E.TYPEALIAS_NAME))
        return withOffside(ta.offside() + 1, false) {
            val tyVars = tryParseListOf { tryParseTypeVar() }
            val end = iter.current().span
            expect<Equals>(withError(E.TYPEALIAS_EQUALS))
            val type = parseType()
            Decl.TypealiasDecl(name.value.v, tyVars, type, vis).withSpan(name.span, end)
        }
    }

    private fun parseTypeSignature(): Type {
        expect<Colon>(withError(E.TYPE_COLON))
        return parseType()
    }

    private fun parseExpression(inDo: Boolean = false): Expr {
        val tk = iter.peek()
        val exps = tryParseListOf(true) { tryParseAtom(inDo) }
        // sanity check
        if (exps.size > 1) {
            val doLets = exps.filterIsInstance<Expr.DoLet>()
            if (doLets.isNotEmpty()) throwError(E.APPLIED_DO_LET to doLets[0].span)
        }

        val unrolled = Application.parseApplication(exps) ?: throwError(withError(E.MALFORMED_EXPR)(tk))

        // type signatures have the lowest precedence
        return if (iter.peek().value is Colon) {
            val dc = iter.next()
            val pt = parseType()
            Expr.Ann(unrolled, pt)
                .withSpan(dc.span, iter.current().span)
                .withComment(dc.comment)
        } else unrolled
    }

    private fun tryParseAtom(inDo: Boolean = false): Expr? {
        val exp = when (iter.peek().value) {
            is IntT -> parseInt32()
            is LongT -> parseInt64()
            is FloatT -> parseFloat32()
            is DoubleT -> parseFloat64()
            is StringT -> parseString()
            is CharT -> parseChar()
            is BoolT -> parseBool()
            is Ident -> parseVar()
            is Op -> parseOperator()
            is Underline -> {
                val tk = iter.next()
                Expr.Underscore().withSpan(tk.span).withComment(tk.comment)
            }
            is LParen -> {
                withIgnoreOffside {
                    val tk = iter.next()
                    if (iter.peek().value is RParen) {
                        val end = iter.next()
                        Expr.Unit().withSpan(span(tk.span, end.span)).withComment(tk.comment)
                    } else {
                        val exp = parseExpression()
                        expect<RParen>(withError(E.rparensExpected("expression")))
                        Expr.Parens(exp)
                    }
                }
            }
            is UpperIdent -> {
                val uident = expect<UpperIdent>(noErr())
                if (iter.peek().value is Dot) {
                    parseAliasedVar(uident)
                } else {
                    Expr.Constructor(uident.value.v).withSpanAndComment(uident)
                }
            }
            is Backslash -> parseLambda()
            is IfT -> parseIf()
            is LetT -> parseLet(inDo)
            is CaseT -> parseMatch()
            is Do -> parseDo()
            is LBracket -> parseRecordOrImplicit()
            is LSBracket -> {
                val tk = iter.next()
                if (iter.peek().value is RSBracket) {
                    val end = iter.next()
                    Expr.VectorLiteral(emptyList()).withSpan(tk.span, end.span).withComment(tk.comment)
                } else {
                    val exps = between<Comma, Expr>(::parseExpression)
                    val end = expect<RSBracket>(withError(E.rsbracketExpected("vector literal")))
                    Expr.VectorLiteral(exps).withSpan(tk.span, end.span).withComment(tk.comment)
                }
            }
            is SetBracket -> {
                val tk = iter.next()
                if (iter.peek().value is RBracket) {
                    val end = iter.next()
                    Expr.SetLiteral(emptyList()).withSpan(tk.span, end.span).withComment(tk.comment)
                } else {
                    val exps = between<Comma, Expr>(::parseExpression)
                    val end = expect<RBracket>(withError(E.rsbracketExpected("set literal")))
                    Expr.SetLiteral(exps).withSpan(tk.span, end.span).withComment(tk.comment)
                }
            }
            else -> null
        }

        // record selection has the highest precedence
        return if (exp != null && iter.peek().value is Dot) {
            iter.next()
            val labels = between<Dot, Pair<String, Spanned<Token>>>(::parseLabel)
            Expr.RecordSelect(exp, labels.map { it.first }).withSpan(exp.span, labels.last().second.span)
        } else exp
    }

    private fun parseInt32(): Expr {
        val num = expect<IntT>(withError(E.literalExpected("integer")))
        return Expr.Int32(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseInt64(): Expr {
        val num = expect<LongT>(withError(E.literalExpected("long")))
        return Expr.Int64(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseFloat32(): Expr {
        val num = expect<FloatT>(withError(E.literalExpected("float")))
        return Expr.Float32(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseFloat64(): Expr {
        val num = expect<DoubleT>(withError(E.literalExpected("double")))
        return Expr.Float64(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseString(): Expr {
        val str = expect<StringT>(withError(E.literalExpected("string")))
        return Expr.StringE(str.value.s).withSpanAndComment(str)
    }

    private fun parseChar(): Expr {
        val str = expect<CharT>(withError(E.literalExpected("char")))
        return Expr.CharE(str.value.c).withSpanAndComment(str)
    }

    private fun parseBool(): Expr {
        val bool = expect<BoolT>(withError(E.literalExpected("boolean")))
        return Expr.Bool(bool.value.b).withSpanAndComment(bool)
    }

    private fun parseVar(): Expr {
        val v = expect<Ident>(withError(E.VARIABLE))
        return Expr.Var(v.value.v).withSpanAndComment(v)
    }

    private fun parseOperator(): Expr {
        val op = expect<Op>(withError("Expected Operator."))
        return Expr.Operator(op.value.op).withSpanAndComment(op)
    }

    private fun parseAliasedVar(alias: Spanned<UpperIdent>): Expr {
        expect<Dot>(noErr())
        return if (iter.peek().value is Op) {
            val op = expect<Op>(noErr())
            Expr.Operator(op.value.op, alias.value.v)
                .withSpan(alias.span, op.span)
                .withComment(alias.comment)
        } else {
            if (iter.peek().value is Ident) {
                val ident = expect<Ident>(withError(E.IMPORTED_DOT))
                Expr.Var(ident.value.v, alias.value.v)
                    .withSpan(alias.span, ident.span)
                    .withComment(alias.comment)
            } else {
                val ident = expect<UpperIdent>(withError(E.IMPORTED_DOT))
                Expr.Constructor(ident.value.v, alias.value.v)
                    .withSpan(alias.span, ident.span)
                    .withComment(alias.comment)
            }
        }
    }

    private fun parseLambda(): Expr {
        val begin = iter.peek()
        expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val vars = tryParseListOf { tryParsePattern(true) }
        if (vars.isEmpty()) throwError(withError(E.LAMBDA_VAR)(iter.current()))

        expect<Arrow>(withError(E.LAMBDA_ARROW))

        val exp = parseExpression()
        return Expr.Lambda(vars, exp)
            .withSpan(begin.span, exp.span)
            .withComment(begin.comment)
    }

    private fun parseIf(): Expr {
        val ifTk = expect<IfT>(noErr())

        val (cond, thens) = withIgnoreOffside {
            val cond = parseExpression()

            expect<Then>(withError(E.THEN))

            val thens = parseExpression()

            expect<Else>(withError(E.ELSE))
            cond to thens
        }

        val elses = parseExpression()

        return Expr.If(cond, thens, elses)
            .withSpan(ifTk.span, elses.span)
            .withComment(ifTk.comment)
    }

    private fun parseLet(inDo: Boolean = false): Expr {
        val let = expect<LetT>(noErr())
        val isInstance = if (iter.peek().value is Instance) {
            iter.next()
            true
        } else false

        val tk = iter.peek()
        val align = tk.offside()
        if (align <= iter.offside()) throwMismatchedIndentation(tk)

        val defs = mutableListOf<LetDef>()

        withOffside(align) {
            if (inDo) {
                while (!iter.peekIsOffside() && iter.peek().value !in statementEnding) {
                    defs += if (isInstance || iter.peek().value is Ident) {
                        parseLetDefBind(isInstance)
                    } else parseLetDefPattern()
                }
            } else {
                while (iter.peek().value != In) {
                    defs += if (isInstance || iter.peek().value is Ident) {
                        parseLetDefBind(isInstance)
                    } else parseLetDefPattern()
                }
            }
        }

        if (inDo) {
            if (iter.peek().value is In) throwError(E.LET_DO_IN to iter.peek().span)
            return Expr.DoLet(defs).withSpan(span(let.span, iter.current().span)).withComment(let.comment)
        }
        withIgnoreOffside { expect<In>(withError(E.LET_IN)) }

        val exp = parseExpression()

        val span = span(let.span, exp.span)
        return Expr.Let(defs, exp).withSpan(span).withComment(let.comment)
    }

    private fun parseLetDefBind(isInstance: Boolean): LetDef {
        val ident = expect<Ident>(withError(E.LET_DECL))
        val name = ident.value.v

        var type: Type? = null
        if (iter.peek().value is Colon) {
            type = withOffside {
                iter.next()
                parseType()
            }
            val newIdent = expect<Ident>(withError(E.expectedLetDefinition(name)))
            if (newIdent.value.v != name) throwError(withError(E.expectedLetDefinition(name))(newIdent))
        }
        return withOffside {
            val vars = tryParseListOf { tryParsePattern(true) }
            expect<Equals>(withError(E.LET_EQUALS))
            val exp = parseExpression()
            val span = span(ident.span, exp.span)
            LetDef.DefBind(Binder(ident.value.v, span), vars, exp, isInstance, type)
        }
    }

    private fun parseLetDefPattern(): LetDef {
        val pat = parsePattern(true)
        return withOffside {
            expect<Equals>(withError(E.LET_EQUALS))
            val exp = parseExpression()
            LetDef.DefPattern(pat, exp)
        }
    }

    private fun parseMatch(): Expr {
        val caseTk = expect<CaseT>(noErr())

        val exps = withIgnoreOffside {
            val exps = between<Comma, Expr>(::parseExpression)
            expect<Of>(withError(E.CASE_OF))
            exps
        }
        val arity = exps.size

        val firstTk = iter.peek()
        val align = firstTk.offside()
        if (iter.peekIsOffside() || (nested && align <= iter.offside())) {
            throwMismatchedIndentation(firstTk)
        }
        return withIgnoreOffside(false) {
            withOffside(align) {
                val cases = mutableListOf<Case>()
                val first = parseCase()
                if (first.patterns.size != arity)
                    throwError(E.wrongArityToCase(first.patterns.size, arity) to first.patternSpan())
                cases += first

                var tk = iter.peek()
                while (!iter.peekIsOffside() && tk.value !in statementEnding) {
                    val case = parseCase()
                    if (case.patterns.size != arity)
                        throwError(E.wrongArityToCase(case.patterns.size, arity) to case.patternSpan())
                    cases += case
                    tk = iter.peek()
                }
                Expr.Match(exps, cases)
                    .withSpan(caseTk.span, iter.current().span)
                    .withComment(caseTk.comment)
            }
        }
    }

    private fun parseCase(): Case {
        var guard: Expr? = null
        val pats = withIgnoreOffside {
            val pats = between<Comma, Pattern>(::parsePattern)
            if (iter.peek().value is IfT) {
                iter.next()
                guard = parseExpression()
            }
            expect<Arrow>(withError(E.CASE_ARROW))
            pats
        }
        return withOffside { Case(pats, parseExpression(), guard) }
    }

    private fun parsePattern(isDestructuring: Boolean = false): Pattern {
        return tryParsePattern(isDestructuring) ?: throwError(withError(E.PATTERN)(iter.peek()))
    }

    private fun tryParsePattern(isDestructuring: Boolean = false): Pattern? {
        val tk = iter.peek()
        val pat = when (tk.value) {
            is Underline -> {
                iter.next()
                Pattern.Wildcard(tk.span)
            }
            is Ident -> {
                iter.next()
                Pattern.Var(tk.value.v, tk.span)
            }
            is BoolT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.BoolLiteral(Expr.Bool(tk.value.b)), tk.span)
            }
            is IntT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.Int32Literal(Expr.Int32(tk.value.v, tk.value.text)), tk.span)
            }
            is LongT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.Int64Literal(Expr.Int64(tk.value.v, tk.value.text)), tk.span)
            }
            is FloatT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.Float32Literal(Expr.Float32(tk.value.v, tk.value.text)), tk.span)
            }
            is DoubleT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.Float64Literal(Expr.Float64(tk.value.v, tk.value.text)), tk.span)
            }
            is CharT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.CharLiteral(Expr.CharE(tk.value.c)), tk.span)
            }
            is StringT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.StringLiteral(Expr.StringE(tk.value.s)), tk.span)
            }
            is LParen -> {
                iter.next()
                if (iter.peek().value is RParen) {
                    val end = iter.next()
                    Pattern.Unit(span(tk.span, end.span))
                } else {
                    val pat = parsePattern()
                    val tkEnd = expect<RParen>(withError(E.rparensExpected("pattern declaration")))
                    Pattern.Parens(pat, span(tk.span, tkEnd.span))
                }
            }
            is UpperIdent -> {
                val ctor = parseConstructor()
                if (isDestructuring) Pattern.Ctor(ctor, emptyList(), span(tk.span, ctor.span))
                else {
                    val fields = tryParseListOf { tryParsePattern() }
                    Pattern.Ctor(ctor, fields, span(tk.span, fields.lastOrNull()?.span ?: ctor.span))
                }
            }
            is LBracket -> {
                iter.next()
                if (iter.peek().value is LBracket) {
                    iter.next()
                    val pat = parsePattern()
                    expect<RBracket>(withError(E.INSTANCE_VAR))
                    val end = expect<RBracket>(withError(E.INSTANCE_VAR))
                    Pattern.ImplicitPattern(pat, span(tk.span, end.span))
                } else {
                    val rows = between<Comma, Pair<String, Pattern>>(::parsePatternRow)
                    val end = expect<RBracket>(withError(E.rbracketExpected("record pattern"))).span
                    Pattern.Record(labelMapWith(rows), span(tk.span, end))
                }
            }
            is LSBracket -> {
                iter.next()
                if (iter.peek().value is RSBracket) {
                    val end = iter.next().span
                    Pattern.Vector(emptyList(), span(tk.span, end))
                } else {
                    val elems = between<Comma, Pattern>(::parsePattern)
                    if (elems.size == 1 && iter.peek().value.isDoubleColon()) {
                        iter.next()
                        val tail = parsePattern()
                        val end = expect<RSBracket>(withError(E.rsbracketExpected("vector pattern"))).span
                        Pattern.VectorHT(elems[0], tail, span(tk.span, end))
                    } else {
                        val end = expect<RSBracket>(withError(E.rsbracketExpected("vector pattern"))).span
                        Pattern.Vector(elems, span(tk.span, end))
                    }
                }
            }
            is Op -> {
                if (tk.value.op == ":?") {
                    iter.next()
                    val tk2 = expect<UpperIdent>(withError(E.TYPE_TEST_TYPE))
                    var ty = tk2.value.v
                    var alias: String? = null
                    if (iter.peek().value is Dot) {
                        iter.next()
                        alias = ty
                        ty = expect<UpperIdent>(withError(E.TYPEALIAS_DOT)).value.v
                    }
                    val type = Type.TConst(ty, alias, span(tk2.span, iter.current().span))

                    var name: String? = null
                    var end = type.span
                    if (iter.peek().value is As) {
                        iter.next()
                        val ident = expect<Ident>(withError(E.VARIABLE))
                        name = ident.value.v
                        end = ident.span
                    }
                    Pattern.TypeTest(type, name, span(tk.span, end))
                } else null
            }
            else -> null
        }
        // named patterns
        return if (pat != null && iter.peek().value is As) {
            iter.next()
            val name = expect<Ident>(withError(E.VARIABLE))
            Pattern.Named(pat, name.value.v, span(pat.span, name.span))
        } else pat
    }

    private fun parsePatternRow(): Pair<String, Pattern> {
        val (label, tk) = parseLabel()
        if (iter.peek().value !is Colon && tk.value is Ident) {
            return label to Pattern.Var(label, tk.span)
        }
        expect<Colon>(withError(E.RECORD_COLON))
        val pat = parsePattern()
        return label to pat
    }

    private fun parseLabel(): Pair<String, Spanned<Token>> {
        return if (iter.peek().value is Ident) {
            val tk = expect<Ident>(noErr())
            tk.value.v to tk
        } else {
            val tk = expect<StringT>(withError(E.RECORD_LABEL))
            tk.value.s to tk
        }
    }

    private fun parseRecordOrImplicit(): Expr {
        return withIgnoreOffside {
            val begin = expect<LBracket>(noErr())
            val nex = iter.peek().value

            if (nex is LBracket) {
                iter.next()
                var alias: String? = null
                if (iter.peek().value is UpperIdent) {
                    alias = expect<UpperIdent>(noErr()).value.v
                    expect<Dot>(withError(E.ALIAS_DOT))
                }
                val exp = expect<Ident>(withError(E.INSTANCE_VAR)).value.v
                expect<RBracket>(withError(E.INSTANCE_VAR))
                val end = expect<RBracket>(withError(E.INSTANCE_VAR))
                Expr.ImplicitVar(exp, alias).withSpan(begin.span, end.span).withComment(begin.comment)
            } else if (nex is RBracket) {
                val end = iter.next()
                Expr.RecordEmpty().withSpan(begin.span, end.span).withComment(begin.comment)
            } else if (nex is Op && nex.op == "-") {
                iter.next()
                parseRecordRestriction(begin)
            } else {
                val rows = between<Comma, Pair<String, Expr>>(::parseRecordRow)
                val exp = if (iter.peek().value is Pipe) {
                    iter.next()
                    parseExpression()
                } else Expr.RecordEmpty()
                val end = expect<RBracket>(withError(E.rbracketExpected("record")))

                Expr.RecordExtend(rows, exp).withSpan(begin.span, end.span).withComment(begin.comment)
            }
        }
    }

    private fun parseRecordRow(): Pair<String, Expr> {
        val label = parseLabel()
        expect<Colon>(withError(E.RECORD_COLON))
        val exp = parseExpression()
        return label.first to exp
    }

    private fun parseRecordRestriction(begin: Spanned<Token>): Expr {
        val labels = between<Comma, Pair<String, Spanned<Token>>>(::parseLabel)
        expect<Pipe>(withError(E.pipeExpected("record restriction")))
        val record = parseExpression()
        val end = expect<RBracket>(withError(E.rbracketExpected("record restriction")))
        return Expr.RecordRestrict(record, labels.map { it.first })
            .withSpan(begin.span, end.span).withComment(begin.comment)
    }

    private fun parseConstructor(): Expr.Constructor {
        val tk = expect<UpperIdent>(noErr())
        var alias: Spanned<UpperIdent>? = null
        var ident: Spanned<UpperIdent> = tk
        if (iter.peek().value is Dot) {
            iter.next()
            alias = tk
            ident = expect(withError(E.IMPORTED_DOT))
        }
        return Expr.Constructor(ident.value.v, alias?.value?.v)
            .withSpan(tk.span, ident.span)
            .withComment(tk.comment) as Expr.Constructor
    }

    private fun parseDataConstructor(typeVars: List<String>, visibility: Token?): DataConstructor {
        val ctor = expect<UpperIdent>(withError(E.CTOR_NAME))

        val vis = if (visibility != null && visibility is PublicPlus) Visibility.PUBLIC else Visibility.PRIVATE

        val pars = tryParseListOf { parseTypeAtom(true) }

        val freeVars = pars.flatMap { it.findFreeVars(typeVars) }
        if (freeVars.isNotEmpty()) {
            val tk = ctor.copy(span = span(ctor.span, iter.current().span))
            throwError(withError(E.undefinedVarInCtor(ctor.value.v, freeVars))(tk))
        }
        return DataConstructor(ctor.value.v, pars, vis, span(ctor.span, iter.current().span))
    }

    private fun parseDo(): Expr {
        val doo = expect<Do>(noErr())

        val firstTk = iter.peek()
        val align = firstTk.offside()
        if (iter.peekIsOffside() || (nested && align <= iter.offside())) {
            throwMismatchedIndentation(firstTk)
        }
        return withIgnoreOffside(false) {
            withOffside(align) {
                val exps = mutableListOf<Expr>()
                val first = parseExpression(true)
                exps += first

                var tk = iter.peek()
                while (!iter.peekIsOffside() && tk.value !in statementEnding) {
                    exps += parseExpression(true)
                    tk = iter.peek()
                }
                if (exps.size == 1) {
                    exps[0]
                } else {
                    Expr.Do(exps).withSpan(doo.span, iter.current().span).withComment(doo.comment)
                }
            }
        }
    }

    private fun parseType(inConstructor: Boolean = false): Type {
        return parseTypeAtom(inConstructor) ?: throwError(withError(E.TYPE_DEF)(iter.peek()))
    }

    private fun parseTypeAtom(inConstructor: Boolean = false): Type? {
        if (iter.peekIsOffside()) return null

        fun parseRowExtend(): Type.TRowExtend {
            val tk = iter.current().span
            val labels = between<Comma, Pair<String, Type>>(::parseRecordTypeRow)
            val rowInner = if (iter.peek().value is Pipe) {
                iter.next()
                parseType()
            } else Type.TRowEmpty(span(tk, iter.current().span))
            return Type.TRowExtend(labels, rowInner, span(tk, iter.current().span))
        }

        val tk = iter.peek()
        val ty = when (tk.value) {
            is LParen -> {
                iter.next()
                withIgnoreOffside {
                    val typ = parseType()
                    val end = expect<RParen>(withError(E.rparensExpected("type definition")))
                    Type.TParens(typ, span(tk.span, end.span))
                }
            }
            is Ident -> Type.TConst(parseTypeVar(), span = span(tk.span, iter.current().span))
            is UpperIdent -> {
                var ty = parseUpperIdent().v
                var alias: String? = null
                if (iter.peek().value is Dot) {
                    iter.next()
                    alias = ty
                    ty = expect<UpperIdent>(withError(E.TYPEALIAS_DOT)).value.v
                }
                if (inConstructor) {
                    Type.TConst(ty, alias, span(tk.span, iter.current().span))
                } else {
                    val const = Type.TConst(ty, alias, span(tk.span, iter.current().span))
                    val pars = tryParseListOf(true) { parseTypeAtom(true) }

                    if (pars.isEmpty()) const
                    else Type.TApp(const, pars, span(tk.span, iter.current().span))
                }
            }
            is LSBracket -> {
                iter.next()
                withIgnoreOffside {
                    if (iter.peek().value is RSBracket) {
                        val end = iter.next()
                        Type.TRowEmpty(span(tk.span, end.span))
                    } else {
                        val rextend = parseRowExtend()
                        expect<RSBracket>(withError(E.rsbracketExpected("row type")))
                        rextend
                    }
                }
            }
            is LBracket -> {
                withIgnoreOffside {
                    iter.next()
                    when (iter.peek().value) {
                        is LBracket -> {
                            iter.next()
                            val ty = parseType()
                            expect<RBracket>(withError(E.INSTANCE_TYPE))
                            val end = expect<RBracket>(withError(E.INSTANCE_TYPE))
                            Type.TImplicit(ty, span(tk.span, end.span))
                        }
                        is RBracket -> {
                            val span = span(tk.span, iter.next().span)
                            Type.TRecord(Type.TRowEmpty(span), span)
                        }
                        is Pipe -> {
                            iter.next()
                            val ty = parseType()
                            val end = expect<RBracket>(withError(E.rbracketExpected("record type")))
                            val span = span(tk.span, end.span)
                            Type.TRecord(Type.TRowExtend(emptyList(), ty, span), span)
                        }
                        else -> {
                            val rextend = parseRowExtend()
                            val end = expect<RBracket>(withError(E.rbracketExpected("record type")))
                            Type.TRecord(rextend, span(tk.span, end.span))
                        }
                    }
                }
            }
            else -> null
        } ?: return null

        if (inConstructor) return ty
        return when (iter.peek().value) {
            is Arrow -> {
                iter.next()
                val ret = parseType()
                Type.TFun(ty, ret, span(ty.span, ret.span))
            }
            else -> ty
        }
    }

    private fun parseRecordTypeRow(): Pair<String, Type> {
        val (label, _) = parseLabel()
        expect<Colon>(withError(E.RECORD_COLON))
        val ty = parseType()
        return label to ty
    }

    private fun parseUpperOrLowerIdent(): String {
        val tk = iter.next()
        return when (tk.value) {
            is UpperIdent -> tk.value.v
            is Ident -> tk.value.v
            else -> throwError(withError(E.UPPER_LOWER)(tk))
        }
    }

    private fun tryParseTypeVar(): String? {
        return if (iter.peek().value is Ident) {
            expect<Ident>(noErr()).value.v
        } else null
    }

    private fun parseTypeVar(): String {
        return expect<Ident>(withError(E.TYPE_VAR)).value.v
    }

    private fun parseUpperIdent(): UpperIdent {
        return expect<UpperIdent>(noErr()).value
    }

    private fun <T> parseListOf(parser: () -> T, keep: (Token) -> Boolean): List<T> {
        val res = mutableListOf<T>()
        while (!iter.peekIsOffside() && keep(iter.peek().value)) {
            res += parser()
        }
        return res
    }

    private inline fun <reified T> tryParseListOf(increaseOffside: Boolean = false, fn: () -> T?): List<T> {
        val acc = mutableListOf<T>()
        if (iter.peekIsOffside()) return acc
        var element = fn()
        val tmp = iter.offside()
        if (increaseOffside) iter.withOffside(tmp + 1)
        while (element != null) {
            acc += element
            if (iter.peekIsOffside()) break
            element = fn()
        }
        iter.withOffside(tmp)
        return acc
    }

    private inline fun <reified TK, T> between(parser: () -> T): List<T> {
        val res = mutableListOf<T>()
        res += parser()
        while (iter.peek().value is TK) {
            iter.next()
            res += parser()
        }
        return res
    }

    /**
     * Expected a [Token] of type `T` or throws
     * an exception with the supplied error handler.
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> expect(err: (Spanned<Token>) -> Pair<String, Span>): Spanned<T> {
        val tk = iter.next()

        return when (tk.value) {
            is T -> tk as Spanned<T>
            else -> {
                val (msg, span) = err(tk)
                throw ParserError(msg, span)
            }
        }
    }

    private fun <T> withOffside(off: Int = iter.offside() + 1, nested: Boolean = true, f: () -> T): T {
        val tmp = iter.offside()
        val tmpNest = this.nested
        this.nested = nested
        iter.withOffside(off)
        val res = f()
        iter.withOffside(tmp)
        this.nested = tmpNest
        return res
    }

    private inline fun <T> withIgnoreOffside(shouldIgnore: Boolean = true, f: () -> T): T {
        val tmp = iter.ignoreOffside()
        iter.withIgnoreOffside(shouldIgnore)
        val res = f()
        iter.withIgnoreOffside(tmp)
        return res
    }

    companion object {
        /**
         * Creates an error handler with the supplied message.
         */
        private fun withError(msg: String): (Spanned<Token>) -> Pair<String, Span> = { token ->
            msg to token.span
        }

        private fun throwError(err: Pair<String, Span>): Nothing {
            throw ParserError(err.first, err.second)
        }

        private fun throwMismatchedIndentation(tk: Spanned<Token>): Nothing {
            throwError(withError(E.MISMATCHED_INDENTATION)(tk))
        }

        /**
         * Represents an error that cannot happen
         */
        private fun noErr() = { tk: Spanned<Token> -> "Cannot happen, token: $tk" to tk.span }

        private fun span(s: Span, e: Span) = Span.new(s, e)

        private val statementEnding = listOf(RParen, RSBracket, RBracket, EOF)
    }
}

class ParserError(val msg: String, val span: Span) : java.lang.RuntimeException(msg)
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

import novah.ast.source.*
import novah.ast.source.Type
import novah.data.*
import novah.frontend.Token.*
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import novah.frontend.typechecker.*
import novah.main.Environment
import novah.frontend.error.Errors as E

class Parser(
    tokens: Iterator<Spanned<Token>>,
    private val isStdlib: Boolean,
    private val sourceName: String = "Unknown",
    private val stdlib: Boolean = true
) {
    private val iter = PeekableIterator(tokens, ::throwMismatchedIndentation)

    private var moduleName: String? = null

    private val errors = mutableListOf<CompilerProblem>()

    private data class ModuleDef(val name: Spanned<String>, val span: Span, val comment: Comment?)

    fun parseFullModule(): Result<Module, CompilerProblem> {
        return try {
            Ok(innerParseFullModule())
        } catch (le: LexError) {
            // for now, we just return the first error
            Err(CompilerProblem(le.msg, le.span, sourceName, moduleName, severity = Severity.FATAL))
        } catch (pe: ParserError) {
            Err(CompilerProblem(pe.msg, pe.span, sourceName, moduleName, severity = Severity.FATAL))
        }
    }

    fun errors(): List<CompilerProblem> = errors

    private fun innerParseFullModule(): Module {
        val moduleAttr = parseMetadata()
        val (mname, mspan, comment) = parseModule()
        moduleName = mname.value

        val imports = mutableListOf<Import>()
        var foreigns: List<ForeignImport> = listOf()

        var next = iter.peek().value
        if (next is ImportT) {
            imports += parseImports()
            if (iter.peek().value is ForeignT) foreigns = parseForeignImports()
        } else if (next is ForeignT) {
            foreigns = parseForeignImports()
            if (iter.peek().value is ImportT) imports += parseImports()
        }
        next = iter.peek().value
        if (next is ImportT || next is ForeignT) throwError(E.MIXED_IMPORTS to iter.peek().span)
        addDefaultImports(imports)

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            try {
                decls += parseDecl()
            } catch (le: LexError) {
                errors += CompilerProblem(le.msg, le.span, sourceName, moduleName)
                fastForward()
            } catch (pe: ParserError) {
                errors += CompilerProblem(pe.msg, pe.span, sourceName, moduleName)
                fastForward()
            }
        }

        return Module(
            mname,
            sourceName,
            imports,
            foreigns,
            decls,
            moduleAttr,
            mspan
        ).withComment(comment)
    }

    private fun parseImports(): List<Import> {
        val imports = mutableListOf<Import>()
        while (true) {
            when (iter.peek().value) {
                is ImportT -> imports += parseImport()
                else -> break
            }
        }
        return imports
    }

    private fun parseForeignImports(): List<ForeignImport> {
        val imports = mutableListOf<ForeignImport>()
        while (true) {
            when (iter.peek().value) {
                is ForeignT -> imports += parseForeignImport()
                else -> break
            }
        }
        return imports
    }

    private fun parseModule(): ModuleDef {
        val m = expect<ModuleT>(withError(E.MODULE_DEFINITION))
        return ModuleDef(parseModuleName(), span(m.span, iter.current().span), m.comment)
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
            is Ident -> DeclarationRef.RefVar(sp.value.v, sp.span)
            is UpperIdent -> {
                val binder = Spanned(sp.span, sp.value.v)
                if (iter.peek().value is LParen) {
                    expect<LParen>(noErr())
                    val ctors = if (iter.peek().value is Op) {
                        val op = expect<Op>(withError(E.DECLARATION_REF_ALL))
                        if (op.value.op != "..") throwError(withError(E.DECLARATION_REF_ALL)(op))
                        listOf()
                    } else {
                        between<Comma, Spanned<String>> {
                            val ident = expect<UpperIdent>(withError(E.CTOR_NAME))
                            Spanned(ident.span, ident.value.v)
                        }
                    }
                    val end = expect<RParen>(withError(E.DECLARATION_REF_ALL))
                    DeclarationRef.RefType(binder, span(sp.span, end.span), ctors)
                } else DeclarationRef.RefType(binder, sp.span)
            }
            is Op -> DeclarationRef.RefVar(sp.value.op, sp.span)
            else -> throwError(withError(E.IMPORT_REFER)(sp))
        }
    }

    private fun parseModuleName(): Spanned<String> {
        val idents = between<Dot, Spanned<Ident>> { expect(withError(E.MODULE_NAME)) }
        idents.forEach {
            if (it.value.v.endsWith('?') || it.value.v.endsWith('!'))
                throwError(E.MODULE_NAME to it.span)
        }
        val span = span(idents[0].span, idents.last().span)
        return Spanned(span, idents.joinToString(".") { it.value.v })
    }

    /**
     * Adds the primitive and base imports if needed.
     */
    private fun addDefaultImports(imports: MutableList<Import>) {
        imports += primImport
        if (!stdlib) return
        if (imports.none { it.module.value == CORE_MODULE } && moduleName != CORE_MODULE) {
            imports += coreImport
        }
        if (!isStdlib && moduleName !in Environment.stdlibModuleNames()) {
            // all aliased modules
            val aliased = imports.filter { it.alias() != null }.map { it.module.value }.toSet()

            if (!aliased.contains(ARRAY_MODULE)) imports += arrayImport
            if (!aliased.contains(IO_MODULE)) imports += ioImport
            if (!aliased.contains(JAVA_MODULE)) imports += javaImport
            if (!aliased.contains(LIST_MODULE)) imports += listImport
            if (!aliased.contains(MATH_MODULE)) imports += mathImport
            if (!aliased.contains(OPTION_MODULE)) imports += optionImport
            if (!aliased.contains(SET_MODULE)) imports += setImport
            if (!aliased.contains(STRING_MODULE)) imports += stringImport
            if (!aliased.contains(MAP_MODULE)) imports += mapImport
            if (!aliased.contains(NUMBER_MODULE)) imports += numberImport
            if (!aliased.contains(REGEX_MODULE)) imports += regexImport
            if (!aliased.contains(RESULT_MODULE)) imports += resultImport
        }
    }

    private fun parseImport(): Import {
        val impTk = expect<ImportT>(noErr())
        val mod = parseModuleName()

        return withOffside {
            if (iter.peekIsOffside()) {
                Import.Raw(mod, span(impTk.span, iter.current().span))
            } else {
                val import = when (iter.peek().value) {
                    is LParen -> {
                        val imp = parseDeclarationRefs()
                        if (iter.peek().value is As) {
                            iter.next()
                            val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                            Import.Exposing(mod, imp, span(impTk.span, alias.span), alias.value.v)
                        } else Import.Exposing(mod, imp, span(impTk.span, iter.current().span))
                    }
                    is As -> {
                        iter.next()
                        val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                        Import.Raw(mod, span(impTk.span, alias.span), alias.value.v)
                    }
                    else -> Import.Raw(mod, span(impTk.span, iter.current().span))
                }

                import.withComment(impTk.comment)
            }
        }
    }

    private fun parseForeignImport(): ForeignImport {
        val tkSpan = expect<ForeignT>(noErr()).span
        expect<ImportT>(withError(E.FOREIGN_IMPORT))

        fun parseAlias(): String? {
            if (iter.peek().value !is As) return null
            iter.next()
            return expect<UpperIdent>(withError(E.FOREIGN_TYPE_ALIAS)).value.v
        }

        val fullName = between<Dot, String>(::parseIdentOrString).joinToString(".")
        if (fullName.split(".").last()[0].isLowerCase()) {
            throwError(E.FOREIGN_TYPE_ALIAS to span(tkSpan, iter.current().span))
        }
        return ForeignImport(fullName, parseAlias(), span(tkSpan, iter.current().span))
    }

    fun parseDecl(): Decl {
        val meta = parseMetadata()
        var tk = iter.peek()
        val comment = tk.comment
        var visibility: Token? = null
        var isInstance = false
        var offside = Integer.MAX_VALUE
        if (tk.value is PublicT || tk.value is PublicPlus) {
            iter.next()
            visibility = tk.value
            if (tk.offside() < offside) offside = tk.offside()
            tk = iter.peek()
        }
        if (tk.value is Instance) {
            iter.next()
            isInstance = true
            if (tk.offside() < offside) offside = tk.offside()
            tk = iter.peek()
        }
        if (tk.offside() < offside) offside = tk.offside()
        val decl = when (tk.value) {
            is TypeT -> {
                if (isInstance) throwError(E.INSTANCE_ERROR to tk.span)
                parseTypeDecl(visibility, offside).withMeta(meta)
            }
            is Ident -> parseVarDecl(visibility, isInstance, offside).withMeta(meta)
            is LParen -> parseVarDecl(visibility, isInstance, offside, true).withMeta(meta)
            is TypealiasT -> {
                if (isInstance) throwError(E.INSTANCE_ERROR to tk.span)
                if (meta != null) throwError(E.META_ALIAS to meta.data.span)
                parseTypealias(visibility, offside)
            }
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseTypeDecl(visibility: Token?, offside: Int): Decl {
        val vis = if (visibility != null) Visibility.PUBLIC else Visibility.PRIVATE
        val typ = expect<TypeT>(noErr())
        return withOffside(offside + 1) {

            val nameTk = expect<UpperIdent>(withError(E.DATA_NAME))
            val name = Spanned(nameTk.span, nameTk.value.v)

            val tyVars = parseListOf(::parseTypeVar) { it is Ident }

            expect<Equals>(withError(E.DATA_EQUALS))

            val ctors = mutableListOf<DataConstructor>()
            while (true) {
                ctors += parseDataConstructor(tyVars, visibility)
                if (iter.peekIsOffside() || iter.peek().value is EOF) break
                expect<Pipe>(withError(E.pipeExpected("constructor")))
            }
            Decl.TypeDecl(name, tyVars, ctors, vis)
                .withSpan(typ.span, iter.current().span)
        }
    }

    private fun parseVarDecl(visibility: Token?, isInstance: Boolean, offside: Int, isOperator: Boolean = false): Decl {
        fun parseName(name: String): Spanned<String> {
            return if (isOperator) {
                val tk = expect<LParen>(withError(E.INVALID_OPERATOR_DECL))
                val op = expect<Op>(withError(E.INVALID_OPERATOR_DECL))
                val end = expect<RParen>(withError(E.INVALID_OPERATOR_DECL))
                Spanned(span(tk.span, end.span), op.value.op)
            } else {
                val id = expect<Ident>(withError(E.expectedDefinition(name)))
                Spanned(id.span, id.value.v)
            }
        }

        var vis = Visibility.PRIVATE
        if (visibility != null) {
            if (visibility is PublicPlus) throwError(E.PUB_PLUS to iter.current().span)
            vis = Visibility.PUBLIC
        }
        val nameTk = parseName("")
        val name = nameTk.value

        return withOffside(offside + 1) {
            var sig: Signature? = null
            var nameTk2: Spanned<String>? = null
            if (iter.peek().value is Colon) {
                sig = Signature(parseTypeSignature(), nameTk.span)
                withOffside(nameTk.offside()) {
                    nameTk2 = parseName(name)
                    if (name != nameTk2!!.value)
                        throwError(E.expectedDefinition(name) to nameTk2!!.span)
                }
            }
            val vars = tryParseListOf { tryParsePattern(true) }

            val eq = expect<Equals>(withError(E.equalsExpected("function parameters/patterns")))

            val exp = if (eq.span.sameLine(iter.peek().span)) parseExpression() else parseDo()
            val binder = Spanned(if (nameTk2 != null) nameTk2!!.span else nameTk.span, name)
            if (isOperator && name.length > 3) throwError(E.opToolong(name) to binder.span)
            Decl.ValDecl(binder, vars, exp, sig, vis, isInstance, isOperator)
                .withSpan(nameTk.span, exp.span)
        }
    }

    private fun parseTypealias(visibility: Token?, offside: Int): Decl {
        var vis = Visibility.PRIVATE
        if (visibility != null) {
            if (visibility is PublicPlus) throwError(E.PUB_PLUS to iter.current().span)
            vis = Visibility.PUBLIC
        }
        expect<TypealiasT>(noErr())
        val name = expect<UpperIdent>(withError(E.TYPEALIAS_NAME))
        return withOffside(offside + 1) {
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
        if (iter.peekIsOffside()) throwMismatchedIndentation(iter.peek())
        val tk = iter.peek()
        val exps = mutableListOf<Expr>()
        exps += tryParseAtom() ?: throwError(E.MALFORMED_EXPR to tk.span)
        val offside = iter.offside()
        if (inDo) {
            var last: Expr? = null
            while (true) {
                if (last != null && last is Expr.Operator) {
                    withOffside(offside) {
                        if (iter.peekIsOffside()) throwError(E.EXPECTED_OPERAND to iter.peek().span)
                        last = tryParseAtom()
                        if (last == null) throwError(E.EXPECTED_OPERAND to iter.peek().span)
                    }
                } else {
                    val peek = iter.peek().value
                    // operators follow different indentation rules
                    val nextOp = peek is Op || peek is Semicolon
                    val off = if (nextOp) offside else offside + 1
                    withOffside(off) {
                        last = if (iter.peekIsOffside()) null else tryParseAtom()
                    }
                }
                if (last == null) break
                else exps += last!!
            }
        } else {
            withOffside(offside) { exps += tryParseListOf { tryParseAtom() } }
        }
        // sanity check
        if (exps.size > 1) {
            val doLets = exps.filterIsInstance<Expr.DoLet>()
            if (doLets.isNotEmpty()) throwError(E.APPLIED_DO_LET to doLets[0].span)
        }

        val unrolled = Application.parseApplication(exps) ?: throwError(withError(E.MALFORMED_EXPR)(tk))

        // type signatures and casts have the lowest precedence
        val typedExpr = if (iter.peek().value is Colon) {
            iter.next()
            val pt = parseType()
            Expr.Ann(unrolled, pt)
                .withSpan(unrolled.span, iter.current().span)
                .withComment(unrolled.comment)
        } else unrolled

        return if (iter.peek().value is As) {
            iter.next()
            val ty = parseType()
            Expr.TypeCast(unrolled, ty)
                .withSpan(typedExpr.span, iter.current().span)
                .withComment(typedExpr.comment)
        } else typedExpr
    }

    private fun tryParseAtom(): Expr? {
        val exp = when (iter.peek().value) {
            is IntT -> parseInt32()
            is LongT -> parseInt64()
            is FloatT -> parseFloat32()
            is DoubleT -> parseFloat64()
            is BigintT -> parseBigint()
            is BigdecT -> parseBigdec()
            is StringT -> parseString()
            is MultilineStringT -> parseMultilineString()
            is PatternStringT -> parsePatternString()
            is CharT -> parseChar()
            is BoolT -> parseBool()
            is Ident -> parseVar()
            is Op -> parseOperator()
            is Semicolon -> {
                val tk = iter.next()
                Expr.Operator(";", false).withSpanAndComment(tk)
            }
            is Underline -> {
                val tk = iter.next()
                val under = Expr.Underscore().withSpan(tk.span).withComment(tk.comment)
                // a _!! unwrap anonymous function
                if (iter.peek().value is BangBang) {
                    val bb = iter.next()
                    Expr.UnderscoreBangBang().withSpan(tk.span, bb.span).withComment(tk.comment)
                } else under
            }
            is At -> {
                val tk = iter.next()
                val exp = if (iter.peek().value is Ident) parseVar() else parseExpression()
                Expr.Deref(exp).withSpan(tk.span, exp.span).withComment(tk.comment)
            }
            is LParen -> {
                withIgnoreOffside {
                    val tk = iter.next()
                    if (iter.peek().value is RParen) {
                        val end = iter.next()
                        Expr.Unit().withSpan(span(tk.span, end.span)).withComment(tk.comment)
                    } else {
                        val exp = parseExpression()
                        val end = expect<RParen>(withError(E.rparensExpected("expression")))
                        Expr.Parens(exp).withSpan(tk.span, end.span).withComment(tk.comment)
                    }
                }
            }
            is UpperIdent -> {
                val uident = expect<UpperIdent>(noErr())
                val peek = iter.peek().value
                when {
                    peek is Dot -> {
                        parseAliasedVar(uident)
                    }
                    // an aliased operator like `MyModule.==`
                    peek.isDotStart() -> {
                        val op = expect<Op>(noErr())
                        Expr.Operator(op.value.op.substring(1), false, uident.value.v)
                            .withSpan(uident.span, op.span)
                            .withComment(uident.comment)
                    }
                    peek is Hash -> {
                        iter.next()
                        val (methodName, tk) = parseJavaName()
                        val (args, end) = parseArglist("foreign static method")
                        val method = Spanned(tk.span, methodName)
                        val clazz = Spanned(uident.span, uident.value.v)
                        Expr.ForeignStaticMethod(clazz, method, args, option = false)
                            .withSpan(uident.span, end)
                            .withComment(uident.comment)
                    }
                    peek is HashQuestion -> {
                        iter.next()
                        val (methodName, tk) = parseJavaName()
                        val (args, end) = parseArglist("foreign static method")
                        val method = Spanned(tk.span, methodName)
                        val clazz = Spanned(uident.span, uident.value.v)
                        Expr.ForeignStaticMethod(clazz, method, args, option = true)
                            .withSpan(uident.span, end)
                            .withComment(uident.comment)
                    }
                    peek is HashDash -> {
                        iter.next()
                        val (fieldName, tk) = parseJavaName()
                        val field = Spanned(tk.span, fieldName)
                        val clazz = Spanned(uident.span, uident.value.v)
                        Expr.ForeignStaticField(clazz, field, option = false)
                            .withSpan(uident.span, tk.span)
                            .withComment(uident.comment)
                    }
                    peek is HashDashQuestion -> {
                        iter.next()
                        val (fieldName, tk) = parseJavaName()
                        val field = Spanned(tk.span, fieldName)
                        val clazz = Spanned(uident.span, uident.value.v)
                        Expr.ForeignStaticField(clazz, field, option = true)
                            .withSpan(uident.span, tk.span)
                            .withComment(uident.comment)
                    }
                    else -> {
                        Expr.Constructor(uident.value.v).withSpanAndComment(uident)
                    }
                }
            }
            is Backslash -> parseLambda()
            is IfT -> parseIf()
            is LetT -> parseLet()
            is LetBang -> parseLetBang()
            is DoDot -> parseComputation()
            is DoBang -> {
                val dobang = iter.next()
                val exp = withOffside { parseExpression() }
                Expr.DoBang(exp).withSpan(dobang.span, exp.span).withComment(dobang.comment)
            }
            is CaseT -> parseMatch()
            is LBracket -> parseRecordOrImplicit()
            is LSBracket -> {
                val tk = iter.next()
                if (iter.peek().value is RSBracket) {
                    val end = iter.next()
                    Expr.ListLiteral(emptyList()).withSpan(tk.span, end.span).withComment(tk.comment)
                } else {
                    withIgnoreOffside {
                        val exps = between<Comma, Expr>(::parseExpression)
                        val end = expect<RSBracket>(withError(E.rsbracketExpected("list literal")))
                        Expr.ListLiteral(exps).withSpan(tk.span, end.span).withComment(tk.comment)
                    }
                }
            }
            is SetBracket -> {
                val tk = iter.next()
                if (iter.peek().value is RBracket) {
                    val end = iter.next()
                    Expr.SetLiteral(emptyList()).withSpan(tk.span, end.span).withComment(tk.comment)
                } else {
                     withIgnoreOffside {
                        val exps = between<Comma, Expr>(::parseExpression)
                        val end = expect<RBracket>(withError(E.rsbracketExpected("set literal")))
                        Expr.SetLiteral(exps).withSpan(tk.span, end.span).withComment(tk.comment)
                     }
                }
            }
            is ThrowT -> {
                val tk = iter.next()
                val exp = withOffside { parseExpression() }
                Expr.Throw(exp).withSpan(tk.span, exp.span).withComment(tk.comment)
            }
            is TryT -> parseTryCatch()
            is WhileT -> parseWhile()
            is Return -> {
                val ret = iter.next()
                val exp = withOffside { parseExpression() }
                Expr.Return(exp).withSpan(ret.span, exp.span).withComment(ret.comment)
            }
            is Yield -> {
                val ret = iter.next()
                val exp = withOffside { parseExpression() }
                Expr.Yield(exp).withSpan(ret.span, exp.span).withComment(ret.comment)
            }
            is For -> parseFor()
            else -> null
        } ?: return null

        // record selection, index (.[x]), unwrap (!!) and method/field call have the highest precedence
        return parseSelection(exp)
    }

    private tailrec fun parseSelection(exp: Expr): Expr = when (iter.peek().value) {
        is Dot -> {
            iter.next()
            val labels = between<Dot, Pair<String, Spanned<Token>>>(::parseLabel)
            val res = Expr.RecordSelect(exp, labels.map { Spanned(it.second.span, it.first) })
                .withSpan(exp.span, labels.last().second.span)
            parseSelection(res)
        }
        is DotBracket -> {
            iter.next()
            val index = parseExpression()
            val end = expect<RSBracket>(withError(E.rsbracketExpected("index")))
            val res = Expr.Index(exp, index).withSpan(exp.span, end.span)
            parseSelection(res)
        }
        is Hash -> {
            iter.next()
            val (methodName, tk) = parseJavaName()
            val (args, end) = parseArglist("foreign method")
            val method = Spanned(tk.span, methodName)
            val res = Expr.ForeignMethod(exp, method, args, option = false)
                .withSpan(exp.span, end).withComment(exp.comment)
            parseSelection(res)
        }
        is HashQuestion -> {
            iter.next()
            val (methodName, tk) = parseJavaName()
            val (args, end) = parseArglist("foreign method")
            val method = Spanned(tk.span, methodName)
            val res = Expr.ForeignMethod(exp, method, args, option = true)
                .withSpan(exp.span, end).withComment(exp.comment)
            parseSelection(res)
        }
        is HashDash -> {
            iter.next()
            val (fieldName, tk) = parseJavaName()
            val field = Spanned(tk.span, fieldName)
            val res = Expr.ForeignField(exp, field, option = false).withSpan(exp.span, tk.span).withComment(exp.comment)
            parseSelection(res)
        }
        is HashDashQuestion -> {
            iter.next()
            val (fieldName, tk) = parseJavaName()
            val field = Spanned(tk.span, fieldName)
            val res = Expr.ForeignField(exp, field, option = true).withSpan(exp.span, tk.span).withComment(exp.comment)
            parseSelection(res)
        }
        is BangBang -> {
            val span = iter.next().span
            val res = Expr.BangBang(exp).withSpan(exp.span, span).withComment(exp.comment)
            parseSelection(res)
        }
        else -> exp
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

    private fun parseBigint(): Expr {
        val num = expect<BigintT>(withError(E.literalExpected("big integer")))
        return Expr.Bigint(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseBigdec(): Expr {
        val num = expect<BigdecT>(withError(E.literalExpected("big decimal")))
        return Expr.Bigdec(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseString(): Expr {
        val str = expect<StringT>(withError(E.literalExpected("string")))
        return Expr.StringE(str.value.s, str.value.raw).withSpanAndComment(str)
    }

    private fun parseMultilineString(): Expr {
        val str = expect<MultilineStringT>(withError(E.literalExpected("multiline string")))
        return Expr.StringE(str.value.s, str.value.s, multi = true).withSpanAndComment(str)
    }

    private fun parsePatternString(): Expr {
        val str = expect<PatternStringT>(withError(E.literalExpected("pattern string")))
        return Expr.PatternLiteral(str.value.s, str.value.raw).withSpanAndComment(str)
    }

    private fun parseChar(): Expr {
        val str = expect<CharT>(withError(E.literalExpected("char")))
        return Expr.CharE(str.value.c, str.value.raw).withSpanAndComment(str)
    }

    private fun parseBool(): Expr {
        val bool = expect<BoolT>(withError(E.literalExpected("boolean")))
        return Expr.Bool(bool.value.b).withSpanAndComment(bool)
    }

    private fun parseVar(): Expr.Var {
        val v = expect<Ident>(withError(E.VARIABLE))
        return Expr.Var(v.value.v).withSpanAndComment(v) as Expr.Var
    }

    private fun parseOperator(): Expr {
        val op = expect<Op>(withError("Expected Operator."))
        return Expr.Operator(op.value.op, op.value.isPrefix).withSpanAndComment(op)
    }

    private fun parseAliasedVar(alias: Spanned<UpperIdent>): Expr {
        expect<Dot>(noErr())
        return if (iter.peek().value is Ident) {
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

    private fun parseLambda(): Expr {
        val begin = iter.peek()
        expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val vars = tryParseListOf { tryParsePattern(true) }
        if (vars.isEmpty()) throwError(withError(E.LAMBDA_VAR)(iter.current()))

        val arr = expect<Arrow>(withError(E.LAMBDA_ARROW))

        val exp = if (arr.span.sameLine(iter.peek().span)) parseExpression() else withOffside { parseDo() }
        return Expr.Lambda(vars, exp)
            .withSpan(begin.span, exp.span)
            .withComment(begin.comment)
    }

    private fun parseIf(): Expr {
        val ifTk = expect<IfT>(noErr())
        val offside = iter.offside() + 1

        var hasElse = false
        var els: Spanned<*>? = null
        val (cond, thens) = withIgnoreOffside {
            val cond = parseExpression()

            val th = expect<Then>(withError(E.THEN))

            val thens = withIgnoreOffside(false) {
                withOffside(offside) {
                    if (th.span.sameLine(iter.peek().span)) parseExpression() else parseDo()
                }
            }

            if (iter.peek().value is Else) {
                els = expect<Else>(withError(E.ELSE))
                hasElse = true
            }
            cond to thens
        }

        val elses = if (hasElse) {
            if (els?.span?.sameLine(iter.peek().span) == true) parseExpression() else parseDo()
        } else null

        return Expr.If(cond, thens, elses)
            .withSpan(ifTk.span, elses?.span ?: thens.span)
            .withComment(ifTk.comment)
    }

    private fun parseLet(): Expr {
        val let = expect<LetT>(noErr())
        val isInstance = if (iter.peek().value is Instance) {
            iter.next()
            true
        } else false

        var def: LetDef? = null
        withOffside {
            def = if (isInstance || iter.peek().value is Ident) {
                parseLetDefBind(isInstance)
            } else parseLetDefPattern()
        }

        if (iter.peek().value !is In) {
            return Expr.DoLet(def!!).withSpan(span(let.span, iter.current().span)).withComment(let.comment)
        }
        withIgnoreOffside { expect<In>(withError(E.LET_IN)) }

        val exp = parseExpression()

        val span = span(let.span, exp.span)
        return Expr.Let(def!!, exp).withSpan(span).withComment(let.comment)
    }

    private fun parseLetBang(): Expr {
        val let = expect<LetBang>(noErr())

        val def = withOffside { parseLetDefPattern() }

        if (iter.peek().value !is In) {
            return Expr.LetBang(def, null).withSpan(span(let.span, iter.current().span)).withComment(let.comment)
        }
        withIgnoreOffside { expect<In>(withError(E.LET_IN)) }

        val exp = parseExpression()

        val span = span(let.span, exp.span)
        return Expr.LetBang(def, exp).withSpan(span).withComment(let.comment)
    }

    private fun parseFor(): Expr {
        val forr = expect<For>(noErr())

        val def = withOffside { parseLetDefPattern(isFor = true) }
        withIgnoreOffside { expect<Do>(withError(E.FOR_DO)) }

        val exp = parseDo()
        val span = span(forr.span, exp.span)
        return Expr.For(def, exp).withSpan(span).withComment(forr.comment)
    }

    private fun parseLetDefBind(isInstance: Boolean): LetDef {
        val ident = expect<Ident>(withError(E.LET_DECL))
        val name = ident.value.v

        var type: Type? = null
        if (iter.peek().value is Colon) {
            type = withOffside(ident.offside() + 1) {
                iter.next()
                parseType()
            }
            val newIdent = expect<Ident>(withError(E.expectedLetDefinition(name)))
            if (newIdent.value.v != name) throwError(withError(E.expectedLetDefinition(name))(newIdent))
        }
        val vars = tryParseListOf { tryParsePattern(true) }
        val eq = expect<Equals>(withError(E.LET_EQUALS))
        val exp = if (eq.span.sameLine(iter.peek().span)) parseExpression() else parseDo()
        return LetDef.DefBind(Binder(ident.value.v, ident.span), vars, exp, isInstance, type)
    }

    private fun parseLetDefPattern(isFor: Boolean = false): LetDef {
        val pat = parsePattern(true)

        val tk = if (isFor) expect<In>(withError(E.FOR_IN))
        else expect<Equals>(withError(E.LET_EQUALS))
        val exp = if (tk.span.sameLine(iter.peek().span)) parseExpression() else parseDo()
        return LetDef.DefPattern(pat, exp)
    }

    private fun parseTryCatch(): Expr {
        fun validCase(c: Case) {
            if (c.patterns.size != 1)
                throwError(E.wrongArityToCase(c.patterns.size, 1) to c.patternSpan())
            val pat = c.patterns[0]
            if (pat !is Pattern.TypeTest || c.guard != null)
                throwError(E.WRONG_CATCH to pat.span)
        }

        val tryTk = expect<TryT>(noErr())
        val tryExp = withOffside { parseDo() }
        val cases = mutableListOf<Case>()
        if (iter.peek().value is CatchT) {
            expect<CatchT>(noErr())

            if (iter.peekIsOffside()) throwMismatchedIndentation(iter.peek())
            val align = iter.peek().offside()
            withIgnoreOffside(false) {
                withOffside(align) {
                    val first = parseCase()
                    validCase(first)
                    cases += first

                    var tk = iter.peek()
                    while (!iter.peekIsOffside() && tk.value !in statementEnding) {
                        val case = parseCase()
                        validCase(case)
                        cases += case
                        tk = iter.peek()
                    }
                }
            }
        }

        val fin = if (iter.peek().value is FinallyT || cases.isEmpty()) {
            expect<FinallyT>(withError(E.NO_FINALLY))
            withOffside { parseDo() }
        } else null
        return Expr.TryCatch(tryExp, cases, fin)
            .withSpan(tryTk.span, iter.current().span)
            .withComment(tryTk.comment)
    }

    private fun parseMatch(): Expr {
        val caseTk = expect<CaseT>(noErr())

        val exps = withIgnoreOffside {
            val exps = between<Comma, Expr>(::parseExpression)
            expect<Of>(withError(E.CASE_OF))
            exps
        }
        val arity = exps.size

        val align = iter.peek().offside()
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
        return withOffside { Case(pats, parseDo(), guard) }
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
                val v = Expr.Var(tk.value.v).withSpan(tk.span) as Expr.Var
                Pattern.Var(v)
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
            is BigintT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.BigintLiteral(Expr.Bigint(tk.value.v, tk.value.text)), tk.span)
            }
            is BigdecT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.BigdecPattern(Expr.Bigdec(tk.value.v, tk.value.text)), tk.span)
            }
            is CharT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.CharLiteral(Expr.CharE(tk.value.c, tk.value.raw)), tk.span)
            }
            is StringT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.StringLiteral(Expr.StringE(tk.value.s, tk.value.raw)), tk.span)
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
                    Pattern.Record(rows, span(tk.span, end))
                }
            }
            is LSBracket -> {
                iter.next()
                if (iter.peek().value is RSBracket) {
                    val end = iter.next().span
                    Pattern.ListP(emptyList(), null, span(tk.span, end))
                } else {
                    val elems = between<Comma, Pattern>(::parsePattern)
                    if (iter.peek().value.isDoubleColon()) {
                        iter.next()
                        val tail = parsePattern()
                        val end = expect<RSBracket>(withError(E.rsbracketExpected("list pattern"))).span
                        Pattern.ListP(elems, tail, span(tk.span, end))
                    } else {
                        val end = expect<RSBracket>(withError(E.rsbracketExpected("list pattern"))).span
                        Pattern.ListP(elems, null, span(tk.span, end))
                    }
                }
            }
            is PatternStringT -> {
                val pt = parsePatternString()
                Pattern.RegexPattern(pt as Expr.PatternLiteral)
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
        } ?: return null

        // named, tuple and annotation patterns
        return when (iter.peek().value) {
            is As -> {
                iter.next()
                val name = expect<Ident>(withError(E.VARIABLE))
                Pattern.Named(pat, Spanned(name.span, name.value.v), span(pat.span, name.span))
            }
            is Colon -> {
                if (pat is Pattern.Var) {
                    iter.next()
                    val type = parseType()
                    Pattern.TypeAnnotation(pat, type, span(pat.span, type.span))
                } else pat
            }
            is Semicolon -> {
                iter.next()
                val p2 = parsePattern(isDestructuring)
                Pattern.TuplePattern(pat, p2, span(pat.span, p2.span))
            }
            else -> pat
        }
    }

    private fun parsePatternRow(): Pair<String, Pattern> {
        val (label, tk) = parseLabel()
        if (iter.peek().value !is Colon && tk.value is Ident) {
            val v = Expr.Var(label).withSpan(tk.span) as Expr.Var
            return label to Pattern.Var(v)
        }
        expect<Colon>(withError(E.RECORD_COLON))
        val pat = parsePattern()
        return label to pat
    }

    private fun parseLabel(error: String = E.RECORD_LABEL): Pair<String, Spanned<Token>> {
        return if (iter.peek().value is Ident) {
            val tk = expect<Ident>(noErr())
            tk.value.v to tk
        } else {
            val tk = expect<StringT>(withError(error))
            tk.value.s to tk
        }
    }

    private fun parseJavaName(): Pair<String, Spanned<Token>> {
        return if (iter.peek().value is Ident) {
            val tk = expect<Ident>(noErr())
            tk.value.v to tk
        } else {
            val tk = expect<Op>(withError(E.RECORD_LABEL))
            if (!tk.value.isPrefix) throwError(E.RECORD_LABEL to tk.span)
            tk.value.op to tk
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
            } else if (nex is Dot) {
                parseRecordSetOrUpdateList(begin)
            } else if (nex is Op && nex.op == "-") {
                iter.next()
                parseRecordRestriction(begin)
            } else if (nex is Op && nex.op == "+") {
                iter.next()
                parseRecordMerge(begin)
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
        val (label, tk) = parseLabel()
        if (iter.peek().value !is Colon && tk.value is Ident) {
            val exp = Expr.Var(label).withSpanAndComment(tk)
            return label to exp
        }
        expect<Colon>(withError(E.RECORD_COLON))
        val exp = parseExpression()
        return label to exp
    }

    private fun parseRecordSetOrUpdateList(begin: Spanned<Token>): Expr {
        val vals = between<Comma, RecUpdate>(::parseRecordSetOrUpdate)
        expect<Pipe>(withError(E.pipeExpected("record set/update")))
        val record = parseExpression()
        expect<RBracket>(withError(E.rbracketExpected("record set/update")))

        return Expr.RecordUpdate(exp = record, updates = vals)
            .withSpan(begin.span, iter.current().span).withComment(begin.comment)
    }

    private fun parseRecordSetOrUpdate(): RecUpdate {
        expect<Dot>(withError(E.RECORD_DOT))
        val labels = between<Dot, Pair<String, Spanned<Token>>>(::parseLabel)
        var isSet = true
        if (iter.peek().value is Equals) expect<Equals>(withError(E.RECORD_EQUALS))
        else {
            isSet = false
            expect<Arrow>(withError(E.RECORD_EQUALS))
        }

        val value = parseExpression()
        return RecUpdate(labels.map { Spanned(it.second.span, it.first) }, value, isSet)
    }

    private fun parseRecordRestriction(begin: Spanned<Token>): Expr {
        val labels = between<Comma, Pair<String, Spanned<Token>>>(::parseLabel)
        expect<Pipe>(withError(E.pipeExpected("record restriction")))
        val record = parseExpression()
        val end = expect<RBracket>(withError(E.rbracketExpected("record restriction")))
        return Expr.RecordRestrict(record, labels.map { it.first })
            .withSpan(begin.span, end.span).withComment(begin.comment)
    }

    private fun parseRecordMerge(begin: Spanned<Token>): Expr {
        val exp1 = parseExpression()
        expect<Comma>(withError(E.commaExpected("expression in record merge")))
        val exp2 = parseExpression()
        val end = expect<RBracket>(withError(E.rbracketExpected("record merge")))
        return Expr.RecordMerge(exp1, exp2)
            .withSpan(begin.span, end.span).withComment(begin.comment)
    }

    private fun parseArglist(ctx: String): Pair<List<Expr>, Span> {
        return withIgnoreOffside {
            expect<LParen>(withError(E.lparensExpected(ctx)))
            if (iter.peek().value is RParen) {
                val end = expect<RParen>(withError(E.rparensExpected(ctx)))
                emptyList<Expr>() to end.span
            } else {
                val args = between<Comma, Expr>(::parseExpression)
                val end = expect<RParen>(withError(E.rparensExpected(ctx)))
                args to end.span
            }
        }
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
        val ctorTk = expect<UpperIdent>(withError(E.CTOR_NAME))
        val ctor = Spanned(ctorTk.span, ctorTk.value.v)

        val vis = if (visibility != null && visibility is PublicPlus) Visibility.PUBLIC else Visibility.PRIVATE

        val pars = tryParseListOf { parseTypeAtom(true) }

        val freeVars = pars.flatMap { it.findFreeVars(typeVars) }
        if (freeVars.isNotEmpty()) {
            throwError(E.undefinedVarInCtor(ctor.value, freeVars) to span(ctor.span, iter.current().span))
        }
        return DataConstructor(ctor, pars, vis, span(ctor.span, iter.current().span))
    }

    private fun parseDo(): Expr {
        val spanned = iter.peek()
        if (spanned.value is DoDot) return parseExpression()

        if (iter.peekIsOffside()) throwMismatchedIndentation(spanned)
        val align = spanned.offside()
        return withIgnoreOffside(false) {
            withOffside(align) {
                var tk: Spanned<Token>
                val exps = mutableListOf<Expr>()
                do {
                    exps += parseExpression(inDo = true)
                    tk = iter.peek()
                } while (!iter.peekIsOffside() && tk.value !in statementEnding)

                if (exps.size == 1) exps[0]
                else {
                    Expr.Do(exps).withSpan(spanned.span, iter.current().span)
                }
            }
        }
    }

    private fun parseWhile(): Expr {
        val whil = expect<WhileT>(noErr())
        val cond = withIgnoreOffside {
            val exp = parseExpression()
            expect<Do>(withError(E.DO_WHILE))
            exp
        }
        if (!cond.isSimple()) throwError(E.EXP_SIMPLE to cond.span)

        if (iter.peekIsOffside()) throwMismatchedIndentation(iter.peek())
        val align = iter.peek().offside()
        return withIgnoreOffside(false) {
            withOffside(align) {
                var tk: Spanned<Token>
                val exps = mutableListOf<Expr>()
                do {
                    exps += parseExpression(inDo = true)
                    tk = iter.peek()
                } while (!iter.peekIsOffside() && tk.value !in statementEnding)
                Expr.While(cond, exps).withSpan(whil.span, iter.current().span).withComment(whil.comment)
            }
        }
    }

    private fun parseComputation(): Expr {
        val doo = expect<DoDot>(noErr())
        val builder = parseVar()

        if (iter.peekIsOffside()) throwMismatchedIndentation(iter.peek())
        val align = iter.peek().offside()
        return withIgnoreOffside(false) {
            withOffside(align) {
                var tk: Spanned<Token>
                val exps = mutableListOf<Expr>()
                do {
                    exps += parseExpression(inDo = true)
                    tk = iter.peek()
                } while (!iter.peekIsOffside() && tk.value !in statementEnding)
                Expr.Computation(builder, exps)
                    .withSpan(doo.span, iter.current().span).withComment(doo.comment)
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

    private fun parseIdentOrString(): String {
        val tk = iter.next()
        return when (tk.value) {
            is UpperIdent -> tk.value.v
            is Ident -> tk.value.v
            is StringT -> tk.value.s
            else -> throwError(withError(E.UPPER_LOWER_STR)(tk))
        }
    }

    private fun parseMetadata(): Metadata? {
        if (iter.peek().value !is MetaBracket) return null

        val begin = expect<MetaBracket>(noErr())
        val rows = between<Comma, Pair<String, Expr>>(::parseMetadataRow)
        val end = expect<RSBracket>(withError(E.rsbracketExpected("metadata")))

        val exp = Expr.RecordExtend(rows, Expr.RecordEmpty())
            .withSpan(begin.span, end.span) as Expr.RecordExtend
        return Metadata(exp)
    }

    private fun parseMetadataRow(): Pair<String, Expr> {
        val (label, tk) = parseLabel()
        if (iter.peek().value !is Colon && tk.value is Ident) {
            return label to Expr.Bool(true).withSpanAndComment(tk)
        }
        expect<Colon>(withError(E.RECORD_COLON))
        val exp = parseMetadataExpr()
        return label to exp
    }

    private fun parseMetadataExpr(): Expr = when (iter.peek().value) {
        is IntT -> parseInt32()
        is LongT -> parseInt64()
        is FloatT -> parseFloat32()
        is DoubleT -> parseFloat64()
        is StringT -> parseString()
        is CharT -> parseChar()
        is BoolT -> parseBool()
        is LSBracket -> parseMetadataList()
        is LBracket -> parseMetadataRecord()
        else -> throwError(E.INVALID_META_EXPR to iter.peek().span)
    }

    private fun parseMetadataList(): Expr {
        val begin = expect<LSBracket>(noErr())
        val exprs = between<Comma, Expr>(::parseMetadataExpr)
        val end = expect<RSBracket>(withError(E.rsbracketExpected("list")))
        return Expr.ListLiteral(exprs).withSpan(begin.span, end.span)
    }

    private fun parseMetadataRecord(): Expr {
        val begin = expect<LBracket>(noErr())
        val rows = between<Comma, Pair<String, Expr>>(::parseMetadataRow)
        val end = expect<RBracket>(withError(E.rbracketExpected("record")))
        return Expr.RecordExtend(rows, Expr.RecordEmpty())
            .withSpan(begin.span, end.span)
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

    private fun <T> withOffside(off: Int = iter.offside() + 1, f: () -> T): T {
        val tmp = iter.offside()
        iter.withOffside(off)
        try {
            return f()
        } finally {
            iter.withOffside(tmp)
        }
    }

    private inline fun <T> withIgnoreOffside(shouldIgnore: Boolean = true, f: () -> T): T {
        val tmp = iter.ignoreOffside()
        iter.withIgnoreOffside(shouldIgnore)
        try {
            return f()
        } finally {
            iter.withIgnoreOffside(tmp)
        }
    }

    /**
     * Fast-forward the lexer to the next declaration.
     */
    private fun fastForward() {
        var peek = iter.peek()
        if (peek.value is EOF) return

        do {
            iter.next()
            peek = iter.peek()
        } while (peek.value !is EOF && peek.offside() != 1)
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

        private val statementEnding = setOf(RParen, RSBracket, RBracket, Else, In, EOF, Comma)
    }
}

class ParserError(val msg: String, val span: Span) : java.lang.RuntimeException(msg)
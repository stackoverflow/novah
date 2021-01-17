package novah.frontend

import novah.Util.splitAt
import novah.ast.source.*
import novah.data.Err
import novah.data.Ok
import novah.data.PeekableIterator
import novah.data.Result
import novah.frontend.Token.*
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.error.Errors as E

class Parser(tokens: Iterator<Spanned<Token>>, private val sourceName: String = "<Unknown>") {
    private val iter = PeekableIterator(tokens, ::throwMismatchedIndentation)

    private var nested = false

    private var moduleName: String? = null

    private data class ModuleDef(val name: String, val exports: ModuleExports, val span: Span, val comment: Comment?)

    fun parseFullModule(): Result<Module, CompilerProblem> {
        return try {
            Ok(innerParseFullModule())
        } catch (le: LexError) {
            // for now we just return the first error
            Err(CompilerProblem(le.msg, ProblemContext.PARSER, le.pos.span(), sourceName, moduleName))
        } catch (pe: ParserError) {
            Err(CompilerProblem(pe.msg, ProblemContext.PARSER, pe.span, sourceName, moduleName))
        }
    }

    private fun innerParseFullModule(): Module {
        val (mname, exports, mspan, comment) = parseModule()
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

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            decls += parseDecl()
        }

        return Module(
            mname,
            sourceName,
            imports,
            exports,
            foreigns,
            decls,
            mspan
        ).withComment(comment)
    }

    private fun parseModule(): ModuleDef {
        val m = expect<ModuleT>(withError(E.MODULE_DEFINITION))

        val name = parseModuleName()

        val exports = when (iter.peek().value) {
            is Hiding -> {
                iter.next()
                ModuleExports.Hiding(parseDeclarationRefs("exports"))
            }
            is Exposing -> {
                iter.next()
                ModuleExports.Exposing(parseDeclarationRefs("exports"))
            }
            else -> ModuleExports.ExportAll
        }
        return ModuleDef(name.joinToString("."), exports, span(m.span, iter.current().span), m.comment)
    }

    private fun parseDeclarationRefs(ctx: String): List<DeclarationRef> {
        expect<LParen>(withError(E.lparensExpected(ctx)))
        if (iter.peek().value is RParen) {
            throwError(withError(E.emptyImportExport(ctx))(iter.peek()))
        }

        val exps = between<Comma, DeclarationRef> { parseDeclarationRef() }

        expect<RParen>(withError(E.rparensExpected(ctx)))
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
            else -> throwError(withError(E.EXPORT_REFER)(sp))
        }
    }

    private fun parseModuleName(): List<String> {
        return between<Dot, String> { expect<Ident>(withError(E.MODULE_NAME)).value.v }
    }

    private fun parseImport(): Import {
        val impTk = expect<ImportT>(noErr())
        val mname = parseModuleName().joinToString(".")

        val import = when (iter.peek().value) {
            is LParen -> {
                val imp = parseDeclarationRefs("import")
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
            return if (tk is Op && tk.op == ":") {
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
                ForeignImport.Type(parseFullName(), parseAlias(false), mkspan())
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
                val (type, name) = if (!static) maybename.splitAt(idx) else maybename to parseFullName()
                if (tk.v == "get") ForeignImport.Getter(type, name, static, parseAlias(), mkspan())
                else ForeignImport.Setter(type, name, static, forceAlias("setter"), mkspan())
            }
            else -> {
                val maybename = parseFullName()
                val static = parseStatic()
                val idx = maybename.lastIndexOf('.')
                val (type, name) = if (!static) maybename.splitAt(idx) else maybename to parseFullName()
                ForeignImport.Method(type, name, parsePars("method"), static, parseAlias(), mkspan())
            }
        }
    }

    private fun parseDecl(): Decl {
        val tk = iter.peek()
        val comment = tk.comment
        val decl = when (tk.value) {
            is TypeT -> parseDataDecl()
            is Ident -> parseVarDecl()
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseDataDecl(): Decl {
        val typ = expect<TypeT>(noErr())
        return withOffside(typ.offside() + 1, false) {

            val name = expect<UpperIdent>(withError(E.DATA_NAME)).value.v

            val tyVars = parseListOf(::parseTypeVar) { it is Ident }

            expect<Equals>(withError(E.DATA_EQUALS))

            val ctors = mutableListOf<DataConstructor>()
            while (true) {
                ctors += parseDataConstructor(tyVars)
                if (iter.peekIsOffside() || iter.peek().value is EOF) break
                expect<Pipe>(withError(E.pipeExpected("constructor")))
            }
            Decl.DataDecl(name, tyVars, ctors)
                .withSpan(typ.span, iter.current().span)
        }
    }

    private fun parseVarDecl(): Decl {
        val nameTk = expect<Ident>(noErr())
        val name = nameTk.value.v
        return withOffside(nameTk.offside() + 1, false) {
            if (iter.peek().value is DoubleColon) {
                parseTypeSignature(name)
            } else {
                val vars = tryParseListOf { tryParseIdent() }

                expect<Equals>(withError(E.EQUALS))

                val exp = parseExpression()
                Decl.ValDecl(name, vars, exp).withSpan(nameTk.span, exp.span)
            }
        }
    }

    private fun parseTypeSignature(name: String): Decl.TypeDecl {
        expect<DoubleColon>(withError(E.TYPE_DCOLON))
        return Decl.TypeDecl(name, parsePolytype())
    }

    private fun tryParseIdent(): Binder? {
        val tk = iter.peek()
        return when (tk.value) {
            is Ident -> {
                iter.next()
                Binder(tk.value.v, tk.span)
            }
            else -> null
        }
    }

    private fun parseExpression(): Expr {
        val tk = iter.peek()
        val exps = tryParseListOf(true, ::tryParseAtom)

        val unrolled = Application.parseApplication(exps) ?: throwError(withError(E.MALFORMED_EXPR)(tk))

        // type signatures have the lowest precendece
        return if (iter.peek().value is DoubleColon) {
            val dc = iter.next()
            val pt = parsePolytype()
            Expr.Ann(unrolled, pt)
                .withSpan(dc.span, iter.current().span)
                .withComment(dc.comment)
        } else unrolled
    }

    private fun tryParseAtom(): Expr? = when (iter.peek().value) {
        is IntT -> parseInt()
        is LongT -> parseLong()
        is FloatT -> parseFloat()
        is DoubleT -> parseDouble()
        is StringT -> parseString()
        is CharT -> parseChar()
        is BoolT -> parseBool()
        is Ident -> parseVar()
        is Op -> parseOperator()
        is LParen -> {
            withIgnoreOffside {
                iter.next()
                val exp = parseExpression()
                expect<RParen>(withError(E.rparensExpected("expression")))
                Expr.Parens(exp)
            }
        }
        is UpperIdent -> {
            val uident = expect<UpperIdent>(noErr())
            if (iter.peek().value is Dot) {
                parseAliasedVar(uident)
            } else {
                Expr.Constructor(uident.value.v)
            }
        }
        is Backslash -> parseLambda()
        is IfT -> parseIf()
        is LetT -> parseLet()
        is CaseT -> parseMatch()
        is Do -> parseDo()
        else -> null
    }

    private fun parseInt(): Expr {
        val num = expect<IntT>(withError(E.literalExpected("integer")))
        return Expr.IntE(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseLong(): Expr {
        val num = expect<LongT>(withError(E.literalExpected("long")))
        return Expr.LongE(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseFloat(): Expr {
        val num = expect<FloatT>(withError(E.literalExpected("float")))
        return Expr.FloatE(num.value.v, num.value.text).withSpanAndComment(num)
    }

    private fun parseDouble(): Expr {
        val num = expect<DoubleT>(withError(E.literalExpected("double")))
        return Expr.DoubleE(num.value.v, num.value.text).withSpanAndComment(num)
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

        val vars = tryParseListOf { tryParseIdent() }
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

    private fun parseLet(): Expr {
        val let = expect<LetT>(noErr())

        val types = mutableListOf<Decl.TypeDecl>()
        val defsCtx = mutableListOf<LetDef>()

        val tk = iter.peek()
        val align = tk.offside()
        if (align <= iter.offside()) throwMismatchedIndentation(tk)

        val defs = mutableListOf<LetDef>()

        withOffside(align) {
            while (iter.peek().value != In) {
                defs += parseLetDef(types, defsCtx)
            }
        }

        withIgnoreOffside { expect<In>(withError(E.LET_IN)) }

        val exp = parseExpression()

        val span = span(let.span, exp.span)
        return Expr.Let(defs, exp).withSpan(span).withComment(let.comment)
    }

    private tailrec fun parseLetDef(types: MutableList<Decl.TypeDecl>, letDefs: MutableList<LetDef>): LetDef {
        val ident = expect<Ident>(withError(E.LET_DECL))

        return when (iter.peek().value) {
            is DoubleColon -> {
                if (letDefs.any { it.name.name == ident.value.v }) {
                    throwError(withError(E.LET_TYPE)(ident))
                }
                val tdecl = withOffside {
                    iter.next()
                    Decl.TypeDecl(ident.value.v, parsePolytype())
                }
                types += tdecl
                parseLetDef(types, letDefs)
            }
            else -> {
                withOffside {
                    val vars = tryParseListOf { tryParseIdent() }
                    expect<Equals>(withError(E.LET_EQUALS))
                    val exp = parseExpression()
                    val span = span(ident.span, exp.span)
                    val def =
                        LetDef(Binder(ident.value.v, span), vars, exp, types.find { it.name == ident.value.v }?.type)
                    letDefs += def
                    def
                }
            }
        }
    }

    private fun parseMatch(): Expr {
        val case = expect<CaseT>(noErr())

        val exp = withIgnoreOffside {
            val exp = parseExpression()
            expect<Of>(withError(E.CASE_OF))
            exp
        }

        val firstTk = iter.peek()
        val align = firstTk.offside()
        if (iter.peekIsOffside() || (nested && align <= iter.offside())) {
            throwMismatchedIndentation(firstTk)
        }
        return withIgnoreOffside(false) {
            withOffside(align) {
                val cases = mutableListOf<Case>()
                val first = parseCase()
                cases += first

                var tk = iter.peek()
                while (!iter.peekIsOffside() && tk.value !in statementEnding) {
                    cases += parseCase()
                    tk = iter.peek()
                }
                Expr.Match(exp, cases)
                    .withSpan(case.span, iter.current().span)
                    .withComment(case.comment)
            }
        }
    }

    private fun parseCase(): Case {
        val pat = withIgnoreOffside {
            val pat = parsePattern()
            expect<Arrow>(withError(E.CASE_ARROW))
            pat
        }
        return withOffside { Case(pat, parseExpression()) }
    }

    private fun parsePattern(): Pattern {
        return tryParsePattern() ?: throwError(withError(E.PATTERN)(iter.peek()))
    }

    private fun tryParsePattern(): Pattern? {
        val tk = iter.peek()
        return when (tk.value) {
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
                Pattern.LiteralP(LiteralPattern.IntLiteral(Expr.IntE(tk.value.v, tk.value.text)), tk.span)
            }
            is LongT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.LongLiteral(Expr.LongE(tk.value.v, tk.value.text)), tk.span)
            }
            is FloatT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.FloatLiteral(Expr.FloatE(tk.value.v, tk.value.text)), tk.span)
            }
            is DoubleT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.DoubleLiteral(Expr.DoubleE(tk.value.v, tk.value.text)), tk.span)
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
                val pat = parsePattern()
                val tkEnd = expect<RParen>(withError(E.rparensExpected("pattern declaration")))
                Pattern.Parens(pat, span(tk.span, tkEnd.span))
            }
            is UpperIdent -> {
                val ctor = parseConstructor()
                val fields = tryParseListOf { tryParsePattern() }
                Pattern.Ctor(ctor, fields, span(tk.span, fields.lastOrNull()?.span ?: tk.span))
            }
            else -> null
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

    private fun parseDataConstructor(typeVars: List<String>): DataConstructor {
        val ctor = expect<UpperIdent>(withError(E.CTOR_NAME))

        val pars = tryParseListOf { parseTypeAtom(true) }

        val freeVars = pars.flatMap { it.findFreeVars(typeVars) }
        if (freeVars.isNotEmpty()) {
            val tk = ctor.copy(span = span(ctor.span, iter.current().span))
            throwError(withError(E.undefinedVarInCtor(ctor.value.v, freeVars))(tk))
        }
        return DataConstructor(ctor.value.v, pars, span(ctor.span, iter.current().span))
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
                val first = parseExpression()
                exps += first

                var tk = iter.peek()
                while (!iter.peekIsOffside() && tk.value !in statementEnding) {
                    exps += parseExpression()
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

    private fun parsePolytype(inConstructor: Boolean = false): Type {
        return if (iter.peek().value is Forall) {
            iter.next()
            val tyVars = parseListOf(::parseTypeVar) { it is Ident }
            if (tyVars.isEmpty()) throwError(withError(E.FORALL_TVARS)(iter.peek()))
            expect<Dot>(withError(E.FORALL_DOT))
            Type.TForall(tyVars, parseType(inConstructor))
        } else parseType(inConstructor)
    }

    private fun parseType(inConstructor: Boolean = false): Type {
        return parseTypeAtom(inConstructor) ?: throwError(withError(E.TYPE_DEF)(iter.peek()))
    }

    private fun parseTypeAtom(inConstructor: Boolean = false): Type? {
        if (iter.peekIsOffside()) return null
        val tk = iter.peek()

        val ty = when (tk.value) {
            is Forall -> parsePolytype(inConstructor)
            is LParen -> {
                iter.next()
                withIgnoreOffside {
                    val typ = parseType(false)
                    expect<RParen>(withError(E.rparensExpected("type definition")))
                    Type.TParens(typ)
                }
            }
            is Ident -> Type.TVar(parseTypeVar())
            is UpperIdent -> {
                var ty = parseUpperIdent().v
                var alias: String? = null
                if (iter.peek().value is Dot) {
                    iter.next()
                    alias = ty
                    ty = expect<UpperIdent>(withError(E.TYPEALIAS_DOT)).value.v
                }
                if (inConstructor) {
                    Type.TVar(ty, alias)
                } else {
                    val pars = tryParseListOf(true) { parseTypeAtom(true) }

                    if (pars.isEmpty()) Type.TVar(ty, alias)
                    else Type.TConstructor(ty, pars, alias)
                }
            }
            else -> null
        } ?: return null

        if (inConstructor) return ty
        return when (iter.peek().value) {
            is Arrow -> {
                iter.next()
                Type.TFun(ty, parseType())
            }
            else -> ty
        }
    }

    private fun parseUpperOrLowerIdent(): String {
        val tk = iter.next()
        return when (tk.value) {
            is UpperIdent -> tk.value.v
            is Ident -> tk.value.v
            else -> throwError(withError(E.UPPER_LOWER)(tk))
        }
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

        private fun span(s: Span, e: Span) = Span(s.startLine, s.startColumn, e.endLine, e.endColumn)

        private val statementEnding = listOf(RParen, RSBracket, RBracket, EOF)
    }
}

class ParserError(val msg: String, val span: Span) : java.lang.RuntimeException(msg)
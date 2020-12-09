package novah.frontend

import novah.frontend.Token.*
import novah.frontend.typechecker.Type as TType
import novah.frontend.Errors as E

class Parser(tokens: Iterator<Spanned<Token>>) {
    private val iter = PeekableIterator(tokens, ::throwMismatchedIndentation)

    private var imports = listOf<Import>()

    private val topLevelTypes = mutableMapOf<String, TType>()

    private var nested = false

    fun parseFullModule(): Module {
        val (mname, exports, comment) = parseModule()

        val imports = mutableListOf<Import>()
        while (iter.peek().value is ImportT) {
            imports += parseImport()
        }
        this.imports = imports

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            decls += parseDecl()
        }

        val dataCtors = decls.filterIsInstance<Decl.DataDecl>()
        // during parsing we don't know if a type is just a Var or
        // a no-parameter constructor. Now we know, so replace it
        resolveConstructors(dataCtors)

        // if the declaration has a type annotation, annotate it
        val annDecls = decls.map { decl ->
            when (decl) {
                is Decl.ValDecl -> {
                    topLevelTypes[decl.name]?.let { type ->
                        decl.copy(exp = Expr.Ann(decl.exp, type))
                    } ?: decl
                }
                else -> decl
            }
        }

        return Module(
            mname,
            imports,
            exports,
            annDecls
        ).withComment(comment)
    }

    private fun resolveConstructors(dataCtors: List<Decl.DataDecl>) {
        val resolved = mutableMapOf<String, TType>()
        for ((k, type) in topLevelTypes) {
            var stype = type
            for (ctor in dataCtors) {
                stype = stype.substTVar(ctor.name, TType.TConstructor(ctor.name))
            }
            resolved[k] = stype
        }
        topLevelTypes.clear()
        topLevelTypes.putAll(resolved)
    }

    private fun parseModule(): Triple<ModuleName, ModuleExports, Comment?> {
        val m = expect<ModuleT>(withError(E.MODULE_DEFINITION))

        val name = parseModuleName()

        val exports = when (iter.peek().value) {
            is Hiding -> {
                iter.next()
                ModuleExports.Hiding(parseImportExports("exports"))
            }
            is Exposing -> {
                iter.next()
                ModuleExports.Exposing(parseImportExports("exports"))
            }
            else -> ModuleExports.ExportAll
        }
        return Triple(name, exports, m.comment)
    }

    private fun parseImportExports(ctx: String): List<String> {
        expect<LParen>(withError(E.lparensExpected(ctx)))
        if (iter.peek().value is RParen) {
            throwError(withError(E.emptyImportExport(ctx))(iter.peek()))
        }

        val exps = between<Comma, String> { parseUpperOrLoweIdent(withError(E.EXPORT_REFER)) }

        expect<RParen>(withError(E.rparensExpected(ctx)))
        return exps
    }

    private fun parseModuleName(): ModuleName {
        return between<Dot, String> { expect<Ident>(withError(E.MODULE_NAME)).value.v }
    }

    private fun parseImport(): Import {
        val impTk = expect<ImportT>(noErr())
        val mname = parseModuleName()

        val import = when (iter.peek().value) {
            is LParen -> {
                val imp = parseImportExports("import")
                if (iter.peek().value is As) {
                    iter.next()
                    val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                    Import.Exposing(mname, imp, alias.value.v)
                } else Import.Exposing(mname, imp)
            }
            is As -> {
                iter.next()
                val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                Import.Raw(mname, alias.value.v)
            }
            else -> Import.Raw(mname)
        }

        return import.withComment(impTk.comment)
    }

    private fun parseDecl(): Decl {
        val tk = iter.peek()
        val comment = tk.comment
        val decl = when (tk.value) {
            is Type -> parseDataDecl()
            is Ident -> parseVarDecl()
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseDataDecl(): Decl {
        val typ = expect<Type>(noErr())
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
        // transforms `fun x y z = 1` into `fun = \x -> \y -> \z -> 1`
        fun desugarToLambda(acc: List<String>, exp: Expr, span: Span, c: Comment?): Expr {
            return if (acc.size <= 1) {
                Expr.Lambda(acc[0], exp, true).withSpan(span).withComment(c)
            } else {
                Expr.Lambda(acc[0], desugarToLambda(acc.drop(1), exp, span, c), true)
                    .withSpan(span)
                    .withComment(c)
            }
        }

        val nameTk = expect<Ident>(noErr())
        val name = nameTk.value.v
        return withOffside(nameTk.offside() + 1, false) {
            if (iter.peek().value is DoubleColon) {
                parseTypeSignature(name)
            } else {
                val vars = tryParseListOf { tryParseIdent() }

                expect<Equals>(withError(E.EQUALS))

                val exp = parseExpression().let {
                    val span = span(nameTk.span, it.span)
                    if (vars.isEmpty()) it else desugarToLambda(vars, it, span, nameTk.comment)
                }

                Decl.ValDecl(name, exp).withSpan(nameTk.span, exp.span)
            }
        }
    }

    private fun parseTypeSignature(name: String): Decl.TypeDecl {
        expect<DoubleColon>(withError(E.TYPE_DCOLON))
        val decl = Decl.TypeDecl(name, parsePolytype())
        topLevelTypes[name] = decl.typ
        return decl
    }

    private fun tryParseIdent(): String? {
        return when (val tk = iter.peek().value) {
            is Ident -> {
                iter.next()
                tk.v
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
        is FloatT -> parseFloat()
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
                exp
            }
        }
        is UpperIdent -> {
            val uident = expect<UpperIdent>(noErr())
            if (iter.peek().value is Dot) {
                parseImportedVar(uident)
            } else {
                Expr.Var(uident.value.v)
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
        return Expr.IntE(num.value.i).withSpanAndComment(num)
    }

    private fun parseFloat(): Expr {
        val num = expect<FloatT>(withError(E.literalExpected("float")))
        return Expr.FloatE(num.value.f).withSpanAndComment(num)
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

    private fun parseImportedVar(alias: Spanned<UpperIdent>): Expr {
        val moduleName = findImport(alias.value.v)
        return if (moduleName == null) {
            throwError(withError(E.IMPORT_NOT_FOUND)(alias))
        } else {
            expect<Dot>(noErr())
            val ident = expect<Ident>(withError(E.IMPORTED_DOT))
            Expr.Var(ident.value.v, moduleName)
                .withSpan(alias.span, ident.span)
                .withComment(alias.comment)
        }
    }

    private fun parseLambda(multiVar: Boolean = false, isLet: Boolean = false): Expr {
        val begin = iter.peek()
        if (!multiVar) expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val bind = expect<Ident>(withError(E.LAMBDA_VAR))

        return if (iter.peek().value is Ident) {
            val l = parseLambda(true, isLet)
            Expr.Lambda(bind.value.v, l, true)
                .withSpan(begin.span, l.span)
                .withComment(begin.comment)
        } else {
            if (isLet) expect<Equals>(withError(E.LET_EQUALS))
            else expect<Arrow>(withError(E.LAMBDA_ARROW))

            val exp = parseExpression()
            Expr.Lambda(bind.value.v, exp, nested = isLet)
                .withSpan(begin.span, exp.span)
                .withComment(begin.comment)
        }
    }

    private fun parseIf(): Expr {
        val _if = expect<IfT>(noErr())

        val (cond, thens) = withIgnoreOffside {
            val cond = parseExpression()

            expect<Then>(withError(E.THEN))

            val thens = parseExpression()

            expect<Else>(withError(E.ELSE))
            cond to thens
        }

        val elses = parseExpression()

        return Expr.If(cond, thens, elses)
            .withSpan(_if.span, elses.span)
            .withComment(_if.comment)
    }

    private fun parseLet(): Expr {
        // unroll a `let x = 1 and y = x in y` to `let x = 1 in let y = x in y`
        fun unrollLets(acc: List<LetDef>, exp: Expr, span: Span, c: Comment?): Expr {
            return if (acc.size <= 1) {
                Expr.Let(acc[0], exp).withSpan(span).withComment(c)
            } else {
                Expr.Let(acc[0], unrollLets(acc.drop(1), exp, span, c))
                    .withSpan(span)
                    .withComment(c)
            }
        }

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
        return unrollLets(defs, exp, span, let.comment)
    }

    private tailrec fun parseLetDef(types: MutableList<Decl.TypeDecl>, letDefs: MutableList<LetDef>): LetDef {
        val ident = expect<Ident>(withError(E.LET_DECL))

        return when (iter.peek().value) {
            is DoubleColon -> {
                if (letDefs.any { it.name == ident.value.v }) {
                    throwError(withError(E.LET_TYPE)(ident))
                }
                val tdecl = withOffside {
                    iter.next()
                    Decl.TypeDecl(ident.value.v, parsePolytype())
                }
                types += tdecl
                parseLetDef(types, letDefs)
            }
            is Ident -> {
                val exp = withOffside { parseLambda(multiVar = true, isLet = true) }
                val def = LetDef(ident.value.v, exp, types.find { it.name == ident.value.v }?.typ)
                letDefs += def
                def
            }
            else -> {
                withOffside {
                    expect<Equals>(withError(E.LET_EQUALS))
                    val exp = parseExpression()
                    val def = LetDef(ident.value.v, exp, types.find { it.name == ident.value.v }?.typ)
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
                Pattern.Wildcard
            }
            is Ident -> {
                iter.next()
                Pattern.Var(tk.value.v)
            }
            is BoolT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.BoolLiteral(tk.value.b))
            }
            is IntT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.IntLiteral(tk.value.i))
            }
            is FloatT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.FloatLiteral(tk.value.f))
            }
            is CharT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.CharLiteral(tk.value.c))
            }
            is StringT -> {
                iter.next()
                Pattern.LiteralP(LiteralPattern.StringLiteral(tk.value.s))
            }
            is LParen -> {
                iter.next()
                val pat = parsePattern()
                expect<RParen>(withError(E.rparensExpected("pattern declaration")))
                pat
            }
            is UpperIdent -> {
                iter.next()
                val fields = tryParseListOf { tryParsePattern() }
                Pattern.Ctor(tk.value.v, fields)
            }
            else -> null
        }
    }

    private fun parseDataConstructor(typeVars: List<String>): DataConstructor {
        val ctor = expect<UpperIdent>(withError(E.CTOR_NAME))

        val pars = tryParseListOf { parseTypeAtom(true) }

        val freeVars = pars.flatMap { it.findFreeVars(typeVars) }
        if (freeVars.isNotEmpty()) {
            val tk = ctor.copy(span = span(ctor.span, iter.current().span))
            throwError(withError(E.undefinedVar(ctor.value.v, freeVars))(tk))
        }
        return DataConstructor(ctor.value.v, pars)
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

    private fun parseUpperOrLoweIdent(err: (Spanned<Token>) -> String): String {
        val sp = iter.peek()
        return when (sp.value) {
            is UpperIdent -> expect<UpperIdent>(noErr()).value.v
            is Ident -> expect<Ident>(noErr()).value.v
            else -> throwError(err(sp))
        }
    }

    private fun parsePolytype(inConstructor: Boolean = false): TType {
        // unroll a `forall a b. a -> b` to `forall a. (forall b. a -> b)`
        fun unrollForall(acc: List<String>, type: TType): TType.TForall {
            return if (acc.size <= 1) {
                TType.TForall(acc[0], type)
            } else {
                TType.TForall(acc[0], unrollForall(acc.drop(1), type))
            }
        }

        return if (iter.peek().value is Forall) {
            iter.next()
            val tyVars = parseListOf(::parseTypeVar) { it is Ident }
            if (tyVars.isEmpty()) throwError(withError(E.FORALL_TVARS)(iter.peek()))
            expect<Dot>(withError(E.FORALL_DOT))
            unrollForall(tyVars, parseType(inConstructor))
        } else parseType()
    }

    private fun parseType(inConstructor: Boolean = false): TType {
        return parseTypeAtom(inConstructor) ?: throwError(withError(E.TYPE_DEF)(iter.peek()))
    }

    private fun parseTypeAtom(inConstructor: Boolean = false): TType? {
        if (iter.peekIsOffside()) return null
        val tk = iter.peek()

        val ty = when (tk.value) {
            is Forall -> parsePolytype(inConstructor)
            is LParen -> {
                iter.next()
                withIgnoreOffside {
                    val typ = parseType(false)
                    expect<RParen>(withError(E.rparensExpected("type definition")))
                    typ
                }
            }
            is Ident -> TType.TVar(parseTypeVar())
            is UpperIdent -> {
                val ty = parseUpperIdent().v
                if (inConstructor) {
                    TType.TVar(ty)
                } else {
                    val pars = tryParseListOf(true) { parseTypeAtom(true) }

                    if (pars.isEmpty()) TType.TVar(ty)
                    else TType.TConstructor(ty, pars)
                }
            }
            else -> null
        } ?: return null

        return when (iter.peek().value) {
            is Arrow -> {
                iter.next()
                TType.TFun(ty, parseType())
            }
            else -> ty
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
    private inline fun <reified T> expect(err: (Spanned<Token>) -> String): Spanned<T> {
        val tk = iter.next()

        return when (tk.value) {
            is T -> tk as Spanned<T>
            else -> throw ParserError(err(tk))
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

    /**
     * Finds the full module name of an import alias
     */
    private fun findImport(alias: String): ModuleName? {
        return imports.find { it.alias() == alias }?.module
    }

    companion object {
        /**
         * Creates an error handler with the supplied message.
         */
        private fun withError(msg: String): (Spanned<Token>) -> String = { token ->
            "$msg\n\nGot: ${token.value} at ${token.span}"
        }

        private fun throwError(err: String): Nothing {
            throw ParserError(err)
        }

        private fun throwMismatchedIndentation(tk: Spanned<Token>): Nothing {
            throwError(withError(E.MISMATCHED_INDENTATION)(tk))
        }

        /**
         * Represents an error that cannot happen
         */
        private fun noErr() = { tk: Spanned<Token> -> "Cannot happen, token: $tk" }

        private fun span(s: Span, e: Span) = Span(s.start, e.end)

        private val statementEnding = listOf(RParen, RSBracket, RBracket, EOF)
    }
}

class ParserError(msg: String) : java.lang.RuntimeException(msg)
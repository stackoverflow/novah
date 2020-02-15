package novah.frontend

import novah.*
import novah.frontend.Token.*
import novah.frontend.Errors as E

class Parser(tokens: Iterator<Spanned<Token>>) {
    private val iter = PeekableIterator(tokens)

    fun parseFullModule(): Module {
        val (mname, exports) = parseModule()

        val imports = mutableListOf<Import>()
        while (iter.peek().value is ImportT) {
            imports += parseImport()
        }

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            decls += parseDecl()
        }

        return Module(mname, imports, ModuleExports.consolidate(exports, decls), decls)
    }

    private fun parseModule(): Pair<ModuleName, ModuleExports> {
        expect<ModuleT>(withError(E.MODULE_DEFINITION))

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
        return name to exports
    }

    private fun parseImportExports(ctx: String): List<String> {
        expect<LParen>(withError(E.lparensExpected(ctx)))
        if (iter.peek().value is RParen) {
            iter.next()
            return listOf()
        }

        val exps = between<Comma, String> { parseUpperOrLoweIdent(withError(E.EXPORT_REFER)) }

        expect<RParen>(withError(E.rparensExpected(ctx)))
        return exps
    }

    private fun parseModuleName(): ModuleName {
        return between<Dot, String> { expect<UpperIdent>(withError(E.MODULE_NAME)).value.v }
    }

    private fun parseImport(): Import {
        expect<ImportT>(noErr())
        val mname = parseModuleName()

        val next = iter.peek()
        return when (next.value) {
            is LParen -> {
                Import.Exposing(mname, parseImportExports("import"))
            }
            is As -> {
                iter.next()
                val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                Import.As(mname, alias.value.v)
            }
            else -> Import.Raw(mname)
        }
    }

    private fun parseDecl(): Decl {
        val tk = iter.peek()
        val comment = tk.value.comment
        val decl = when (tk.value) {
            is Type -> parseTypeDecl()
            is Val -> parseVal()
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseTypeDecl(): Decl {
        expect<Type>(noErr())

        if(iter.peek().value is Ident) {
            return parseValTypeDecl()
        }
        val name = expect<UpperIdent>(withError(E.DATA_NAME))

        val tyVars = parseListOf(::parseTypeVar) { it is Ident }

        expect<Equals>(withError(E.DATA_EQUALS))

        val ctors = mutableListOf<DataConstructor>()
        while (true) {
            ctors += parseDataConstructor()
            if (iter.peek().value is Pipe) {
                iter.next()
            } else break
        }

        return Decl.DataDecl(name.value.v, tyVars, ctors)
    }

    private fun parseValTypeDecl(): Decl.TypeDecl {
        val name = expect<Ident>(noErr())
        expect<DoubleColon>(withError(E.TYPE_DCOLON))
        return Decl.TypeDecl(name.value.v, parsePolytype())
    }

    private fun parseVal(): Decl.ValDecl {
        // transforms `fun x y z = 1` into `fun = \x -> \y -> \z -> 1`
        fun desugarToLambda(acc: List<String>, stmt: Statement): Expression.Lambda {
            return if (acc.size <= 1) {
                Expression.Lambda(acc[0], stmt)
            } else {
                Expression.Lambda(acc[0], Statement.Exp(desugarToLambda(acc.drop(1), stmt)))
            }
        }

        expect<Val>(noErr())

        val name = expect<Ident>(withError(E.LET_DECL)).value.v
        val vars = tryParseListOf(::tryParseIdent)

        expect<Equals>(withError(E.EQUALS))

        val stmt = parseStatement()

        return if(vars.isEmpty()) {
            Decl.ValDecl(name, stmt)
        } else {
            Decl.ValDecl(name, Statement.Exp(desugarToLambda(vars, stmt)))
        }
    }

    private fun tryParseIdent(): String? {
        return when (val tk = iter.peek().value) {
            is Ident -> {
                iter.next(); tk.v
            }
            else -> null
        }
    }

    private fun parseStatement(): Statement = when(iter.peek().value) {
        is Do -> parseDo()
        else -> Statement.Exp(parseExpression())
    }

    private fun parseDo(): Statement.Do {
        expect<Do>(noErr())
        expect<LBracket>(withError(E.lbracketExpected("do expression")))

        val exps = between<Semicolon, Expression>(::parseExpression)

        // last semicolon is optional
        if(iter.peek().value is Semicolon) iter.next()

        expect<RBracket>(withError(E.rbracketExpected("do expression")))

        return Statement.Do(exps)
    }

    private fun parseExpression(): Expression {
        val tk = iter.peek()
        val exps = tryParseListOf(::tryParseAtom)

        val unrolled = Operators.parseApplication(exps)
        if (unrolled == null) {
            throwError(withError(E.MALFORMED_EXPR)(tk))
        } else return unrolled
    }

    private fun tryParseAtom(): Expression? = when (iter.peek().value) {
        is IntT -> parseInt()
        is FloatT -> parseFloat()
        is StringT -> parseString()
        is CharT -> parseChar()
        is BoolT -> parseBool()
        is Ident -> parseVar()
        is Op -> parseOperator()
        is LParen -> {
            iter.next()
            val exp = parseExpression()
            expect<RParen>(withError(E.rparensExpected("expression")))
            exp
        }
        is UpperIdent -> parseDataConstruction()
        is Backslash -> parseLambda()
        is IfT -> parseIf()
        is LetT -> parseLet()
        is CaseT -> parseMatch()
        else -> null
    }

    private fun parseInt(): Expression.IntE {
        val num = expect<IntT>(withError(E.literalExpected("integer")))
        return Expression.IntE(num.value.i)
    }

    private fun parseFloat(): Expression.FloatE {
        val num = expect<FloatT>(withError(E.literalExpected("float")))
        return Expression.FloatE(num.value.f)
    }

    private fun parseString(): Expression.StringE {
        val str = expect<StringT>(withError(E.literalExpected("string")))
        return Expression.StringE(str.value.s)
    }

    private fun parseChar(): Expression.CharE {
        val str = expect<CharT>(withError(E.literalExpected("char")))
        return Expression.CharE(str.value.c)
    }

    private fun parseBool(): Expression.Bool {
        val bool = expect<BoolT>(withError(E.literalExpected("boolean")))
        return Expression.Bool(bool.value.b)
    }

    private fun parseVar(): Expression.Var {
        val v = expect<Ident>(withError(E.VARIABLE))
        return Expression.Var(v.value.v)
    }

    private fun parseOperator(): Expression.Operator {
        val op = expect<Op>(withError("Expected Operator."))
        return Expression.Operator(op.value.op)
    }

    private fun parseDataConstruction(): Expression.Construction {
        val ctor = expect<UpperIdent>(withError("")).value.v

        val fields = tryParseListOf(::tryParseAtom)
        return Expression.Construction(ctor, fields)
    }

    private fun parseLambda(multiVar: Boolean = false, isLet: Boolean = false): Expression.Lambda {
        if (!multiVar) expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val bind = expect<Ident>(withError(E.LAMBDA_VAR))

        return if (iter.peek().value is Ident) {
            Expression.Lambda(bind.value.v, Statement.Exp(parseLambda(true, isLet)))
        } else {
            if (isLet) expect<Equals>(withError(E.LET_EQUALS))
            else expect<Arrow>(withError(E.LAMBDA_ARROW))

            val stmt = parseStatement()
            Expression.Lambda(bind.value.v, stmt)
        }
    }

    private fun parseIf(): Expression.If {
        expect<IfT>(noErr())

        val cond = parseExpression()

        expect<Then>(withError(E.THEN))

        val thens = parseStatement()

        expect<Else>(withError(E.ELSE))

        val elses = parseStatement()

        return Expression.If(cond, thens, elses)
    }

    private fun parseLet(): Expression.Let {
        expect<LetT>(noErr())

        val defs = between<And, Def>(::parseLetDef)

        expect<In>(withError(E.LET_IN))

        val stmt = parseStatement()

        return Expression.Let(defs, stmt)
    }

    private fun parseLetDef(): Def {
        val ident = expect<Ident>(withError(E.LET_DECL))

        return if (iter.peek().value is Ident) {
            val exp = parseLambda(multiVar = true, isLet = true)
            Def(ident.value.v, exp, null)
        } else {
            expect<Equals>(withError(E.LET_EQUALS))
            val exp = parseExpression()
            Def(ident.value.v, exp, null)
        }
    }

    private fun parseMatch(): Expression.Match {
        expect<CaseT>(noErr())

        val exp = parseExpression()

        expect<Of>(withError(E.CASE_OF))

        expect<LSBracket>(withError(E.lsbracketExpected("case expression")))
        val cases = between<Pipe, Case>(::parseCase)
        expect<RSBracket>(withError(E.rsbracketExpected("case expression")))

        return Expression.Match(exp, cases)
    }

    private fun parseCase(): Case {
        val pat = parsePattern()

        expect<Arrow>(withError(E.CASE_ARROW))

        val stmt = parseStatement()
        return Case(pat, stmt)
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
                val fields = tryParseListOf(::tryParsePattern)
                Pattern.Ctor(tk.value.v, fields)
            }
            else -> null
        }
    }

    private fun parseDataConstructor(): DataConstructor {
        val ctor = expect<UpperIdent>(withError(E.CTOR_NAME))

        val pars = tryParseListOf(::tryParseType)
        return DataConstructor(ctor.value.v, pars)
    }

    private fun parseUpperOrLoweIdent(err: (Spanned<Token>) -> String): String {
        val sp = iter.peek()
        return when (sp.value) {
            is UpperIdent -> expect<UpperIdent>(noErr()).value.v
            is Ident -> expect<Ident>(noErr()).value.v
            else -> throwError(err(sp))
        }
    }

    private fun parsePolytype(): Polytype {
        var tyVars = listOf<TypeVar>()

        if (iter.peek().value is Forall) {
            iter.next()
            tyVars = parseListOf(::parseTypeVar) { it is Ident }
            expect<Dot>(withError(E.FORALL_DOT))
        }

        return Polytype(tyVars, parseType())
    }

    private fun parseType(): Monotype {
        return tryParseType() ?: throwError(withError(E.TYPE_DEF)(iter.peek()))
    }

    private fun tryParseType(): Monotype? {
        val atm = parseTypeAtom() ?: return null
        return when (iter.peek().value) {
            is Arrow -> {
                iter.next()
                Monotype.Function(atm, parseType())
            }
            else -> atm
        }
    }

    private fun parseTypeAtom(): Monotype? {
        val tk = iter.peek()

        return when (tk.value) {
            is LParen -> {
                iter.next()
                val typ = parseType()
                expect<RParen>(withError(E.rparensExpected("type definition")))
                typ
            }
            is Ident -> Monotype.Var(parseTypeVar())
            is UpperIdent -> {
                val ctor = parseUpperIdent()

                val ctorArgs = tryParseListOf(::tryParseType)
                Monotype.Constructor(ctor.v, ctorArgs)
            }
            else -> null
        }
    }

    private fun parseTypeVar(): TypeVar {
        return TypeVar(expect<Ident>(withError(E.TYPE_VAR)).value.v)
    }

    private fun parseUpperIdent(): UpperIdent {
        return expect<UpperIdent>(noErr()).value
    }

    private fun <T> parseListOf(parser: () -> T, keep: (Token) -> Boolean): List<T> {
        val res = mutableListOf<T>()
        while (keep(iter.peek().value)) {
            res += parser()
        }
        return res
    }

    private inline fun <reified T> tryParseListOf(fn: () -> T?): List<T> {
        val acc = mutableListOf<T>()
        var element = fn()
        while (element != null) {
            acc += element
            element = fn()
        }
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
            else -> throw RuntimeException(err(tk))
        }
    }

    companion object {

        /**
         * Creates an error handler with the supplied message.
         */
        private fun withError(msg: String): (Spanned<Token>) -> String = { token ->
            "$msg\n\nGot: ${token.value} at ${token.span}"
        }

        private fun throwError(err: String): Nothing {
            throw RuntimeException(err)
        }

        /**
         * Represents an error that cannot happen
         */
        private fun noErr() = { _: Spanned<Token> -> "Cannot happen" }
    }
}
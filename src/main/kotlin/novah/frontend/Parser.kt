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
            expect<Newline>(withError(E.newlineExpected("after import declaration")))
        }

        val decls = mutableListOf<Decl>()
        while (iter.hasNext()) {
            decls += parseDecl()
            expect<Newline>(withError(E.newlineExpected("after declaration")))
        }
        return Module(mname, imports, ModuleExports.consolidate(exports, decls), decls)
    }

    private fun parseModule(): Pair<ModuleName, ModuleExports> {
        expect<ModuleT>(withError(E.MODULE_DEFINITION))
        val mname = parseModuleName()

        val exps = withIndentation {
            val tk = iter.peek()
            when (tk.value) {
                is Hiding -> {
                    iter.next()
                    withIndentation {
                        ModuleExports.Hiding(parseImportExports("module", "exports"))
                    }
                }
                is Exposing -> {
                    iter.next()
                    withIndentation {
                        ModuleExports.Exposing(parseImportExports("module", "exports"))
                    }
                }
                is Newline -> ModuleExports.ExportAll
                else -> throwError(withError(E.MODULE_END)(tk))
            }
        }

        expect<Newline>(withError(E.newlineExpected("module definition")))
        return mname to exps
    }

    private fun parseImportExports(ctx: String, listObj: String): List<String> {
        val exps = mutableListOf<String>()

        expect<LParen>(withError(E.lparensExpected("$ctx exposing/hiding")))

        while (iter.peek().value !is RParen) {
            exps += parseUpperOrLoweIdent(withError(E.EXPORT_REFER))

            if (iter.peek().value is Newline) iter.next()
            if (iter.peek().value !is Comma) break

            expect<Comma>(withError(E.commaExpected(listObj)))
        }

        expect<RParen>(withError(E.rparensExpected("$ctx exposing/hiding")))
        return exps
    }

    private fun parseModuleName(): ModuleName {
        val firstName = expect<UpperIdent>(withError(E.MODULE_NAME))
        val mname = mutableListOf(firstName.value.v)
        while (iter.peek().value is Dot) {
            iter.next()
            mname.add(expect<UpperIdent>(withError(E.MODULE_NAME)).value.v)
        }
        return mname
    }

    private fun parseImport(): Import {
        expect<ImportT>(noErr())
        val mname = parseModuleName()

        return withIndentation {
            val next = iter.peek()
            when (next.value) {
                is Exposing -> {
                    iter.next()
                    withIndentation {
                        Import.Exposing(mname, parseImportExports("import", "definitions"))
                    }
                }
                is Hiding -> {
                    iter.next()
                    withIndentation {
                        Import.Hiding(mname, parseImportExports("import", "definitions"))
                    }
                }
                is As -> {
                    iter.next()
                    val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                    Import.As(mname, alias.value.v)
                }
                is Newline -> Import.Raw(mname)
                else -> throwError(withError(E.IMPORT_INVALID)(next))
            }
        }
    }

    private fun parseDecl(): Decl {
        val tk = iter.next()
        return when (tk.value) {
            is Data -> parseDataDecl()
            is Ident -> {
                when (iter.peek().value) {
                    is DoubleColon, is Indent -> parseVarType(tk.value.v)
                    else -> parseVarDecl(tk.value.v)
                }
            }
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
    }

    private fun parseDataDecl(): Decl.DataDecl {
        val name = expect<UpperIdent>(withError(E.DATA_NAME))

        val tyVars = parseListOf(::parseTypeVar) { it is Ident }

        val ctors = withIndentation { ind1 ->
            expect<Equals>(withError(E.DATA_EQUALS))

            withIndentation { ind2 ->
                val indented = ind1 || ind2

                parseListOf(::parseDataConstructor) {
                    if (it is Pipe) iter.next()
                    if (indented && it is Newline) {
                        iter.next()
                        expect<Pipe>(withError(E.PIPE_SEPARATOR))
                    }

                    if (indented) it !is Dedent
                    else it !is Newline
                }
            }
        }

        return Decl.DataDecl(name.value.v, tyVars, ctors)
    }

    private fun parseVarType(name: String): Decl.VarType {
        return withIndentation { indented ->
            expect<DoubleColon>(withError(E.TYPE_DECL))
            Decl.VarType(name, parsePolytype(indented))
        }
    }

    private fun parseVarDecl(name: String): Decl.VarDecl {
        // transforms `fun x y z = 1` into `fun = \x -> \y -> \z -> 1`
        fun unsugarToLambda(acc: List<String>, exps: List<Expression>): Expression.Lambda {
            return if (acc.size <= 1) {
                Expression.Lambda(acc[0], exps)
            } else {
                Expression.Lambda(acc[0], listOf(unsugarToLambda(acc.drop(1), exps)))
            }
        }

        val tk = iter.peek()
        val vars = tryParseListOf(::tryParseIdent)

        return withIndentation {
            expect<Equals>(withError(E.EQUALS))
            withIndentation { indented ->
                val exps = if (indented) {
                    between<Newline, Expression> { parseExpression() }
                } else {
                    listOf(parseExpression())
                }

                val def = if (vars.isEmpty()) {
                    if (exps.size > 1) throwError(withError(E.INVALID_DECL)(tk))
                    Def(name, exps[0], null)
                } else {
                    Def(name, unsugarToLambda(vars, exps), null)
                }

                Decl.VarDecl(Expression.Let(listOf(def)))
            }
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

    private fun parseExpression(parens: Boolean = false): Expression {
        val tk = iter.peek()
        val exps = tryParseListOf { tryParseAtom(parens) }

        val unrolled = Operators.parseApplication(exps)
        if (unrolled == null) {
            throwError(withError(E.MALFORMED_EXPR)(tk))
        } else return unrolled
    }

    private fun tryParseAtom(parens: Boolean = false): Expression? = when (iter.peek().value) {
        is IntT -> parseInt()
        is FloatT -> parseFloat()
        is StringT -> parseString()
        is CharT -> parseChar()
        is BoolT -> parseBool()
        is Ident -> parseVar()
        is Op -> parseOperator()
        is LParen -> {
            iter.next()
            val exp = parseExpression(true)
            expect<RParen>(withError(E.rparensExpected("expression")))
            if (iter.peek().value is Dedent) iter.next()
            exp
        }
        is UpperIdent -> parseDataConstruction(parens)
        is Backslash -> parseLambda(parens)
        is IfT -> parseIf(parens)
        is LetT -> parseLet()
        is CaseT -> parseMatch(parens)
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

    private fun parseDataConstruction(parens: Boolean): Expression.Construction {
        val ctor = expect<UpperIdent>(withError("")).value.v

        val fields = tryParseListOf { tryParseAtom(parens) }
        return Expression.Construction(ctor, fields)
    }

    private fun parseLambda(parens: Boolean, multiVar: Boolean = false): Expression.Lambda {
        if (!multiVar) expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val bind = expect<Ident>(withError(E.LAMBDA_VAR))

        return if (iter.peek().value is Ident) {
            Expression.Lambda(bind.value.v, listOf(parseLambda(parens, true)))
        } else {
            expect<Arrow>(withError(E.LAMBDA_ARROW))
            withIndentation(parens = parens) { indented ->
                val exps = if (indented) {
                    between<Newline, Expression> { parseExpression(parens) }
                } else listOf(parseExpression())
                Expression.Lambda(bind.value.v, exps)
            }
        }
    }

    private fun parseIf(parens: Boolean): Expression.If {
        var (indented, newlined) = false to false
        expect<IfT>(noErr())

        val cond = withIndentation {
            parseExpression()
        }

        when (iter.peek().value) {
            is Newline -> {
                iter.next(); newlined = true
            }
            is Indent -> {
                iter.next(); indented = true
            }
        }

        expect<Then>(withError(E.THEN))

        fun thenElse(parens: Boolean): List<Expression> = withIndentation { isIndented ->
            if (isIndented) {
                between<Newline, Expression> { parseExpression(parens) }
            } else {
                listOf(parseExpression(parens))
            }
        }

        val thenE = thenElse(false)

        if (indented || newlined) expect<Newline>(withError(E.newlineExpected("then clause")))

        expect<Else>(withError(E.ELSE))

        val elseE = thenElse(parens)

        if (parens) {
            if (iter.peek().value is Dedent) iter.next()
        } else if (indented) {
            expect<Dedent>(withError(E.dedentExpected("after if expression")))
        }

        return Expression.If(cond, thenE, elseE)
    }

    private fun parseLet(): Expression.Let {
        expect<LetT>(noErr())

        val defs = mutableListOf<Def>()
        defs += parseLetDef()

        if (iter.peek().value is Indent) {
            iter.next()
            defs += parseLetDef()
            while (iter.peek().value is Newline) {
                iter.next()
                defs += parseLetDef()
            }
            expect<Dedent>(withError(E.dedentExpected("let definition")))
        }
        return Expression.Let(defs)
    }

    private fun parseLetDef(): Def {
        val ident = expect<Ident>(withError(E.LET_DECL))

        expect<Equals>(withError(E.LET_EQUALS))

        val exp = parseExpression()

        var type: Polytype? = null
        if (iter.peek().value is DoubleColon) {
            iter.next()
            type = parsePolytype(false)
        }
        return Def(ident.value.v, exp, type)
    }

    private fun parseMatch(parens: Boolean): Expression.Match {
        expect<CaseT>(noErr())

        val exp = parseExpression()

        expect<Of>(withError(E.CASE_OF))

        val cases = withIndentation(required = true, parens = parens) {
            between<Newline, Case> { parseCase(parens) }
        }
        return Expression.Match(exp, cases)
    }

    private fun parseCase(parens: Boolean): Case {
        val pat = parsePattern()

        val exps = withIndentation {
            expect<Arrow>(withError(E.CASE_ARROW))

            withIndentation { indented ->
                if (indented) {
                    between<Newline, Expression> { parseExpression(parens) }
                } else {
                    listOf(parseExpression(parens))
                }
            }
        }
        return Case(pat, exps)
    }

    private fun parsePattern(): Pattern {
        return tryParsePattern() ?: throwError(withError(E.PATTERN)(iter.peek()))
    }

    private fun tryParsePattern(): Pattern? {
        val tk = iter.peek()
        return when (tk.value) {
            is Underline -> {
                iter.next(); Pattern.Wildcard
            }
            is Ident -> {
                iter.next(); Pattern.Var(tk.value.v)
            }
            is BoolT -> {
                iter.next(); Pattern.LiteralP(LiteralPattern.BoolLiteral(tk.value.b))
            }
            is IntT -> {
                iter.next(); Pattern.LiteralP(LiteralPattern.IntLiteral(tk.value.i))
            }
            is FloatT -> {
                iter.next(); Pattern.LiteralP(LiteralPattern.FloatLiteral(tk.value.f))
            }
            is CharT -> {
                iter.next(); Pattern.LiteralP(LiteralPattern.CharLiteral(tk.value.c))
            }
            is StringT -> {
                iter.next(); Pattern.LiteralP(LiteralPattern.StringLiteral(tk.value.s))
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

        val pars = parseListOf(::parseType) { it !is Pipe && noNewLine(it) }
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

    private fun parsePolytype(indented: Boolean): Polytype {
        var tyVars = listOf<TypeVar>()

        // Ignore newlines for this parser
        if (indented) iter.ignore { it.value is Newline }

        if (iter.peek().value is Forall) {
            iter.next()
            tyVars = parseListOf(::parseTypeVar) {
                it is Ident
            }
            expect<Dot>(withError(E.FORALL_DOT))
        }

        val poly = Polytype(tyVars, parseType())
        if (indented) iter.stopIgnoring()
        return poly
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

    private fun noNewLine(tk: Token): Boolean = tk !is Newline && tk !is Indent && tk !is Dedent

    /**
     * Runs function `fn` with a possible increase of indentation.
     * @param required if true forces an indentation
     * @param parens if true this expressions in inside parens and doesn't necessarily need an dedent
     */
    private fun <T> withIndentation(required: Boolean = false, parens: Boolean = false, fn: (Boolean) -> T): T {
        var indented = false
        if (required || iter.peek().value is Indent) {
            expect<Indent>(withError(E.indentExpected("expression")))
            indented = true
        }

        val res = fn(indented)

        if (parens) {
            if (iter.peek().value is Dedent) iter.next()
        } else if (indented) {
            expect<Dedent>(withError(E.GENERIC_DEDENT))
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
            "$msg\n\n\tGot: ${token.value} at ${token.span}"
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
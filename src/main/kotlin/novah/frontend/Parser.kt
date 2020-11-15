package novah.frontend

import novah.frontend.Token.*
import novah.frontend.Errors as E

class Parser(tokens: Iterator<Spanned<Token>>) {
    private val iter = PeekableIterator(tokens)

    private var imports = listOf<Import>()

    private val topLevelTypes = mutableMapOf<String, Polytype>()

    fun parseFullModule(): Module {
        val (mname, exports) = parseModule()

        val imports = mutableListOf<Import>()
        while (iter.peek().value is ImportT) {
            imports += parseImport()
        }
        this.imports = imports

        val decls = mutableListOf<Decl>()
        while (iter.peek().value !is EOF) {
            decls += parseDecl()
        }

        return Module(
            mname,
            imports,
            ModuleExports.consolidate(exports, decls),
            decls
        )
    }

    private fun parseModule(): Pair<ModuleName, ModuleExports> {
        expect<ModuleT>(withError(E.MODULE_DEFINITION))

        val name = parseModuleName()

        var consumedEol = consumeEol()

        val exports = when (iter.peek().value) {
            is Hiding -> {
                iter.next()
                consumeEol()
                consumedEol = false
                ModuleExports.Hiding(parseImportExports("exports"))
            }
            is Exposing -> {
                iter.next()
                consumeEol()
                consumedEol = false
                ModuleExports.Exposing(parseImportExports("exports"))
            }
            else -> ModuleExports.ExportAll
        }
        if (!consumedEol)
            expectEolOrSemicolon()
        return name to exports
    }

    private fun parseImportExports(ctx: String): List<String> {
        expect<LParen>(withError(E.lparensExpected(ctx)))
        consumeEol()
        if (iter.peek().value is RParen) {
            iter.next()
            return listOf()
        }

        val exps = between<Comma, String>(true) { parseUpperOrLoweIdent(withError(E.EXPORT_REFER)) }

        expect<RParen>(withError(E.rparensExpected(ctx)))
        return exps
    }

    private fun parseModuleName(): ModuleName {
        return between<Dot, String> { expect<UpperIdent>(withError(E.MODULE_NAME)).value.v }
    }

    private fun parseImport(): Import {
        expect<ImportT>(noErr())
        val mname = parseModuleName()

        var consumedEol = consumeEol()
        val import = when (iter.peek().value) {
            is LParen -> {
                consumedEol = false
                Import.Exposing(mname, parseImportExports("import"))
            }
            is As -> {
                iter.next()
                val alias = expect<UpperIdent>(withError(E.IMPORT_ALIAS))
                consumedEol = false
                Import.As(mname, alias.value.v)
            }
            else -> Import.Raw(mname)
        }

        if (!consumedEol)
            expectEolOrSemicolon()
        return import
    }

    private fun parseDecl(): Decl {
        val tk = iter.peek()
        val comment = tk.comment
        val decl = when (tk.value) {
            is Type -> parseTypeDecl()
            is Ident -> parseVarDecl()
            else -> throwError(withError(E.TOPLEVEL_IDENT)(tk))
        }
        decl.comment = comment
        return decl
    }

    private fun parseTypeDecl(): Decl.DataDecl {
        expect<Type>(noErr())

        val name = expect<UpperIdent>(withError(E.DATA_NAME))

        val tyVars = parseListOf(::parseTypeVar) { it is Ident }

        consumeEol()
        expect<Equals>(withError(E.DATA_EQUALS))
        consumeEol()

        var consumedEol = false
        val ctors = mutableListOf<DataConstructor>()
        loop@ while (true) {
            ctors += parseDataConstructor()

            when (iter.peek().value) {
                is EOL -> {
                    iter.next()
                    if (iter.peek().value is Pipe) {
                        iter.next()
                    } else {
                        consumedEol = true
                        break@loop
                    }
                }
                is Pipe -> iter.next()
                else -> break@loop
            }
        }

        if (!consumedEol) {
            expect<Semicolon>(withError(E.semicolonExpected("type declaration")))
        }

        return Decl.DataDecl(name.value.v, tyVars, ctors)
    }

    private fun parseVarDecl(): Decl {
        // transforms `fun x y z = 1` into `fun = \x -> \y -> \z -> 1`
        fun desugarToLambda(acc: List<String>, exp: Expression): Expression.Lambda {
            return if (acc.size <= 1) {
                Expression.Lambda(acc[0], exp)
            } else {
                Expression.Lambda(acc[0], desugarToLambda(acc.drop(1), exp))
            }
        }

        val name = expect<Ident>(noErr()).value.v
        if (iter.peek().value is DoubleColon) {
            return parseTypeSignature(name)
        }

        val vars = tryParseListOf(::tryParseIdent)

        expect<Equals>(withError(E.EQUALS))
        consumeEol()

        val exp = parseExpression().let {
            if (vars.isEmpty()) it else desugarToLambda(vars, it)
        }

        expectEolOrSemicolon()

        // if the declaration has a defined type, annotate it
        return topLevelTypes[name]?.let {
            Decl.ValDecl(name, Expression.Ann(exp, it))
        } ?: Decl.ValDecl(name, exp)
    }

    private fun parseTypeSignature(name: String): Decl.TypeDecl {
        expect<DoubleColon>(withError(E.TYPE_DCOLON))
        consumeEol()
        val decl = Decl.TypeDecl(name, parsePolytype())
        expectEolOrSemicolon()
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

    private fun parseExpression(): Expression {
        val tk = iter.peek()
        val exps = tryParseListOf(::tryParseAtom)

        val unrolled = Operators.parseApplication(exps) ?: throwError(withError(E.MALFORMED_EXPR)(tk))

        // type signatures have the lowest precendece
        return if (iter.peek().value is DoubleColon) {
            iter.next()
            val pt = parsePolytype()
            Expression.Ann(unrolled, pt)
        } else unrolled
    }

    private fun tryParseAtom(): Expression? = when (iter.peek().value) {
        is IntT -> parseInt()
        is FloatT -> parseFloat()
        is StringT -> parseString()
        is CharT -> parseChar()
        is BoolT -> parseBool()
        is Ident -> parseVar()
        is Op -> {
            val op = parseOperator()
            // EOL are allowed after operators
            consumeEol()
            op
        }
        is LParen -> {
            iter.next()
            val exp = parseExpression()
            expect<RParen>(withError(E.rparensExpected("expression")))
            exp
        }
        is UpperIdent -> {
            val uident = expect<UpperIdent>(noErr())
            if (iter.peek().value is Dot) {
                parseImportedVar(uident)
            } else {
                parseDataConstruction(uident.value.v)
            }
        }
        is Backslash -> parseLambda()
        is IfT -> parseIf()
        is LetT -> parseLet()
        is CaseT -> parseMatch()
        is Do -> parseDo()
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

    private fun parseImportedVar(alias: Spanned<UpperIdent>): Expression.Var {
        val moduleName = findImport(alias.value.v)
        return if (moduleName == null) {
            throwError(withError(E.IMPORT_NOT_FOUND)(alias))
        } else {
            expect<Dot>(noErr())
            val ident = expect<Ident>(withError(E.IMPORTED_DOT)).value.v
            Expression.Var(ident, moduleName)
        }
    }

    private fun parseDataConstruction(ctor: String): Expression.Construction {
        val fields = tryParseListOf(::tryParseAtom)
        return Expression.Construction(ctor, fields)
    }

    private fun parseLambda(multiVar: Boolean = false, isLet: Boolean = false): Expression.Lambda {
        if (!multiVar) expect<Backslash>(withError(E.LAMBDA_BACKSLASH))

        val bind = expect<Ident>(withError(E.LAMBDA_VAR))

        return if (iter.peek().value is Ident) {
            Expression.Lambda(bind.value.v, parseLambda(true, isLet))
        } else {
            if (isLet) expect<Equals>(withError(E.LET_EQUALS))
            else expect<Arrow>(withError(E.LAMBDA_ARROW))
            consumeEol()

            val exp = parseExpression()
            Expression.Lambda(bind.value.v, exp)
        }
    }

    private fun parseIf(): Expression.If {
        expect<IfT>(noErr())

        val cond = parseExpression()

        consumeEol()
        expect<Then>(withError(E.THEN))

        consumeEol()
        val thens = parseExpression()

        consumeEol()
        expect<Else>(withError(E.ELSE))

        consumeEol()
        val elses = parseExpression()

        return Expression.If(cond, thens, elses)
    }

    private fun parseLet(): Expression.Let {
        expect<LetT>(noErr())

        val types = mutableListOf<Decl.TypeDecl>()
        val defsCtx = mutableListOf<Def>()

        val defs = between<And, Def>(true) { parseLetDef(types, defsCtx) }

        consumeEol()
        expect<In>(withError(E.LET_IN))

        consumeEol()
        val exp = parseExpression()

        return Expression.Let(defs, exp)
    }

    private tailrec fun parseLetDef(types: MutableList<Decl.TypeDecl>, defs: MutableList<Def>): Def {
        val ident = expect<Ident>(withError(E.LET_DECL))

        return when (iter.peek().value) {
            is DoubleColon -> {
                if (defs.any { it.name == ident.value.v }) {
                    throwError(withError(E.LET_TYPE)(ident))
                }
                iter.next()
                val tdecl = Decl.TypeDecl(ident.value.v, parsePolytype())
                types += tdecl
                consumeEol()
                expect<And>(withError(E.LET_AND))
                parseLetDef(types, defs)
            }
            is Ident -> {
                val exp = parseLambda(multiVar = true, isLet = true)
                val def = Def(ident.value.v, exp, types.find { it.name == ident.value.v }?.typ)
                defs += def
                def
            }
            else -> {
                expect<Equals>(withError(E.LET_EQUALS))
                val exp = parseExpression()
                val def = Def(ident.value.v, exp, types.find { it.name == ident.value.v }?.typ)
                defs += def
                def
            }
        }
    }

    private fun parseMatch(): Expression.Match {
        expect<CaseT>(noErr())

        consumeEol()
        val exp = parseExpression()

        consumeEol()
        expect<Of>(withError(E.CASE_OF))

        consumeEol()

        // A single case doesn't need a bracket
        if (iter.peek().value !is LBracket) {
            return Expression.Match(exp, listOf(parseCase()))
        }
        expect<LBracket>(withError(E.lbracketExpected("after `case expression of`")))
        consumeEol()

        val cases = mutableListOf<Case>()
        while (true) {
            cases.add(parseCase())
            val next = iter.next()
            if (next.value is EOL || next.value is Semicolon) {
                if (iter.peek().value is RBracket) {
                    iter.next()
                    break
                }
            } else if (next.value is RBracket) {
                break
            } else {
                throwError(withError(E.EOL_OR_SEMICOLON)(next))
            }
        }

        return Expression.Match(exp, cases)
    }

    private fun parseCase(): Case {
        val pat = parsePattern()

        expect<Arrow>(withError(E.CASE_ARROW))
        consumeEol()

        return Case(pat, parseExpression())
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

    private fun parseDo(): Expression {
        expect<Do>(noErr())
        consumeEol()
        expect<LBracket>(withError(E.lbracketExpected("do expression")))
        consumeEol()

        val exps = mutableListOf<Expression>()

        while (true) {
            exps.add(parseExpression())
            val next = iter.next()
            if (next.value is EOL || next.value is Semicolon) {
                if (iter.peek().value is RBracket) {
                    iter.next()
                    break
                }
            } else if (next.value is RBracket) {
                break
            } else {
                throwError(withError(E.EOL_OR_SEMICOLON)(next))
            }
        }
        return if (exps.size == 1) exps[0] else Expression.Do(exps)
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
            consumeEol()
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
                consumeEol()
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

    private inline fun <reified TK, T> between(ignoreEol: Boolean = false, parser: () -> T): List<T> {
        val res = mutableListOf<T>()
        res += parser()
        if (ignoreEol) consumeEol()
        while (iter.peek().value is TK) {
            iter.next()
            res += parser()
            if (ignoreEol) consumeEol()
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

    private fun consumeEol(): Boolean {
        return if (iter.peek().value is EOL) {
            iter.next()
            true
        } else false
    }

    private fun expectEolOrSemicolon() {
        val tk = iter.next()
        if (tk.value !is EOL && tk.value !is Semicolon) {
            throwError(withError(E.EOL_OR_SEMICOLON)(tk))
        }
    }

    /**
     * Finds the full module name of an import alias
     */
    private fun findImport(alias: String): ModuleName? {
        return imports.filterIsInstance<Import.As>().find { it.alias == alias }?.module
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
        private fun noErr() = { tk: Spanned<Token> -> "Cannot happen, token: $tk" }
    }
}
package novah.frontend

import novah.frontend.typechecker.Type

object TestUtil {

    private fun lexString(input: String): MutableList<Spanned<Token>> {
        val lex = Lexer(input)
        val tks = mutableListOf<Spanned<Token>>()
        while(lex.hasNext()) {
            tks += lex.next()
        }
        tks += lex.next() // EOF
        return tks
    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun getResourceAsString(name: String): String {
        return TestUtil::class.java.classLoader.getResource(name).readText(Charsets.UTF_8)
    }

    fun lexResource(res: String): MutableList<Spanned<Token>> {
        return lexString(getResourceAsString(res))
    }

    fun parseResource(res: String): Module {
        val resStr = getResourceAsString(res)
        val lexer = Lexer(resStr)
        val parser = Parser(lexer)
        return parser.parseFullModule()
    }

    fun tvar(n: String) = Type.TVar(n)
    fun tfun(l: Type, r: Type) = Type.TFun(l, r)
    fun forall(x: String, t: Type) = Type.TForall(x, t)

    fun _i(i: Long) = Expr.IntE(i)
    fun _f(f: Double) = Expr.FloatE(f)
    fun _s(s: String) = Expr.StringE(s)
    fun _c(c: Char) = Expr.CharE(c)
    fun _b(b: Boolean) = Expr.Bool(b)
    fun _var(n: String) = Expr.Var(n)
    fun abs(n: String, e: Expr) = Expr.Lambda(n, e)
    fun app(l: Expr, r: Expr) = Expr.App(l, r)
    fun app2(f: Expr, arg1: Expr, arg2: Expr) = app(app(f, arg1), arg2)
    fun _if(c: Expr, t: Expr, e: Expr) = Expr.If(c, t, e)
    fun _let(bs: List<Pair<String, Expr>>, body: Expr): Expr {
        return if (bs.size <= 1) Expr.Let(LetDef(bs[0].first, bs[0].second), body)
        else Expr.Let(LetDef(bs[0].first, bs[0].second), _let(bs.drop(1), body))
    }
}
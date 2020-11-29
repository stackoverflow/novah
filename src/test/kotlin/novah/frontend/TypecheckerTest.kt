package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.typechecker.Elem
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.Inference.infer
import novah.frontend.typechecker.Type

class TypecheckerTest : StringSpec({

    val tInt = Type.TInt
    val tFloat = Type.TFloat
    val tString = Type.TString
    val tChar = Type.TChar
    val tBool = Type.TBoolean

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

    "typecheck primitive expressions" {
        val iexp = _i(34)
        val fexp = _f(34.0)
        val sexp = _s("asd")
        val cexp = _c('A')
        val bexp = _b(false)
        val tint = infer(iexp)
        val tfloat = infer(fexp)
        val tstring = infer(sexp)
        val tchar = infer(cexp)
        val tboolean = infer(bexp)

        tint shouldBe tInt
        tfloat shouldBe tFloat
        tstring shouldBe tString
        tchar shouldBe tChar
        tboolean shouldBe tBool
    }

    "typecheck if" {
        val exp = Expr.If(_b(false), _i(0), _i(1))

        val ty = infer(exp)

        ty shouldBe tInt
    }

    "typecheck generics" {
        val exp = abs("x", _var("x"))

        val ty = infer(exp)

        // ty shouldBe `forall a. a -> a`
        if (ty is Type.TForall) {
            val n = ty.name
            val t = ty.type

            if (t is Type.TFun) {
                t.arg shouldBe tvar(n)
                t.ret shouldBe tvar(n)
            } else error("Type should be function")
        } else error("Function should be polymorphic")

        val exp2 = app(exp, _b(false))

        val ty2 = infer(exp2)

        ty2 shouldBe tBool
    }

    "typecheck pre-added context vars" {
        context.add(Elem.CVar("id", forall("a", tfun(tvar("a"), tvar("a")))))

        val ty = infer(app(_var("id"), _i(10)))

        ty shouldBe tInt
    }

    "typecheck let" {
        val exp = _let(
            listOf(
                "x" to _s("bla"),
                "y" to _var("x")
            ),
            _var("y")
        )

        val ty = infer(exp)

        ty shouldBe tString
    }

    "typecheck polymorphic let bindings" {
        val exp = _let(
            listOf(
                "id" to abs("a", _var("a"))
            ),
            app(_var("id"), _i(10))
        )

        context.add(Elem.CVar("+", tfun(tInt, tInt)))
        context.add(Elem.CVar("-", tfun(tInt, tInt)))
        val ty = infer(exp)

        ty shouldBe tInt
    }

    "typecheck a recursive function" {
        /**
         * fact :: Int -> Int
         * fact x =
         *   if x <= 1
         *     then x
         *     else x * fact (x - 1)
         */
        context.add(Elem.CVar("*", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("-", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("<=", tfun(tInt, tfun(tInt, tBool))))
        context.add(Elem.CVar("fact", tfun(tInt, tInt)))

        val fact = abs(
            "x",
            _if(
                app2(_var("<="), _var("x"), _i(1)),
                _var("x"),
                app2(_var("*"), _var("x"), app(_var("fact"), app2(_var("-"), _var("x"), _i(1))))
            )
        )

        val ty = infer(fact)

        ty shouldBe tfun(tInt, tInt)
    }
})
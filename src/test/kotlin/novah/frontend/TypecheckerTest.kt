package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil._b
import novah.frontend.TestUtil._c
import novah.frontend.TestUtil._do
import novah.frontend.TestUtil._f
import novah.frontend.TestUtil._i
import novah.frontend.TestUtil._if
import novah.frontend.TestUtil._let
import novah.frontend.TestUtil._s
import novah.frontend.TestUtil._var
import novah.frontend.TestUtil.abs
import novah.frontend.TestUtil.app
import novah.frontend.TestUtil.app2
import novah.frontend.TestUtil.forall
import novah.frontend.TestUtil.tfun
import novah.frontend.TestUtil.tvar
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

    beforeTest {
        context.reset()
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
        val exp = _if(_b(false), _i(0), _i(1))

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

        val ty = infer(exp)

        ty shouldBe tInt
    }

    "typecheck do statements" {
        context.add(Elem.CVar("+", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("println", tfun(tString, tvar("Unit"))))
        context.add(Elem.CVar("store", tfun(tInt, tvar("Unit"))))

        val exp = _do(
            app(_var("println"), _s("Hello world")),
            app2(_var("+"), _i(10), _i(10)),
            app(_var("store"), _i(100)),
            _b(true)
        )

        val ty = infer(exp)

        ty shouldBe tBool
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
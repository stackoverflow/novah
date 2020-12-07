package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.forall
import novah.frontend.TestUtil.inferString
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.tfun
import novah.frontend.TestUtil.tvar
import novah.frontend.typechecker.Elem
import novah.frontend.typechecker.InferContext
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.tBoolean
import novah.frontend.typechecker.InferContext.tChar
import novah.frontend.typechecker.InferContext.tFloat
import novah.frontend.typechecker.InferContext.tInt
import novah.frontend.typechecker.InferContext.tString
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Type

class TypecheckerTest : StringSpec({

    beforeTest {
        context.reset()
        InferContext.initDefaultContext()
        context.add(Elem.CVar("*", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("+", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("-", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("<=", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">=", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("<", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("==", tfun(tBoolean, tfun(tBoolean, tBoolean))))
        context.add(Elem.CVar("println", tfun(tString, tvar("Unit"))))
        context.add(Elem.CVar("id", forall("a", tfun(tvar("a"), tvar("a")))))
    }

    fun exprX(expr: String) = "x = $expr".module()

    fun inferX(expr: String) = inferString(exprX(expr))["x"]!!

    "typecheck primitive expressions" {
        val tint = inferX("34")
        val tfloat = inferX("34.0")
        val tstring = inferX("\"asd\"")
        val tchar = inferX("'A'")
        val tboolean = inferX("false")

        tint shouldBe tInt
        tfloat shouldBe tFloat
        tstring shouldBe tString
        tchar shouldBe tChar
        tboolean shouldBe tBoolean
    }

    "typecheck if" {
        val ty = inferX("if false then 0 else 1")

        ty shouldBe tInt
    }

    "typecheck subsumed if" {
        val code = """
            f = if true then 10 else id 0
            f2 a = if true then 10 else id a
        """.module()

        val tys = inferString(code)

        tys["f"] shouldBe tInt
        tys["f2"] shouldBe tfun(tInt, tInt)

        shouldThrow<InferenceError> {
            inferX("if true then 10 else id 'a'")
        }
    }

    "typecheck generics" {
        val ty = inferX("\\x -> x")

        // ty shouldBe `forall a. a -> a`
        if (ty is Type.TForall) {
            val n = ty.name
            val t = ty.type

            if (t is Type.TFun) {
                t.arg shouldBe tvar(n)
                t.ret shouldBe tvar(n)
            } else error("Type should be function")
        } else error("Function should be polymorphic")

        val ty2 = inferX("(\\x -> x) false")

        ty2 shouldBe tBoolean
    }

    "typecheck pre-added context vars" {
        val ty = inferX("id 10")

        ty shouldBe tInt
    }

    "typecheck let" {
        val code = """
            x = let x = "bla"
                    y = x
                in y
        """.module()

        val ty = inferString(code)["x"]

        ty shouldBe tString
    }

    "typecheck polymorphic let bindings" {
        val code = """
            x = let id a = a
                in id 10
        """.module()

        val ty = inferString(code)["x"]

        ty shouldBe tInt
    }

    "typecheck do statements" {
        context.add(Elem.CVar("store", tfun(tInt, tvar("Unit"))))

        val code = """
            x = do
              println "hello world"
              10 + 10
              store 100
              true
        """.module()

        val ty = inferString(code)["x"]

        ty shouldBe tBoolean
    }

    "typecheck a recursive function" {
        // only works because of the type annotations for now
        val code = """
            fact :: Int -> Int
            fact x =
              if x <= 1
              then x
              else x * fact (x - 1)
        """.module()

        val ty = inferString(code)["fact"]

        ty shouldBe tfun(tInt, tInt)
    }

    "typecheck mutually recursive functions" {
        // only works because of the type annotations for now
        val code = """
            f1 :: Int -> Int
            f1 x =
              if x > 0
              then f2 x
              else x - 1
            
            f2 :: Int -> Int
            f2 x =
              if x <= 0
              then f1 x
              else x + 1
        """.module()
        shouldNotThrowAny {
            inferString(code)
        }
    }

    "test" {
        val code = """
            id x = x
            
            //fun :: forall a b. a -> b -> (forall c. c -> c) -> a
            fun x y f = do
              f 1
              f x
        """.module()
        val tys = inferString(code)

        tys.map { println(it) }
    }
})
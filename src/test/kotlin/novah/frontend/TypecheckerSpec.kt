package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.inferString
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.setupContext
import novah.frontend.TestUtil.tfun
import novah.frontend.TestUtil.tvar
import novah.frontend.typechecker.Elem
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tByte
import novah.frontend.typechecker.Prim.tChar
import novah.frontend.typechecker.Prim.tDouble
import novah.frontend.typechecker.Prim.tFloat
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tLong
import novah.frontend.typechecker.Prim.tShort
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Type

class TypecheckerSpec : StringSpec({

    beforeTest {
        setupContext()
    }

    fun exprX(expr: String) = "x = $expr".module()

    fun inferX(expr: String): Type {
        context.reset()
        setupContext()
        return inferString(exprX(expr))["x"]!!
    }

    "typecheck primitive expressions" {
        val tint = inferX("34")
        val tlong = inferX("34L")
        val tfloat = inferX("34.0F")
        val tdouble = inferX("34.0")
        val tstring = inferX("\"asd\"")
        val tchar = inferX("'A'")
        val tboolean = inferX("false")

        tint shouldBe tInt
        tlong shouldBe tLong
        tfloat shouldBe tFloat
        tdouble shouldBe tDouble
        tstring shouldBe tString
        tchar shouldBe tChar
        tboolean shouldBe tBoolean
    }

    "typecheck number conversions" {
        val code = """
            i = 12
            
            b :: Byte
            b = 12
            
            b2 = 12 :: Byte
            
            s :: Short
            s = 12
            
            l = 9999999999
            
            l2 = 12L
            
            l3 :: Long
            l3 = 12
            
            d = 12.0
            
            f = 12.0F
            
            bi = 0b1101011
            
            bi2 = 0b1101011L
            
            bi3 :: Byte
            bi3 = 0b1101011
            
            hex = 0xFF
            
            hex2 = 0xFFL
            
            hex3 :: Short
            hex3 = 0xFF
        """.module()

        val tys = inferString(code)

        tys["i"] shouldBe tInt
        tys["b"] shouldBe tByte
        tys["b2"] shouldBe tByte
        tys["s"] shouldBe tShort
        tys["l"] shouldBe tLong
        tys["l2"] shouldBe tLong
        tys["l3"] shouldBe tLong
        tys["d"] shouldBe tDouble
        tys["f"] shouldBe tFloat
        tys["bi"] shouldBe tInt
        tys["bi2"] shouldBe tLong
        tys["bi3"] shouldBe tByte
        tys["hex"] shouldBe tInt
        tys["hex2"] shouldBe tLong
        tys["hex3"] shouldBe tShort
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
        val code = """
            fact x =
              if x <= 1
              then x
              else x * fact (x - 1)
        """.module()

        val ty = inferString(code)["fact"]

        ty shouldBe tfun(tInt, tInt)
    }

    "typecheck mutually recursive functions" {
        val code = """
            //f1 :: Int -> Int
            f1 x =
              if x > 0
              then f2 x
              else x - 1
            
            //f2 :: Int -> Int
            f2 x =
              if x <= 0
              then f1 x
              else x + 1
        """.module()

        shouldNotThrowAny {
            inferString(code)
        }
    }

    "typecheck mutually recursive functions 2" {
        val code = """
            f1 x = f2 x + 1
            
            f2 x = f1 x
        """.module()

        shouldNotThrowAny {
            inferString(code)
        }
    }

    "high ranked types compile with type annotation" {
        val code = """
            poly :: (forall a. a -> a) -> Boolean
            poly f = (f 0 < 1) == f true
            
            type Tuple a b = Tuple a b
            
            fun :: (forall a. a -> a) -> Tuple Int String
            fun f = Tuple (f 1) (f "a")
        """.module()

        shouldNotThrowAny {
            inferString(code)
        }
    }

    "high ranked types do not compile without type annotation" {
        val code = """
            //poly :: (forall a. a -> a) -> Boolean
            poly f = (f 0 < 1) == f true
            
            type Tuple a b = Tuple a b
            
            //fun :: (forall a. a -> a) -> Tuple Int String
            fun f = Tuple (f 1) (f "a")
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }
    }
})
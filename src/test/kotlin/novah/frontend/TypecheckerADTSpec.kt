package novah.frontend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.inferString
import novah.frontend.TestUtil.module
import novah.frontend.typechecker.InferContext
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Prim

class TypecheckerADTSpec : StringSpec({

    beforeTest {
        InferContext.context.reset()
        Prim.addAll(InferContext.context)
    }

    "typecheck no parameter ADTs" {
        val code = """
            type Day = Week | Weekend
            
            x :: Int -> Day
            x a = Week
            
            y a = Weekend
        """.module()

        val tys = inferString(code)

        tys["x"]?.simpleName() shouldBe "(Int -> Day)"
        tys["y"]?.substFreeVar("a")?.simpleName() shouldBe "forall a. (a -> Day)"
    }

    "typecheck one parameter ADTs" {
        val code = """
            type May a
              = It a
              | Nope
            
            //x :: Int -> May String
            //x a = Nope
            
            //y :: Int -> May String
            y a = Nope
            
            //w :: Int -> May Int
            //w a = It 5
        """.module()

        val tys = inferString(code)

        //tys["x"]?.simpleName() shouldBe "(Int -> May String)"
        //tys["y"]?.simpleName() shouldBe "forall a. (a -> May a)"
        //tys["w"]?.simpleName() shouldBe "(Int -> May Int)"
    }

    "typecheck 2 parameter ADTs" {
        val code = """
            type Result k e
              = Ok k
              | Err e
            
            x :: Int -> Result Int String
            x a = Ok 1
            
            //y :: Result Int String
            y a = Err "oops"
        """.module()

        val tys = inferString(code)
        tys["x"]?.simpleName() shouldBe "(Int -> Result Int String)"
        val y = tys["y"]!!.substFreeVar("k")
        y.simpleName() shouldBe "forall k. Result k String"
    }

    "typecheck recursive ADT" {
        val code = """
            type List a = Nil | Cons a (List a)
            
            x :: Int -> List String
            x a = Nil
            
            y a = Cons 1 (Cons 2 (Cons 3 Nil))
        """.module()

        val tys = inferString(code)

        tys["x"]?.simpleName() shouldBe "(Int -> List String)"
        tys["y"]?.substFreeVar("a")?.simpleName() shouldBe "forall a. (a -> List Int)"
    }

    "typecheck complex type" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor (forall c. c -> a)
            
            str :: forall a. a -> String
            str x = "1"
            
            x a = Cfor str
            
            y a = Cfun str
        """.module()

        val tys = inferString(code)

        val x = tys["x"]!!.substFreeVar("b")
        x.simpleName() shouldBe "forall b. Comp String b"
        val y = tys["y"]!!.substFreeVar("a")
        y.simpleName() shouldBe "forall a. Comp a String"
    }

    "typecheck complex type 2" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor (forall c. c -> a)
            
            id :: forall a. a -> a
            id x = "1"
            
            x a = Cfor str
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }

    }

    "unbounded variables should not compile" {
        val code = """
            type Typ b = Empty | Jus a b
        """.module()

        shouldThrow<ParserError> {
            inferString(code)
        }
    }

    "kind error check (too many parameter)" {
        val code = """
            type Day = Week | Weekend
            
            f :: Int -> Day Int
            f x = Week
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }
    }

    "kind error check (too few parameters)" {
        val code = """
            type Maybe a = Just a | Nope
            
            f :: Int -> Maybe
            f x = Nope
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }
    }

    "kind error in type constructor" {
        val code = """
            type Day = Week | Weekend
            
            type May a = Just a | Nope
            
            type Foo a = Bar Day | Baz May
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }
    }
})
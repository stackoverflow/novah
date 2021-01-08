package novah.frontend

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.typechecker.CompilationError
import novah.frontend.typechecker.InferenceError

class TypecheckerADTSpec : StringSpec({

    "typecheck no parameter ADTs" {
        val code = """
            type Day = Week | Weekend
            
            x :: Int -> Day
            x a = Week
            
            y a = Weekend
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "(Int -> Day)"
        tys["y"]?.type?.substFreeVar("a")?.simpleName() shouldBe "forall a. (a -> Day)"
    }

    "typecheck one parameter ADTs" {
        val code = """
            type May a
              = It a
              | Nope
            
            x :: Int -> May String
            x a = Nope
            
            y :: forall a. a -> May a
            y a = Nope
            
            w :: Int -> May Int
            w a = It 5
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "(Int -> May String)"
        tys["y"]?.type?.simpleName() shouldBe "forall a. (a -> May a)"
        tys["w"]?.type?.simpleName() shouldBe "(Int -> May Int)"
    }

    "typecheck 2 parameter ADTs" {
        val code = """
            type Result k e
              = Ok k
              | Err e
            
            x :: Int -> Result Int String
            x a = Ok 1
            
            //y :: Int -> Result Int String
            y a = Err "oops"
        """.module()

        val tys = TestUtil.compileCode(code).env.decls
        tys["x"]?.type?.simpleName() shouldBe "(Int -> Result Int String)"
        val y = tys["y"]!!.type.substFreeVar("k")
        y.simpleName() shouldBe "forall k. forall a. (k -> Result a String)"
    }

    "typecheck recursive ADT" {
        val code = """
            type List a = Nil | Cons a (List a)
            
            x :: Int -> List String
            x a = Nil
            
            y a = Cons 1 (Cons 2 (Cons 3 Nil))
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "(Int -> List String)"
        tys["y"]?.type?.substFreeVar("a")?.simpleName() shouldBe "forall a. (a -> List Int)"
    }

    "typecheck complex type" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor (forall c. c -> a)
            
            str :: forall a. a -> String
            str x = "1"
            
            x a = Cfor str
            
            y a = Cfun str
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        val x = tys["x"]!!.type.substFreeVar("b")
        x.simpleName() shouldBe "forall b. forall a. (b -> Comp String a)"
        val y = tys["y"]!!.type.substFreeVar("b")
        y.simpleName() shouldBe "forall b. forall a. (b -> Comp a String)"
    }

    "typecheck complex type 2" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor (forall c. c -> a)
            
            id :: forall a. a -> a
            id x = "1"
            
            x a = Cfor str
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileCode(code)
        }
    }

    "unbounded variables should not compile" {
        val code = """
            type Typ b = Empty | Jus a b
        """.module()

        shouldThrow<CompilationError> {
            TestUtil.compileCode(code)
        }
    }

    "kind error check (too many parameter)" {
        val code = """
            type Day = Week | Weekend
            
            f :: Int -> Day Int
            f x = Week
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileCode(code)
        }
    }

    "kind error check (too few parameters)" {
        val code = """
            type Maybe a = Just a | Nope
            
            f :: Int -> Maybe
            f x = Nope
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileCode(code)
        }
    }

    "kind error in type constructor" {
        val code = """
            type Day = Week | Weekend
            
            type May a = Just a | Nope
            
            type Foo a = Bar Day | Baz May
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileCode(code)
        }
    }
})
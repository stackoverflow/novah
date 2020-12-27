package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.inferString
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.preCompile
import novah.frontend.TestUtil.tcon
import novah.frontend.TestUtil.tfun
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tString

class PatternMatchingSpec : StringSpec({

    beforeTest {
        TestUtil.setupContext()
    }

    "pattern matching typechecks" {
        val code = """
            f x = case x of
              0 -> "zero"
              1 -> "one"
              v -> toString (v + 4)
        """.module()

        val ty = inferString(code)["f"]
        ty shouldBe tfun(tInt, tString)
    }

    "pattern matching with polymorphic types typechecks" {
        val code = """
            f x = case id x of
              0 -> "zero"
              1 -> "one"
              v -> toString (v + 4)
        """.module()

        val ty = inferString(code)["f"]
        ty shouldBe tfun(tInt, tString)
    }

    "pattern matching constructor typechecks" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f x = case x of
              Just v -> toString (v + 4)
              Nothing -> "nope"
        """.module()

        val ty = inferString(code)["f"]
        ty shouldBe tfun(tcon("test.Maybe", tInt), tString)
    }

    "overlapping variables are disallowed" {
        val code = """
            type Tuple a b = Tuple a b
            
            f x = case x of
              Tuple v v -> v
              Tuple t _ -> t
        """.module()

        shouldThrow<InferenceError> {
            inferString(code)
        }
    }

    "pattern matching unreacheable cases fails" {
        val code = """
            f x = case id x of
              0 -> "zero"
              1 -> "one"
              _ -> "asd"
              5 -> "five"
        """.module()

        shouldThrow<InferenceError> {
            preCompile(code)
        }
    }

    "pattern matching unreacheable constructor fails" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f x = case x of
              Just v -> toString (v + 4)
              Nothing -> "bla"
              Nothing -> "nope"
        """.module()

        shouldThrow<InferenceError> {
            preCompile(code)
        }
    }

    "pattern matching incomplete cases fail" {
        val code = """
            f x = case x of
              0 -> "zero"
              1 -> "one"
              5 -> "five"
        """.module()

        shouldThrow<InferenceError> {
            preCompile(code)
        }
    }

    "pattern matching incomplete cases fail (constructor)" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f x = case x of
              Just (Just true) -> "a"
              Just Nothing -> "c"
              Nothing -> "b"
        """.module()

        shouldThrow<InferenceError> {
            preCompile(code)
        }
    }

    "pattern matching literals succeed" {
        val code = """
            f x = case x of
              true -> "a"
              false -> "b"
        """.module()

        shouldNotThrowAny {
            preCompile(code)
        }
    }
})
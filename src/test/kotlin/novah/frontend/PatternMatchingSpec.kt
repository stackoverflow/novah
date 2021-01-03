package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.tcon
import novah.frontend.TestUtil.tfun
import novah.frontend.typechecker.InferenceError
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tString

class PatternMatchingSpec : StringSpec({

    "pattern matching typechecks" {
        val code = """
            f x = case x of
              0 -> "zero"
              1 -> "one"
              v -> toString v
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type

        ty shouldBe tfun(tInt, tString)
    }

    "pattern matching with polymorphic types typechecks" {
        val code = """
            id x = x
            
            f x = case id x of
              0 -> "zero"
              1 -> "one"
              v -> toString v
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty shouldBe tfun(tInt, tString)
    }

    "pattern matching constructor typechecks" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f :: Maybe Int -> String
            f x = case x of
              Just v -> toString v
              Nothing -> "nope"
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
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
            TestUtil.compileCode(code)
        }
    }

    "pattern matching unreacheable cases fails" {
        val code = """
            id x = x
            
            f x = case id x of
              0 -> "zero"
              1 -> "one"
              _ -> "asd"
              5 -> "five"
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileAndOptimizeCode(code)
        }
    }

    "pattern matching unreacheable constructor fails" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f x = case x of
              Just v -> toString v
              Nothing -> "bla"
              Nothing -> "nope"
        """.module()

        shouldThrow<InferenceError> {
            TestUtil.compileAndOptimizeCode(code)
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
            TestUtil.compileAndOptimizeCode(code)
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
            TestUtil.compileAndOptimizeCode(code)
        }
    }

    "pattern matching literals succeed" {
        val code = """
            f x = case x of
              true -> "a"
              false -> "b"
        """.module()

        shouldNotThrowAny {
            TestUtil.compileAndOptimizeCode(code)
        }
    }
})
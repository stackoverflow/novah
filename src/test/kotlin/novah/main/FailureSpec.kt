package novah.main

import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import novah.frontend.TestUtil
import novah.frontend.typechecker.CompilationError

class FailureSpec : StringSpec({

    "duplicate modules fail" {
        val compiler = TestUtil.compilerFor("fail/duplication")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "cycling modules fail" {
        val compiler = TestUtil.compilerFor("fail/cycle")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "non-existing import module fail" {
        val code = """
            module importfail

            import non.existing.mod (foo)

            x :: Int
            x = 1
        """.trimIndent()

        val compiler = TestUtil.compilerForCode(code)

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "non-existing import declaration fail" {
        val compiler = TestUtil.compilerFor("fail/importfail")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "private import declaration fail" {
        val compiler = TestUtil.compilerFor("fail/privateimport")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }
})
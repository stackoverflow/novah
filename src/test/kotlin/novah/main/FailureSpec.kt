package novah.main

import io.kotest.assertions.failure
import io.kotest.assertions.throwables.shouldThrowExactly
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil
import java.io.File

class FailureSpec : StringSpec({

    "run all failure tests" {
        val basePath = "src/test/resources/failure"
        File("$basePath/source").walkTopDown().filter { it.extension == "novah" }.forEach { file ->
            val path = file.toPath()
            val testName = file.nameWithoutExtension
            val output = File("$basePath/error/$testName.txt")

            val compiler = Compiler.new(sequenceOf(path), false)
            try {
                compiler.run(File("."), true)
                throw failure("Expected test `$testName` to fail with a CompilationError.")
            } catch (ce: CompilationError) {
                val error = ce.problems.joinToString("\n") { it.formatToConsole() }
                error shouldBe output.readText()
            }
        }
    }

    "duplicate modules fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/duplication")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "cycling modules fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/cycle")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "non-existing import declaration fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/importfail")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }

    "private import declaration fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/privateimport")

        shouldThrowExactly<CompilationError> {
            compiler.compile()
        }
    }
})
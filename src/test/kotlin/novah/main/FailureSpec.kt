package novah.main

import io.kotest.assertions.failure
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
            } catch (e: Exception) {
                throw failure("Got exception in test `$testName`: ${e.message}")
            }
        }
    }

    "duplicate modules fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/duplicatedmodules")

        val error = """
            module [33mmod1[0m
            at src/test/resources/multimodulefail/duplicatedmodules/same.novah:1:1 - 1:12
            
              Found duplicate module mod1.
            

        """.trimIndent()
        
        withError(error) {
            compiler.compile()
        }
    }

    "cycling modules fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/cycle")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/cycle/mod2.novah:1:1 - 1:12
            
              Found cycle between modules mod2, mod1.
            

        """.trimIndent()

        withError(error) {
            compiler.compile()
        }
    }

    "non-existing import declaration fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/importfail")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/importfail/mod2.novah:3:1 - 3:18
            
              Cannot find declaration bar in module mod1.
            

        """.trimIndent()
        
        withError(error) {
            compiler.compile()
        }
    }

    "private import declaration fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/privateimport")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/privateimport/mod2.novah:3:1 - 3:18
            
              Cannot import private declaration foo in module mod1.
            

        """.trimIndent()
        
        withError(error) {
            compiler.compile()
        }
    }

    "duplicate imported types fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/duplicatedimports")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/duplicatedimports/mod2.novah:5:1 - 5:40
            
              Another import with name ArrayList is already in scope.
            
            
        """.trimIndent()
        withError(error) {
            compiler.compile()
        }
    }

    "duplicate imported variables fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/duplicatedimportvars")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/duplicatedimportvars/mod2.novah:5:1 - 5:57
            
              Another import with name newArrayList is already in scope.
            
 
        """.trimIndent()
        withError(error) {
            compiler.compile()
        }
    }
}) {
    companion object {
        private inline fun withError(error: String, block: () -> Unit) {
            try {
                block()
                throw failure("Expected test to fail with a CompilationError.")
            } catch (ce: CompilationError) {
                val err = ce.problems[0]
                err.formatToConsole() shouldBe error
            }
        }
    }
}
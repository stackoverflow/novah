/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.main

import io.kotest.assertions.failure
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil
import novah.frontend.typechecker.Typechecker
import java.io.File

class FailureSpec : StringSpec({

    "run all failure tests" {
        File("src/test/resources/failure").walkTopDown().filter { it.extension == "novah" }.forEach { file ->
            Typechecker.reset()
            val path = file.toPath()
            val testName = file.nameWithoutExtension
            val output = File("${file.parent}/$testName.txt")

            val compiler = Compiler.new(sequenceOf(path), "",false)
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

    "run all warning tests" {
        File("src/test/resources/warning").walkTopDown().filter { it.extension == "novah" }.forEach { file ->
            Typechecker.reset()
            val path = file.toPath()
            val testName = file.nameWithoutExtension
            val output = File("${file.parent}/$testName.txt")

            val compiler = Compiler.new(sequenceOf(path), "", false)
            val warns = compiler.run(File("."), true)
            if (warns.isEmpty()) throw failure("Expected test `$testName` to return warnings.")

            val warn = warns.joinToString("\n") { it.formatToConsole() }
            warn shouldBe output.readText()
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
            
            
            module [33mmod1[0m
            at src/test/resources/multimodulefail/cycle/mod1.novah:1:1 - 1:12
            
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

    "unused imports fail" {
        val compiler = TestUtil.compilerFor("multimodulefail/unusedimport")

        val error = """
            module [33mmod2[0m
            at src/test/resources/multimodulefail/unusedimport/mod2.novah:3:1 - 3:27
            
              Import notUsed is unused in module.
            
            
            module [33mmod2[0m
            at src/test/resources/multimodulefail/unusedimport/mod2.novah:5:1 - 5:35
            
              Import codePoints is unused in module.
            

        """.trimIndent()
        withError(error) {
            compiler.run(File("."), true)
        }
    }
}) {
    companion object {
        private inline fun withError(error: String, block: () -> Unit) {
            try {
                block()
                throw failure("Expected test to fail with a CompilationError.")
            } catch (ce: CompilationError) {
                val err = ce.problems.joinToString("\n") { it.formatToConsole() }
                err shouldBe error
            }
        }
    }
}
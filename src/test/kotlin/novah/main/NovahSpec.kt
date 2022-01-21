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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NovahSpec : StringSpec({

    fun compileStdlibTests() {
        val compiler = TestUtil.compilerFor("test", devMode = false)
        compiler.run(TestUtil.cleanAndGetOutDir())
    }

    fun runCommand(command: String, cwd: File): Int {
        return try {
            val parts = command.split("\\s".toRegex())
            val process = ProcessBuilder(*parts.toTypedArray())
                .directory(cwd)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()

            process.waitFor(10, TimeUnit.MINUTES)
            process.exitValue()
        } catch (e: IOException) {
            e.printStackTrace()
            3
        }
    }

    fun runStdlibTests(): Int =
        runCommand("java -cp output test.main.\$Module", File("."))

    "run standard library tests" {
        compileStdlibTests()
        runStdlibTests() shouldBe 0
    }
})
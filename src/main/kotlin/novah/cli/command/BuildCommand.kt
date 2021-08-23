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
package novah.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import novah.main.CompilationError
import novah.main.Compiler
import java.io.File
import kotlin.system.exitProcess

class BuildCommand : CliktCommand(name = "build", help = "Compile the project described by the `novah.json` file") {

    private val alias by option(
        "-a",
        "--alias",
        help = "Alias to turn on"
    )

    private val out by option(
        "-o", "--out",
        help = "Output directory for the generated classes (default output)"
    ).file(
        mustExist = false,
        canBeDir = true,
        canBeFile = false
    ).default(File("output"))

    private val check by option(
        "-c", "--check",
        help = "Just check the code for errors, but don't generate code"
    ).flag(default = false)

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Print information about the process"
    ).flag(default = false)

    override fun run() {
        val al = alias ?: "\$default"
        val classpath = getClasspath(al, "classpath") ?: return
        val sourcepath = getClasspath(al, "sourcepath") ?: return

        val compiler = Compiler.new(emptySequence(), classpath, sourcepath, verbose)
        try {
            val warns = compiler.run(out, check)
            Compiler.printWarnings(warns, ::echo)
            echo("Success")
        } catch (ce: CompilationError) {
            val allErrs = compiler.getWarnings() + ce.problems
            Compiler.printErrors(allErrs, ::echo) { echo(it, err = true) }
            echo("Failure", err = true)
            exitProcess(1)
        }
    }

    private fun getClasspath(alias: String, type: String): String? {
        val cp = File(".cpcache/$alias.$type")
        if (!cp.exists()) {
            if (alias == "\$default") {
                echo("No classpath found. Run the `deps` command first to generate a classpath")
            } else echo("Alias not found: $alias")
            return null
        }

        return cp.readText(Charsets.UTF_8)
    }
}
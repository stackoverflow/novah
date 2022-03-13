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
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import novah.main.CompilationError
import novah.main.Compiler
import novah.main.Options
import java.io.File
import kotlin.system.exitProcess

class CompileCommand : CliktCommand(name = "compile", help = "compile source files") {

    private val out by option(
        "-o", "--out",
        help = "output directory for the generated classes (default output)"
    ).file(
        mustExist = false,
        canBeDir = true,
        canBeFile = false
    ).default(File("output"))

    private val verbose by option(
        "-v", "--verbose",
        help = "print information about the compilation process to stdout"
    ).flag(default = false)

    private val classpath by option("-cp", "--classpath", help = "where to find user class files and sources")

    private val sourcepath by option("-sp", "--sourcepath", help = "where to find novah sources")

    private val devMode by option(
        "-d", "--dev",
        help = "run the compiler in dev mode: no optimizations will be applied and some errors will be warnings."
    ).flag(default = false)

    private val srcs by argument(help = "source files").path(mustExist = true, canBeDir = false).multiple()

    override fun run() {
        if (out.isFile) {
            echo("output directory cannot be a file: $out", err = true)
            return
        }
        if (!out.exists()) {
            val success = out.mkdirs()
            if (!success) {
                echo("failed to create output directory: $out", err = true)
                return
            }
        }
        if (verbose) echo("compiling files to $out")

        val compiler = Compiler.new(srcs.asSequence(), classpath, sourcepath, Options(verbose, devMode))
        try {
            val warns = compiler.run(out)
            Compiler.printWarnings(warns, ::echo)
            echo("Success")
        } catch (_: CompilationError) {
            val allErrs = compiler.errors()
            Compiler.printErrors(allErrs, ::echo)
            echo("Failure", err = true)
            exitProcess(1)
        }
    }
}
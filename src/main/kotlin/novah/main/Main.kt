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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import novah.cli.DepsCommand
import novah.cli.NewCommand
import novah.frontend.error.CompilerProblem.Companion.RED
import novah.frontend.error.CompilerProblem.Companion.RESET
import novah.frontend.error.CompilerProblem.Companion.YELLOW
import novah.frontend.error.Severity
import novah.ide.NovahIde
import java.io.File
import kotlin.system.exitProcess

class CompileCommand : CliktCommand(name = "compile", help = "compile source files") {

    private val out by option("-o", "--out", help = "Output directory for the generated classes").file(
        mustExist = false,
        canBeDir = true,
        canBeFile = false
    ).default(File("./output"))

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Print information about the compilation process to stdout"
    ).flag(default = false)

    private val classPath by option("-cp", "--classpath", help = "Where to find user class files and sources")

    private val srcs by argument(help = "Source files").path(mustExist = true, canBeDir = false).multiple()

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
        if (verbose) echo("Compiling files to $out")

        val compiler = Compiler.new(srcs.asSequence(), classPath, verbose)
        try {
            val warns = compiler.run(out)
            if (warns.isNotEmpty()) {
                val label = if (warns.size > 1) "Warnings" else "Warning"
                echo("$YELLOW$label:$RESET\n")
                warns.forEach { echo(it.formatToConsole()) }
            }
            echo("Success")
        } catch (ce: CompilationError) {
            val allErrs = compiler.getWarnings() + ce.problems
            val (errors, warns) = allErrs.partition { it.severity == Severity.ERROR }
            if (warns.isNotEmpty()) {
                val label = if (warns.size > 1) "Warnings" else "Warning"
                echo("$YELLOW$label:$RESET\n")
                warns.forEach { echo(it.formatToConsole()) }
            }
            if (errors.isNotEmpty()) {
                val label = if (errors.size > 1) "Errors" else "Error"
                echo("$RED$label:$RESET\n", err = true)
                errors.forEach { echo(it.formatToConsole(), err = true) }
            }
            echo("Failure", err = true)
            exitProcess(1)
        }
    }
}

class IdeCommand : CliktCommand(name = "ide", help = "run the Novah language server") {

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Send debug messages to the client"
    ).flag(default = false)

    override fun run() {
        NovahIde.run(verbose)
    }
}

class MainCommand : CliktCommand(name = "") {
    override fun run() {
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val comms = arrayOf(CompileCommand(), DepsCommand(), NewCommand(), IdeCommand())
        MainCommand().subcommands(*comms).main(args)
    }
}
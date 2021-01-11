package novah.main

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.path
import novah.frontend.error.CompilerProblem.Companion.RED
import novah.frontend.error.CompilerProblem.Companion.RESET
import novah.frontend.error.CompilerProblem.Companion.YELLOW
import novah.frontend.error.Severity
import java.io.File
import kotlin.system.exitProcess

class CompileCommand : CliktCommand() {

    private val out by option("-o", "--out", help = "Output directory for the generated classes").file(
        mustExist = false,
        canBeDir = true,
        canBeFile = false
    ).default(File("./output"))

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Prints information about the compilation process to stdout"
    ).flag(default = false)

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

        try {
            Compiler.new(srcs.asSequence(), verbose).run(out)
        } catch (ce: CompilationError) {
            val (errors, warns) = ce.problems.partition { it.severity == Severity.ERROR }
            if (warns.isNotEmpty()) {
                val label = if (warns.size > 1) "Warnings" else "Warning"
                echo("$YELLOW$label:$RESET\n")
                warns.forEach { echo(it.formatToConsole(), err = true) }
            }
            if (errors.isNotEmpty()) {
                val label = if (errors.size > 1) "Errors" else "Error"
                echo("$RED$label:$RESET\n")
                errors.forEach { echo(it.formatToConsole(), err = true) }
            }
            exitProcess(1)
        }
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        CompileCommand().main(args)
    }
}
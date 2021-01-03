package novah.main

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import java.io.File

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

    private val src by argument(help = "Directory containing source files").file(
        mustExist = true,
        canBeDir = true,
        canBeFile = false
    )

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
        val sources = src.walkBottomUp().filter { file -> file.isFile && file.extension == "novah" }
        if (verbose) echo("Compiling files at ${src.absolutePath}")
        Compiler.new(sources, verbose).run(out)
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        CompileCommand().main(args)
    }
}
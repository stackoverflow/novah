package novah.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import novah.cli.DepsProcessor
import novah.main.Compiler
import novah.main.Options
import java.io.File

class ApidocCommand : CliktCommand(name = "apidoc", help = "generate the api documentation for the project") {

    private val alias by option(
        "-a", "--alias",
        help = "Alias to turn on"
    )

    private val output by option(
        "-o", "--output",
        help = "Output directory to save the htmls (default: apidoc)"
    )

    override fun run() {
        val al = alias ?: DepsProcessor.defaultAlias

        val classpath = BuildCommand.getClasspath(al, "classpath", ::echo) ?: return
        val sourcepath = BuildCommand.getClasspath(al, "sourcepath", ::echo) ?: return

        val out = output ?: "apidoc"

        val compiler = Compiler.new(emptySequence(), classpath, sourcepath, Options())
        val outFile = File(out)
        if (!outFile.exists()) outFile.mkdirs()
        compiler.genApidocs(outFile)
    }
}
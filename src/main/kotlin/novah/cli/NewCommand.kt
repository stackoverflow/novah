package novah.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Paths

class NewCommand : CliktCommand(name = "new", help = "create a Novah project") {

    private val name by argument(help = "the name of the new project")

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Verbose output"
    ).flag(default = false)

    override fun run() {
        if (verbose) echo("creating project directories...")
        val projectDir = Paths.get(".", name)
        projectDir.toFile().mkdir()
        projectDir.resolve("src").toFile().mkdir()

        if (verbose) echo("creating deps file...")
        val project = projectDir.resolve("novah.json").toFile()
        project.writeText(defaultProjectJson, Charsets.UTF_8)
        if (verbose) echo("done!")
    }

    companion object {
        private val defaultProjectJson = """
            {
                "paths": ["src"],
                "deps": {}
            }
        """.trimIndent()
    }
}
package novah.cli

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File

data class MvnRepo(val url: String)

data class Coord(val version: String, val extension: String? = null, val exclusions: List<String>? = null)

data class Deps(
    val paths: List<String>,
    val deps: Map<String, Map<String, String>>,
    @JsonProperty("mvn/repos")
    val mvnRepos: Map<String, MvnRepo>?
)

class DepsCommand : CliktCommand(name = "deps", help = "fetch and store all dependencies for this project") {

    private val mapper = jacksonObjectMapper()

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Print information about the process"
    ).flag(default = false)

    override fun run() {
        val deps = readNovahFile()
        if (deps == null) {
            echo("No `novah.json` file found at the root of the project: exiting.")
            return
        }

        println(deps)
    }

    private fun readNovahFile(): Deps? {
        val file = File("./novah.json")
        if (!file.exists()) return null

        return try {
            val json = file.readText(Charsets.UTF_8)
            mapper.readValue<Deps>(json)
        } catch (e: Exception) {
            echo("There was an error processing the `novah.json` file. Make sure the file is valid", err = true)
            throw e
        }
    }

    private fun resolveDeps(deps: Map<String, Map<String, String>>) {

    }
}
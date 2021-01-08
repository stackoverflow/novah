package novah.main

import novah.frontend.typechecker.Environment
import novah.frontend.typechecker.FullModuleEnv
import java.io.File
import java.nio.file.Path

class Compiler(private val sources: Sequence<Entry>, private val verbose: Boolean) {

    fun compile(): Map<String, FullModuleEnv> {
        val env = Environment(verbose)
        return env.parseSources(sources)
    }

    fun run(output: File) {
        val env = Environment(verbose)
        // TODO: check errors
        env.parseSources(sources)

        env.generateCode(output)
    }

    companion object {
        fun new(sources: Sequence<Path>, verbose: Boolean): Compiler {
            val entries = sources.map { path -> Entry(path, path.toFile().readText(charset = Charsets.UTF_8)) }
            return Compiler(entries, verbose)
        }
    }

    data class Entry(val fileName: Path, val code: String)
}
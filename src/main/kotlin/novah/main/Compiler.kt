package novah.main

import java.io.File
import java.nio.file.Path

class Compiler(private val sources: Sequence<Source>, private val verbose: Boolean) {

    fun compile(): Map<String, FullModuleEnv> {
        val env = Environment(verbose)
        return env.parseSources(sources)
    }

    fun run(output: File, dryRun: Boolean = false) {
        val env = Environment(verbose)
        env.parseSources(sources)

        env.generateCode(output, dryRun)
    }

    companion object {
        fun new(sources: Sequence<Path>, verbose: Boolean): Compiler {
            val entries = sources.map { path -> Source.SPath(path) }
            return Compiler(entries, verbose)
        }
    }
}
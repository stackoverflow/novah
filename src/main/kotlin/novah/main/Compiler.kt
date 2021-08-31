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

import novah.data.BufferedCharIterator
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import java.io.File
import java.io.Reader
import java.nio.file.Files
import java.nio.file.Path

class Compiler(private val sources: Sequence<Source>, classpath: String?, sourcepath: String?, verbose: Boolean) {

    private val env = Environment(classpath, sourcepath, verbose)

    fun compile(): Map<String, FullModuleEnv> = env.parseSources(sources)

    fun run(output: File, dryRun: Boolean = false): Set<CompilerProblem> {
        env.parseSources(sources)
        env.generateCode(output, dryRun)
        return env.errors()
    }

    fun errors() = env.errors()

    fun getModules() = env.modules()

    companion object {
        fun new(sources: Sequence<Path>, classpath: String?, sourcepath: String?, verbose: Boolean): Compiler {
            val entries = sources.map { path -> Source.SPath(path) }
            return Compiler(entries, classpath, sourcepath, verbose)
        }
        
        fun printWarnings(warns: Set<CompilerProblem>, echo: (String) -> Unit) {
            if (warns.isNotEmpty()) {
                val label = if (warns.size > 1) "Warnings" else "Warning"
                echo("${CompilerProblem.YELLOW}$label:${CompilerProblem.RESET}\n")
                warns.forEach { echo(it.formatToConsole()) }
            }
        }
        
        fun printErrors(errors: Set<CompilerProblem>, echo: (String, Boolean) -> Unit) {
            val (errs, warns) = errors.partition { it.severity == Severity.ERROR }
            if (warns.isNotEmpty()) {
                val label = if (warns.size > 1) "Warnings" else "Warning"
                echo("${CompilerProblem.YELLOW}$label:${CompilerProblem.RESET}\n", false)
                warns.forEach { echo(it.formatToConsole(), false) }
            }
            if (errs.isNotEmpty()) {
                val label = if (errs.size > 1) "Errors" else "Error"
                echo("${CompilerProblem.RED}$label:${CompilerProblem.RESET}\n", true)
                errs.forEach { echo(it.formatToConsole(), true) }
            }
        }
    }
}

sealed class Source(val path: Path) {
    class SPath(path: Path) : Source(path)
    class SString(path: Path, val str: String) : Source(path)
    class SReader(path: Path, val reader: Reader) : Source(path)

    fun withIterator(action: (Iterator<Char>) -> Unit): Unit = when (this) {
        is SPath -> Files.newBufferedReader(path, Charsets.UTF_8).use {
            action(BufferedCharIterator(it))
        }
        is SString -> action(str.iterator())
        is SReader -> reader.use { action(BufferedCharIterator(it)) }
    }
}
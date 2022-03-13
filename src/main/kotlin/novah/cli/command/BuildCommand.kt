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
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import novah.cli.Deps
import novah.cli.DepsProcessor
import novah.data.Err
import novah.main.CompilationError
import novah.main.Compiler
import novah.main.Options
import java.io.File
import kotlin.system.exitProcess

class BuildCommand : CliktCommand(name = "build", help = "compile the project described by the `novah.json` file") {

    private val alias by option(
        "-a", "--alias",
        help = "Alias to turn on"
    )

    private val check by option(
        "-c", "--check",
        help = "just check the code for errors, but don't generate code"
    ).flag(default = false)

    private val verbose by option(
        "-v", "--verbose",
        help = "print information about the process"
    ).flag(default = false)

    private val devMode by option(
        "-d", "--dev",
        help = "run the compiler in dev mode: no optimizations will be applied and some errors will be warnings."
    ).flag(default = false)

    override fun run() {
        val depsRes = DepsProcessor.readNovahFile()
        if (depsRes is Err) {
            echo(depsRes.err, false)
            return
        }
        val deps = depsRes.unwrap()
        val al = alias ?: DepsProcessor.defaultAlias

        build(al, deps, verbose, devMode, check, ::echo, ::echo)
    }

    companion object {

        fun build(
            alias: String,
            deps: Deps,
            verbose: Boolean,
            devMode: Boolean,
            check: Boolean,
            echo: (String) -> Unit,
            echoErr: (String, Boolean) -> Unit
        ) {
            val classpath = getClasspath(alias, "classpath", echoErr) ?: return
            val sourcepath = getClasspath(alias, "sourcepath", echoErr) ?: return

            val out = deps.output ?: DepsProcessor.defaultOutput

            val javaPaths = File(".cpcache/$alias.javasourcepath")
            if (javaPaths.exists()) {
                val paths = javaPaths.readText(Charsets.UTF_8).split(File.pathSeparator).toSet()
                if (paths.isNotEmpty()) {
                    if (!runJavac(paths, alias, out)) {
                        echoErr("Failed to compile java sources", true)
                        exitProcess(-1)
                    }
                }
            }

            val compiler = Compiler.new(emptySequence(), classpath, sourcepath, Options(verbose, devMode))
            try {
                val warns = compiler.run(File(out), check)
                Compiler.printWarnings(warns, echo)
                echo("Success")
            } catch (_: CompilationError) {
                val allErrs = compiler.errors()
                Compiler.printErrors(allErrs, echoErr)
                echoErr("Failure", true)
                exitProcess(1)
            }
        }

        private fun runJavac(paths: Set<String>, alias: String, out: String): Boolean {
            val argsfile = File(".cpcache/$alias.argsfile")

            val sources = mutableSetOf<String>()
            paths.forEach { path ->
                File(path).walkTopDown().forEach { file ->
                    if (file.extension == "java") {
                        sources += file.absolutePath
                    }
                }
            }
            if (sources.isEmpty()) return true

            val srcFile = File.createTempFile("novahsources", null)
            srcFile.writeText(sources.joinToString(" "))

            val cmd = "javac @${argsfile.path} -d $out @${srcFile.path}"
            val exit = RunCommand.runCommand(cmd)
            return exit == 0
        }

        private fun getClasspath(alias: String, type: String, echo: (String, Boolean) -> Unit): String? {
            val cp = File(".cpcache/$alias.$type")
            if (!cp.exists()) {
                if (alias == DepsProcessor.defaultAlias) {
                    echo("No classpath found. Run the `deps` command first to generate a classpath", true)
                } else {
                    echo(
                        "No classpath found for alias $alias. Run the `deps` command first to generate a classpath",
                        true
                    )
                }
                return null
            }

            return cp.readText(Charsets.UTF_8)
        }
    }
}
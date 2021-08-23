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

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import novah.cli.DepsProcessor
import novah.cli.DepsProcessor.Companion.defaultAlias
import novah.data.Err
import java.io.File
import kotlin.system.exitProcess

class RunCommand : CliktCommand(name = "run", help = "Run the main module if one is defined") {

    private val mapper = jacksonObjectMapper()

    private val alias by option(
        "-a",
        "--alias",
        help = "Alias to turn on"
    )

    private val main by option(
        "-m",
        "--main",
        help = "Use a different main function instead of the one defined in the project file"
    )

    private val args by option("-p", "--pars", help = "Parameters to be passed to JVM").multiple()

    override fun run() {
        val depsRes = DepsProcessor.readNovahFile(mapper)
        if (depsRes is Err) {
            echo(depsRes.err)
            return
        }
        val deps = depsRes.unwrap()

        val mainFun = when {
            main != null -> main
            alias == null -> deps.main
            else -> deps.aliases?.get(alias)?.main
        }
        if (mainFun == null) {
            if (alias == null) echo("Could not find main definition in project file")
            else echo("Could not find main definition for alias `$alias` in project file")
            return
        }

        val al = alias ?: defaultAlias
        val argsfile = File(".cpcache/$al.argsfile")
        if (!argsfile.exists()) {
            if (alias == null) {
                echo("The project is not built. Build it with: `novah deps build`", err = true)
            } else {
                echo(
                    "The project is not built for alias $alias. Build it with: `novah deps build -a $alias`",
                    err = true
                )
            }
            return
        }

        var cmd = "java @${argsfile.path} $mainFun.Module"
        if (args.isNotEmpty()) cmd = args.joinToString(" ", prefix = "$cmd ")
        runCommand(cmd)
    }

    private fun runCommand(command: String) {
        val parts = command.split("\\s".toRegex())
        val process = ProcessBuilder(*parts.toTypedArray())
            .directory(null)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()

        process.waitFor()
        exitProcess(process.exitValue())
    }
}
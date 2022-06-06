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

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import novah.cli.command.*
import kotlin.system.exitProcess

class MainCommand : CliktCommand(name = "novah", printHelpOnEmptyArgs = true, invokeWithoutSubcommand = true) {

    private val version by option("-v", "--version",
        help = "show the current version and exit"
    ).flag()

    override fun run() {
        if (version) {
            echo("Novah compiler $VERSION")
            exitProcess(0)
        }
    }

    companion object {
        private const val VERSION = "0.3.0"
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val comms = arrayOf(
            CompileCommand(),
            DepsCommand(),
            BuildCommand(),
            NewCommand(),
            RunCommand(),
            ClearCommand(),
            ApidocCommand(),
            IdeCommand()
        )
        MainCommand().subcommands(*comms).main(args)
    }
}
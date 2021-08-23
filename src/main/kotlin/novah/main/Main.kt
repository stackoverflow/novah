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
import novah.cli.command.*

class MainCommand : CliktCommand(name = "") {
    override fun run() {
    }
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        val depsCommand = DepsCommand().subcommands(BuildCommand())
        val comms = arrayOf(CompileCommand(), depsCommand, NewCommand(), RunCommand(), IdeCommand())
        MainCommand().subcommands(*comms).main(args)
    }
}
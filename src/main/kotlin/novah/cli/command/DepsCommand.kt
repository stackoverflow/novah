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
import novah.cli.DepsProcessor
import novah.data.Err

class DepsCommand : CliktCommand(
    name = "deps",
    help = "fetch and store all dependencies for this project",
    invokeWithoutSubcommand = true
) {

    private val verbose by option(
        "-v", "--verbose",
        help = "print information about the process"
    ).flag(default = false)

    override fun run() {
        val depsRes = DepsProcessor.readNovahFile()
        if (depsRes is Err) {
            echo(depsRes.err, false)
            return
        }
        val processor = DepsProcessor(verbose) { msg, err -> echo(msg, err = err) }
        val deps = depsRes.unwrap()
        processor.run(deps)
    }
}
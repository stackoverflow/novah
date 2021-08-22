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
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.nio.file.Paths

class NewCommand : CliktCommand(name = "new", help = "Create a Novah project") {

    private val name by argument(help = "The name of the new project")

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
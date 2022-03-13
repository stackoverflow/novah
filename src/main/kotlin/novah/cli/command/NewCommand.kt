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
import java.io.File

class NewCommand : CliktCommand(name = "new", help = "create a Novah project") {

    private val name by argument(help = "the name of the new project")

    private val empty by option(
        "-e", "--empty",
        help = "don't create sample source and test file"
    ).flag(default = false)

    private val verbose by option(
        "-v", "--verbose",
        help = "verbose output"
    ).flag(default = false)

    override fun run() {
        if (verbose) echo("creating project directories...")
        val projectDir = File(name)
        projectDir.mkdir()

        val srcDir = projectDir.resolve("src")
        srcDir.mkdir()
        var testDir = projectDir.resolve("test")
        testDir.mkdir()

        if (!empty) {
            testDir = testDir.resolve("test")
            testDir.mkdir()
            srcDir.resolve("main.novah").writeText(mainFile(), Charsets.UTF_8)
            testDir.resolve("main.novah").writeText(testFile(), Charsets.UTF_8)
        }

        val gitignore = projectDir.resolve(".gitignore")
        gitignore.writeText(gitignore(), Charsets.UTF_8)

        if (verbose) echo("creating deps file...")
        val project = projectDir.resolve("novah.json")
        project.writeText(defaultProjectJson(), Charsets.UTF_8)

        echo("Project created!")
        echo("To fetch all dependencies run `novah deps` or `novah deps -a <alias>` for a specific alias")
        echo("To build the project run `novah deps build` or `novah deps build -a <alias>` for a specific alias")
        echo("To run the project run `novah run` or `novah run -a test` for tests")
    }

    companion object {
        private fun defaultProjectJson() = """
            {
                "paths": ["src"],
                "deps": {},
                "main": "main",
                "aliases": {
                    "test": {
                        "extraPaths": ["test"],
                        "main": "test.main"
                    }
                }
            }
        """.trimIndent()

        private fun mainFile() = """
            module main
            
            pub
            main : Array String -> Unit
            main _ =
              println "Hello Novah!"
        """.trimIndent()

        private fun testFile() = """
            module test.main
            
            import novah.test
            
            myTest : Suite
            myTest =
              Test "A simple test" \_ ->
                (1 + 1) `shouldBe` 2
            
            pub
            main : Array String -> Unit
            main _ =
              runTests [myTest]
              ()
        """.trimIndent()

        private fun gitignore() = """
            .cpcache/
            output/
        """.trimIndent()
    }
}
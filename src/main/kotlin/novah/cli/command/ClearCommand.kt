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
import novah.cli.DepsProcessor
import novah.data.Err
import java.io.File

class ClearCommand : CliktCommand(name = "clear", help = "Clear the output directory") {

    private val mapper = jacksonObjectMapper()
    
    override fun run() {
        val depsRes = DepsProcessor.readNovahFile(mapper)
        if (depsRes is Err) {
            echo(depsRes.err)
            return
        }
        val deps = depsRes.unwrap()
        
        val out = deps.output ?: DepsProcessor.defaultOutput
        val file = File(out)
        if (file.deleteRecursively()) {
            file.mkdir()
        } else {
            echo("Failed to delete $out directory")
        }
    }
}
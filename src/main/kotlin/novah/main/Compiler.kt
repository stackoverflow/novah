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

import novah.frontend.error.CompilerProblem
import java.io.File
import java.nio.file.Path

class Compiler(private val sources: Sequence<Source>, classPath: String?, verbose: Boolean) {

    private val env = Environment(classPath, verbose)

    fun compile(): Map<String, FullModuleEnv> = env.parseSources(sources)

    fun run(output: File, dryRun: Boolean = false): List<CompilerProblem> {
        env.parseSources(sources)
        env.generateCode(output, dryRun)
        return env.getWarnings()
    }
    
    fun getWarnings() = env.getWarnings()
    
    fun getModules() = env.modules()

    companion object {
        fun new(sources: Sequence<Path>, classPath: String?, verbose: Boolean): Compiler {
            val entries = sources.map { path -> Source.SPath(path) }
            return Compiler(entries, classPath, verbose)
        }
    }
}
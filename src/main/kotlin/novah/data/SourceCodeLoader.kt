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
package novah.data

import novah.main.Source
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * Class responsible for finding novah source files
 * in the class path (jars, and directories).
 * Jars are not considered at the moment
 */
class SourceCodeLoader(private val classpath: String?) {

    fun loadSources(): Sequence<Source> {
        if (classpath == null) return emptySequence()

        val sep = System.getProperty("path.separator")
        return classpath.split(sep).asSequence().flatMap(::processEntry)
    }

    private fun processEntry(path: String): Sequence<Source> {
        return if (path.endsWith(".jar")) {
            //loadSourcesFromJar(path)
            emptySequence()
        } else {
            loadSourcesFromDir(path)
        }
    }

    private fun loadSourcesFromJar(jar: String): Sequence<Source> {
        val file = JarFile(jar)
        return file.entries().asSequence().filter { it.name.endsWith(".novah") }.map {
            val input = file.getInputStream(it)
            Source.SReader(Path.of(it.name), BufferedReader(InputStreamReader(input)))
        }
    }

    private fun loadSourcesFromDir(path: String): Sequence<Source> {
        val dir = File(path)
        if (!dir.isDirectory) return emptySequence()

        return dir.walkTopDown().filter { it.isFile && it.extension == "novah" }.map {
            Source.SPath(it.toPath())
        }
    }
}
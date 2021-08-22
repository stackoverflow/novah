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
package novah.cli

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import novah.cli.maven.Maven
import novah.data.Err
import novah.data.Ok
import java.io.File

data class MvnRepo(val url: String)

data class Coord(
    @JsonProperty("mvn/version") val version: String,
    val exclusions: List<String>? = null
)

data class Alias(val extraPaths: List<String>?, val extraDeps: Map<String, Coord>?)

data class Deps(
    val paths: List<String>,
    val deps: Map<String, Coord>,
    @JsonProperty("mvn/repos") val mvnRepos: Map<String, MvnRepo>?,
    val aliases: Map<String, Alias>?
)

class DepsProcessor(private val echo: (String) -> Unit, private val err: (String) -> Unit) {

    private val mapper = jacksonObjectMapper()

    fun run() {
        val cache = ensureCache() ?: return
        if (!isResolveNeeded(cache)) {
            echo("No changes since last run: skipping")
            return
        }

        val deps = readNovahFile()
        if (deps == null) {
            echo("No `novah.json` file found at the root of the project: exiting.")
            return
        }

        // resolve top level deps
        val srccp = deps.paths.joinToString(File.pathSeparator) { File(it).absolutePath }
        val mvncp = resolveDeps(null, deps.deps, deps.mvnRepos) ?: return
        saveClasspath("\$default", mvncp)
        saveSourcepath("\$default", srccp)

        // resolve alias deps
        if (deps.aliases != null) {
            deps.aliases.forEach { (name, alias) ->
                runAlias(deps, name, alias, mvncp)
            }
        }
    }

    private fun readNovahFile(): Deps? {
        val file = File("./novah.json")
        if (!file.exists()) return null

        return try {
            val json = file.readText(Charsets.UTF_8)
            val deps = mapper.readValue<Deps>(json)
            if (deps.paths.isEmpty()) {
                err("ERROR: No source paths defined for project")
                return null
            }
            for (path in deps.paths) {
                if (path == "\$default") {
                    err("Invalid source path name: \$default")
                    return null
                }
            }
            deps
        } catch (e: Exception) {
            err("There was an error parsing the `novah.json` file. Make sure the file is valid")
            null
        }
    }

    private fun runAlias(deps: Deps, name: String, alias: Alias, mvncp: String) {
        if (alias.extraPaths == null && alias.extraDeps == null) return

        val allPaths = deps.paths + (alias.extraPaths ?: listOf())
        val srccp = allPaths.joinToString(File.pathSeparator) { File(it).absolutePath }
        saveSourcepath(name, srccp)
        if (alias.extraDeps == null || alias.extraDeps.isEmpty()) {
            // no need to resolve dependencies
            saveClasspath(name, mvncp)
        } else {
            val deps2 = mutableMapOf<String, Coord>()
            deps2.putAll(deps.deps)
            deps2.putAll(alias.extraDeps)
            val mvncp2 = resolveDeps(name, deps2, deps.mvnRepos) ?: return
            saveClasspath(name, mvncp2)
        }
    }

    private fun resolveDeps(alias: String?, deps: Map<String, Coord>, repos: Map<String, MvnRepo>?): String? {
        if (alias != null) echo("resolving dependencies for alias $alias")
        else echo("Resolving dependencies")

        return when (val res = Maven.resolveDeps(deps, repos, alias)) {
            is Ok -> {
                echo("Generating classpath file")
                when (val mvncp = Maven.makeClasspath(res.value)) {
                    is Ok -> mvncp.value
                    is Err -> {
                        err(mvncp.err)
                        null
                    }
                }
            }
            is Err -> {
                err("Could not resolve dependencies: ${res.err.message}")
                null
            }
        }
    }

    private fun saveClasspath(alias: String, classpath: String) {
        File(".cpcache/$alias.classpath").writeText(classpath, Charsets.UTF_8)
    }

    private fun saveSourcepath(alias: String, sourcepath: String) {
        File(".cpcache/$alias.sourcepath").writeText(sourcepath, Charsets.UTF_8)
    }

    private fun isResolveNeeded(cacheDir: File): Boolean {
        val novah = File("./novah.json")
        return novah.lastModified() > getCacheLastModified(cacheDir)
    }

    private fun getCacheLastModified(cacheDir: File): Long {
        val files = cacheDir.listFiles() ?: return 0L
        // take the oldest file in the cache to compare
        return files.minOf { it.lastModified() }
    }

    private fun ensureCache(): File? {
        val cacheDir = File(".cpcache")
        if (!cacheDir.exists()) {
            val success = cacheDir.mkdir()
            if (!success) {
                err("Failed to create classpath cache directory `.cpcache`")
                return null
            }
        }
        if (!cacheDir.isDirectory) {
            val success = cacheDir.delete()
            if (!success) {
                err("Failed to delete classpath cache `.cpcache`")
                return null
            }
            cacheDir.mkdir()
        }
        return cacheDir
    }
}
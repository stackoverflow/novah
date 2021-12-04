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
import novah.data.Result
import java.io.File

data class MvnRepo(val url: String)

data class Coord(
    @JsonProperty("mvn/version") val version: String?,
    val exclusions: List<String>?,
    @JsonProperty("git/version") val gitVersion: String?,
) {
    fun isValid() = (version != null) xor (gitVersion != null)

    fun isMaven() = version != null
}

data class Alias(val extraPaths: Set<String>?, val extraDeps: Map<String, Coord>?, val main: String?)

data class Deps(
    val paths: Set<String>,
    val deps: Map<String, Coord>,
    val javaPaths: Set<String>?,
    @JsonProperty("mvn/repos") val mvnRepos: Map<String, MvnRepo>?,
    val aliases: Map<String, Alias>?,
    val main: String?,
    val output: String?
)

class DepsProcessor(private val verbose: Boolean, private val echo: (String, Boolean) -> Unit) {

    fun run(deps: Deps) {
        val cache = ensureCache() ?: return
        if (!isResolveNeeded(cache)) {
            echo("Skipping dependency resolution", false)
            return
        }
        deleteOldAliases(cache)

        val out = deps.output ?: defaultOutput
        // resolve top level deps
        val (mvncp, spaths, jspaths) = resolveDeps(null, deps.deps, deps.mvnRepos) ?: return
        val srccp = deps.paths.map { File(it).absolutePath } + spaths
        val jsrccp = (deps.javaPaths ?: setOf()).map { File(it).absolutePath } + jspaths

        saveClasspath(defaultAlias, out, mvncp)
        saveSourcepath(defaultAlias, srccp.joinToString(File.pathSeparator))
        if (jsrccp.isNotEmpty())
            saveJavaSourcepath(defaultAlias, jsrccp.joinToString(File.pathSeparator))
        saveArgsfile(defaultAlias, out, mvncp)

        // resolve alias deps
        deps.aliases?.forEach { (name, alias) ->
            runAlias(deps, name, alias, mvncp, srccp.toSet(), jsrccp.toSet(), out)
        }
    }

    private fun runAlias(
        deps: Deps,
        name: String,
        alias: Alias,
        mvncp: String,
        srcPaths: Set<String>,
        jsrcPaths: Set<String>,
        out: String
    ) {
        if (alias.extraPaths == null && alias.extraDeps == null) return

        var allPaths = srcPaths + (alias.extraPaths ?: listOf()).map { File(it).absolutePath }
        if (alias.extraDeps == null || alias.extraDeps.isEmpty()) {
            // no need to resolve dependencies
            saveClasspath(name, out, mvncp)
            saveArgsfile(name, out, mvncp)
            saveSourcepath(name, allPaths.joinToString(File.pathSeparator))
            if (jsrcPaths.isNotEmpty())
                saveJavaSourcepath(name, jsrcPaths.joinToString(File.pathSeparator))
        } else {
            val deps2 = mutableMapOf<String, Coord>()
            deps2.putAll(deps.deps)
            deps2.putAll(alias.extraDeps)
            val (mvncp2, spaths, jspaths) = resolveDeps(name, deps2, deps.mvnRepos) ?: return
            saveClasspath(name, out, mvncp2)
            saveArgsfile(name, out, mvncp2)

            allPaths = allPaths + spaths
            saveSourcepath(name, allPaths.joinToString(File.pathSeparator))

            val javapaths = jsrcPaths + jspaths
            if (javapaths.isNotEmpty())
                saveJavaSourcepath(name, javapaths.joinToString(File.pathSeparator))
        }
    }

    private data class Paths(val mavencp: String, val sourcepaths: Set<String>, val javasourcepaths: Set<String>)

    private fun resolveDeps(alias: String?, deps: Map<String, Coord>, repos: Map<String, MvnRepo>?): Paths? {
        if (alias != null) log("resolving dependencies for alias $alias")
        else log("Resolving dependencies")

        try {
            val (mvndeps, gitdeps) = collectDeps(deps)

            return when (val res = Maven.resolveDeps(mvndeps, repos, alias)) {
                is Ok -> {
                    log("Generating classpath file")
                    when (val mvncp = Maven.makeClasspath(res.value)) {
                        is Ok -> {
                            val (spaths, jspaths) = Git.makeSourcepaths(gitdeps)
                            Paths(mvncp.value, spaths, jspaths)
                        }
                        is Err -> {
                            echo(mvncp.err, true)
                            null
                        }
                    }
                }
                is Err -> {
                    echo("Could not resolve dependencies: ${res.err.message}", true)
                    null
                }
            }
        } catch (re: RuntimeException) {
            echo(re.message!!, true)
            return null
        }
    }

    // Recursively resolve git dependencies and collect the maven ones
    private fun collectDeps(
        deps: Map<String, Coord>,
        seen: MutableSet<String> = mutableSetOf()
    ): Pair<Map<String, Coord>, List<GitDeps>> {
        val maven = mutableMapOf<String, Coord>()
        val git = mutableListOf<GitDeps>()

        for ((dep, version) in deps) {
            if (version.isMaven()) maven[dep] = version
            else {
                seen += dep
                val folderRes = Git.fetchDependency(dep, version.gitVersion!!)
                if (folderRes is Ok) {
                    val folder = folderRes.unwrap()
                    val deps2 = readDeps(folder)
                    if (deps2 != null) {
                        git += GitDeps(folder, deps2)
                        val (mvns, gits) = collectDeps(deps2.deps, seen)
                        maven.putAll(mvns)
                        git.addAll(gits)
                    } else throw RuntimeException("Could not read deps file for dependency $dep")
                } else throw RuntimeException("Could not fetch git dependency")
            }
        }

        return maven to git
    }

    private fun readDeps(folder: File): Deps? {
        val file = folder.resolve("novah.json")
        if (!file.exists()) return null
        return when (val res = readNovahFile(file)) {
            is Ok -> res.value
            is Err -> null
        }
    }

    private fun saveClasspath(alias: String, out: String, classpath: String) {
        val cp = if (classpath.isBlank()) out else out + File.pathSeparator + classpath
        File(".cpcache/$alias.classpath").writeText(cp, Charsets.UTF_8)
    }

    private fun saveSourcepath(alias: String, sourcepath: String) {
        File(".cpcache/$alias.sourcepath").writeText(sourcepath, Charsets.UTF_8)
    }

    private fun saveJavaSourcepath(alias: String, sourcepath: String) {
        File(".cpcache/$alias.javasourcepath").writeText(sourcepath, Charsets.UTF_8)
    }

    private fun saveArgsfile(alias: String, out: String, classpath: String) {
        val cp = if (classpath.isBlank()) out else out + File.pathSeparator + classpath
        File(".cpcache/$alias.argsfile").writeText("-cp $cp", Charsets.UTF_8)
    }

    private fun isResolveNeeded(cacheDir: File): Boolean {
        val novah = File("./novah.json")
        return novah.lastModified() > getCacheLastModified(cacheDir)
    }

    private fun getCacheLastModified(cacheDir: File): Long {
        val files = cacheDir.listFiles() ?: return 0L
        if (files.isEmpty()) return 0L
        // take the oldest file in the cache to compare
        return files.minOf { it.lastModified() }
    }

    private fun ensureCache(): File? {
        val cacheDir = File(".cpcache")
        if (!cacheDir.exists()) {
            val success = cacheDir.mkdir()
            if (!success) {
                echo("Failed to create classpath cache directory `.cpcache`", true)
                return null
            }
        }
        if (!cacheDir.isDirectory) {
            val success = cacheDir.delete()
            if (!success) {
                echo("Failed to delete classpath cache `.cpcache`", true)
                return null
            }
            cacheDir.mkdir()
        }
        return cacheDir
    }

    private fun deleteOldAliases(cacheDir: File) {
        val files = cacheDir.listFiles() ?: return
        files.filter(File::isFile).forEach(File::delete)
    }

    private fun log(msg: String, err: Boolean = false) {
        if (verbose) echo(msg, err)
    }

    companion object {

        const val defaultAlias = "default"
        const val defaultOutput = "output"

        private val mapper = jacksonObjectMapper()

        fun readNovahFile(file: File = File("./novah.json")): Result<Deps, String> {
            if (!file.exists()) return Err("No `novah.json` file found at the root of the project: exiting")

            return try {
                val json = file.readText(Charsets.UTF_8)
                val deps = mapper.readValue<Deps>(json)
                if (deps.paths.isEmpty()) {
                    return Err("No source paths defined for project")
                }
                for ((name, _) in deps.aliases ?: emptyMap()) {
                    if (name == defaultAlias)
                        return Err("Invalid alias name $name")
                }
                for (path in deps.paths) {
                    if (path == defaultAlias) {
                        return Err("Invalid source path name: $defaultAlias")
                    }
                }
                for (entry in deps.deps) {
                    if (!entry.value.isValid()) {
                        return Err("A dependency requires either a maven version or a git version (but not both")
                    }
                }
                Ok(deps)
            } catch (_: Exception) {
                Err("There was an error parsing the `novah.json` file. Make sure the file is valid")
            }
        }
    }
}

data class GitDeps(val folder: File, val deps: Deps)
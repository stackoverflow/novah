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
package novah.cli.maven

import novah.cli.Coord
import novah.cli.MvnRepo
import novah.data.Err
import novah.data.Ok
import novah.data.Result
import org.apache.maven.settings.Settings
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.resolution.DependencyResult
import java.io.File

object Maven {
    
    fun resolveDeps(
        deps: Map<String, Coord>,
        repos: Map<String, MvnRepo>?,
        scope: String?
    ): Result<DependencyResult, DependencyResolutionException> {
        val system = MvnUtil.retrieve("system", MvnUtil::makeSystem)!!
        val settings = MvnUtil.retrieve("settings", MvnUtil::getSettings)!!
        val session = MvnUtil.retrieve("session") {
            MvnUtil.makeSession(system, settings, MvnUtil.localRepo)
        }
        val req = makeRequest(deps, repos, settings, scope)
        return try {
            Ok(system.resolveDependencies(session, req))
        } catch (e: DependencyResolutionException) {
            Err(e)
        }
    }

    fun makeClasspath(res: DependencyResult): Result<String, String> {
        val exc = res.collectExceptions
        if (exc.isNotEmpty()) {
            val err = exc.joinToString("\n") { "could not resolve dependency: ${it.message}" }
            return Err(err)
        }
        val cy = res.cycles
        if (cy.isNotEmpty()) {
            val str = StringBuilder()
            str.append("could not finish fetching dependencies: cycle found")
            cy.forEach { cycle ->
                val report = cycle.cyclicDependencies.joinToString(" -> ") {
                    it.artifact.groupId + ":" + it.artifact.artifactId
                }
                str.append("\ncycle found between $report")
            }
            return Err(str.toString())
        }

        return Ok(res.artifactResults.joinToString(File.pathSeparator) { it.artifact.file.absolutePath })
    }

    private fun makeRequest(
        deps: Map<String, Coord>,
        repos: Map<String, MvnRepo>?,
        settings: Settings,
        scope: String?
    ): DependencyRequest {
        val depList = deps.map { (lib, coord) ->
            val dep = Dependency(MvnUtil.coordToArtifact(lib, coord.version), MvnUtil.getScope(scope))
            val exs = coord.exclusions
            if (exs != null && exs.isNotEmpty()) {
                dep.exclusions = exs.map { MvnUtil.coordToExclusion(it) }
            }
            dep
        }
        val remRepos = MvnUtil.remoteRepos(repos, settings)

        val collectReq = CollectRequest(depList, null, remRepos)
        return DependencyRequest(collectReq, null)
    }
}
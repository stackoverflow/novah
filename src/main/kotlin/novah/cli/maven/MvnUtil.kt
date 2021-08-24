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

import com.github.ajalt.clikt.output.TermUi.echo
import novah.cli.MvnRepo
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.apache.maven.settings.DefaultMavenSettingsBuilder
import org.apache.maven.settings.Server
import org.apache.maven.settings.Settings
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory
import org.codehaus.plexus.util.xml.Xpp3Dom
import org.eclipse.aether.ConfigurationProperties
import org.eclipse.aether.DefaultRepositoryCache
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Exclusion
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.Proxy
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferListener
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.DefaultMirrorSelector
import org.eclipse.aether.util.repository.DefaultProxySelector
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object MvnUtil {

    @Suppress("UNCHECKED_CAST")
    fun <T> retrieve(key: String, compute: (() -> T)?): T? {
        return if (compute == null) {
            session[key] as T
        } else {
            session.computeIfAbsent(key) { compute() as Any } as T
        }
    }

    fun makeSystem(): RepositorySystem? {
        return locator.getService(RepositorySystem::class.java)
    }

    fun getSettings(): Settings {
        val defBuilder = DefaultMavenSettingsBuilder()
        val settings = DefaultSettingsBuilderFactory().newInstance()
        val field = defBuilder::class.java.getDeclaredField("settingsBuilder")
        field.isAccessible = true
        field.set(defBuilder, settings)
        return defBuilder.buildSettings()
    }

    fun makeSession(system: RepositorySystem, settings: Settings, localRepo: String): DefaultRepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepoMgr = system.newLocalRepositoryManager(session, LocalRepository(localRepo))
        session.localRepositoryManager = localRepoMgr
        session.transferListener = ConsoleListener()
        session.cache = DefaultRepositoryCache()
        settings.servers.forEach { addServerConfig(session, it) }
        return session
    }

    fun coordToArtifact(lib: String, version: String?): Artifact {
        val names = libToNames(lib)
        val ver = version ?: "LATEST"
        return DefaultArtifact(names[0], names[1], names.getOrNull(2), "jar", ver)
    }

    fun coordToExclusion(lib: String): Exclusion {
        val names = libToNames(lib)
        return Exclusion(names[0], names[1], names.getOrNull(2), "jar")
    }

    fun remoteRepos(repos: Map<String, MvnRepo>?, settings: Settings): List<RemoteRepository> {
        if (repos == null) return listOf(centralRepo)
        return repos.toList().sortedWith { a, b ->
            when {
                a.first == "central" -> -1
                b.first == "central" -> 1
                else -> a.first.compareTo(b.first)
            }
        }.mapNotNull { (name, url) -> remoteRepo(name, url, settings) }
    }

    fun getScope(scope: String?): String {
        if (scope == null) return defaultScope
        return if (scope in scopes) scope else defaultScope
    }

    val localRepo: String by lazy {
        val path = Paths.get(System.getProperty("user.home"), ".m2", "repository")
        path.toFile().absolutePath
    }

    private val scopes = setOf("compile", "provided", "runtime", "test")

    private const val defaultScope = "compile"

    private val locator: DefaultServiceLocator by lazy {
        val loc = MavenRepositorySystemUtils.newServiceLocator()
        loc.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        loc.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        loc.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        loc
    }

    private val session = ConcurrentHashMap<String, Any>()

    private fun remoteRepo(repoName: String, url: MvnRepo, settings: Settings): RemoteRepository? {
        val builder = RemoteRepository.Builder(repoName, "default", url.url)
        val repo = builder.build()
        val mirror = selectMirror(settings, repo)
        val remoteRepo = mirror ?: repo
        val proxy = selectProxy(settings, remoteRepo)
        val serverId = remoteRepo.id
        val serverSetting = settings.servers.find { it.id == serverId }

        if (mirror != null) builder.setUrl(mirror.url)
        if (serverSetting != null) {
            val auth = AuthenticationBuilder().apply {
                addUsername(serverSetting.username)
                addPassword(serverSetting.password)
                addPrivateKey(serverSetting.privateKey, serverSetting.passphrase)
            }.build()
            builder.setAuthentication(auth)
        }
        if (proxy != null) builder.setProxy(proxy)
        return builder.build()
    }

    private fun selectMirror(settings: Settings, repo: RemoteRepository): RemoteRepository? {
        val mirrors = settings.mirrors
        val selector = DefaultMirrorSelector()
        mirrors.forEach { mirror ->
            selector.add(mirror.id, mirror.url, mirror.layout, false, false, mirror.mirrorOf, mirror.mirrorOfLayouts)
        }
        return selector.getMirror(repo)
    }

    private fun selectProxy(settings: Settings, repo: RemoteRepository): Proxy? {
        return settings.proxies.find { it.isActive }?.let { proxySetting ->
            val proxySel = DefaultProxySelector()
            val auth = AuthenticationBuilder().apply {
                addUsername(proxySetting.username)
                addPassword(proxySetting.password)
            }.build()
            val proxy = Proxy(proxySetting.protocol, proxySetting.host, proxySetting.port, auth)

            proxySel.add(proxy, proxySetting.nonProxyHosts)
            proxySel.getProxy(repo)
        }
    }

    private fun libToNames(lib: String): List<String> {
        val (group, art) = lib.split("/")
        val artclass = art.split(Regex("""\$"""))
        return if (artclass.size > 1) listOf(group, artclass[0], artclass[1])
        else listOf(group, art)
    }

    private val centralRepo: RemoteRepository by lazy {
        val settings = retrieve("settings", ::getSettings)!!
        remoteRepo("central", MvnRepo("https://repo1.maven.org/maven2/"), settings)!!
    }

    private fun addServerConfig(session: DefaultRepositorySystemSession, server: Server) {
        val config = server.configuration as? Xpp3Dom ?: return
        val headers = config.getChild("httpHeaders") ?: return
        val headerMap = mutableMapOf<String, String>()
        headers.getChildren("property").forEach { header ->
            val name = header.getChild("name")
            val v = header.getChild("value")
            if (name != null && v != null) headerMap[name.value] = v.value
        }
        session.setConfigProperty("${ConfigurationProperties.HTTP_HEADERS}.${server.id}", headerMap)
    }

    private class ConsoleListener : TransferListener {
        override fun transferInitiated(event: TransferEvent?) {
        }

        override fun transferStarted(event: TransferEvent) {
            val res = event.resource
            echo("downloading ${res.resourceName} from ${res.repositoryId}")
        }

        override fun transferProgressed(event: TransferEvent?) {
        }

        override fun transferCorrupted(event: TransferEvent) {
            echo("download corrupted: ${event.exception.message}", err = true)
        }

        override fun transferSucceeded(event: TransferEvent?) {
        }

        override fun transferFailed(event: TransferEvent?) {
        }
    }
}
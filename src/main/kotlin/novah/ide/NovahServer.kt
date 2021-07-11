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
package novah.ide

import novah.main.Environment
import novah.main.Source
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.io.File
import java.util.concurrent.CompletableFuture
import kotlin.io.path.Path
import kotlin.system.exitProcess

class NovahServer(private val verbose: Boolean) : LanguageServer, LanguageClientAware {

    private var logger: IdeLogger? = null
    private var env: Environment? = null
    private var root: String? = null

    private var client: LanguageClient? = null
    private var errorCode = 1

    private var workspaceService: NovahWorkspaceService = NovahWorkspaceService(this)
    private var textService: NovahTextDocumentService = NovahTextDocumentService(this)

    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        if (params.workspaceFolders.isEmpty()) {
            logger!!.error("no root supplied")
            exitProcess(1)
        }

        root = params.workspaceFolders[0].uri

        logger!!.info("starting server on $root")
        env = Environment(root!!, verbose = false)

        val initializeResult = InitializeResult(ServerCapabilities())
        initializeResult.capabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

        // Hover capability
        initializeResult.capabilities.setHoverProvider(true)

        // initial build
        build()

        return CompletableFuture.supplyAsync { initializeResult }
    }

    override fun shutdown(): CompletableFuture<Any> {
        errorCode = 0
        return CompletableFuture.supplyAsync(::Object)
    }

    override fun exit() {
        exitProcess(errorCode)
    }

    override fun getTextDocumentService(): TextDocumentService = textService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
        this.logger = IdeLogger(client)
    }

    fun env(): Environment = env!!

    fun logger(): IdeLogger = logger!!

    fun build() {
        if (root == null) return
        val path = IdeUtil.uriToFile(root!!)
        logger().info("root: $path")
        val sources = path.walkTopDown().filter { it.isFile && it.extension == "novah" }
            .map { Source.SPath(Path(it.absolutePath)) }
        logger().info("compiling project")

        env!!.reset()
        env!!.parseSources(sources.asSequence())
        env!!.generateCode(File("."), dryRun = true)
    }
}
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

import novah.frontend.error.CompilerProblem
import novah.frontend.error.Severity
import novah.frontend.matching.PatternMatchingCompiler
import novah.ide.IdeUtil.spanToRange
import novah.ide.features.SemanticTokensFeature
import novah.main.CompilationError
import novah.main.Environment
import novah.main.Source
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.*
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

class NovahServer(private val verbose: Boolean) : LanguageServer, LanguageClientAware {

    private var logger: IdeLogger? = null
    private var root: String? = null
    private val changes: AtomicReference<FileChange?> = AtomicReference(null)
    private var env: AtomicReference<Environment?> = AtomicReference(null)
    private var lastSuccessfulEnv: Environment? = null
    private val buildThread = Executors.newSingleThreadScheduledExecutor()

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

        val initializeResult = InitializeResult(ServerCapabilities())
        initializeResult.capabilities.textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)

        // Hover capability
        initializeResult.capabilities.setHoverProvider(true)
        // Formatting capability
        initializeResult.capabilities.setDocumentFormattingProvider(true)
        // Document symbols capability
        initializeResult.capabilities.setDocumentSymbolProvider(true)
        // Folding capability
        initializeResult.capabilities.setFoldingRangeProvider(true)
        // Completion capability
        initializeResult.capabilities.completionProvider = CompletionOptions(false, listOf("."))
        // Semantic tokens capability
        val semOpts =
            SemanticTokensWithRegistrationOptions(SemanticTokensFeature.legend, SemanticTokensServerFull(false))
        initializeResult.capabilities.semanticTokensProvider = semOpts

        // initial build
        build()

        // start build thread
        buildThread.scheduleWithFixedDelay(::buildRun, 0, 500, TimeUnit.MILLISECONDS)

        return CompletableFuture.supplyAsync { initializeResult }
    }

    override fun shutdown(): CompletableFuture<Any> {
        errorCode = 0
        buildThread.shutdown()
        try {
            buildThread.awaitTermination(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            buildThread.shutdownNow()
        }
        return CompletableFuture.supplyAsync(::Object)
    }

    override fun exit() {
        exitProcess(errorCode)
    }

    override fun getTextDocumentService(): TextDocumentService = textService

    override fun getWorkspaceService(): WorkspaceService = workspaceService

    override fun connect(client: LanguageClient) {
        this.client = client
        this.logger = IdeLogger(client, verbose)
    }

    fun env(): Environment {
        var envv = env.get()
        while (envv == null) {
            Thread.sleep(300)
            envv = env.get()
        }
        return envv
    }

    fun resetEnv() {
        env.set(null)
    }

    fun lastSuccessfulEnv() = lastSuccessfulEnv

    fun logger(): IdeLogger = logger!!

    fun addChange(uri: String, text: String) {
        changes.set(FileChange(uri, text))
    }

    fun resetChange() {
        changes.set(null)
    }

    fun change() = changes.get()

    private var diags = mutableMapOf<String, List<Diagnostic>>()

    fun publishDiagnostics(uri: String) {
        val diag = diags[uri] ?: listOf()
        logger().log("publishing diagnostics for $uri")
        val params = PublishDiagnosticsParams(uri, diag)
        client!!.publishDiagnostics(params)
    }

    private fun saveDiagnostics(errors: List<CompilerProblem>) {
        if (errors.isEmpty()) return

        diags = errors.map { err ->
            val sev = if (err.severity == Severity.ERROR) DiagnosticSeverity.Error else DiagnosticSeverity.Warning
            val diag = Diagnostic(spanToRange(err.span), err.msg + "\n\n")
            diag.severity = sev
            diag.source = "Novah compiler"
            val uri = File(err.fileName).toURI().toString()
            logger().log("error on $uri span ${err.span}")
            uri to diag
        }.groupBy { it.first }.mapValues { kv -> kv.value.map { it.second } }.toMutableMap()
    }

    private fun build() {
        if (root == null) return
        val change = changes.getAndUpdate { it?.copy(built = true) }
        val rootPath = IdeUtil.uriToFile(root!!)
        logger().info("root: $rootPath")
        val sources = rootPath.walkTopDown().filter { it.isFile && it.extension == "novah" }
            .map {
                val path = Path.of(it.absolutePath)
                if (change != null && change.path == it.absolutePath) Source.SString(path, change.txt)
                else Source.SPath(path)
            }
        logger().info("compiling project")

        val lenv = Environment(root!!, verbose = false)
        PatternMatchingCompiler.resetCache()
        try {
            lenv.parseSources(sources.asSequence())
            lenv.generateCode(File("."), dryRun = true)
            diags.clear()
            saveDiagnostics(lenv.getWarnings())
            lastSuccessfulEnv = lenv
        } catch (ce: CompilationError) {
            saveDiagnostics(ce.problems + lenv.getWarnings())
        } catch (e: Exception) {
            logger().error(e.stackTraceToString())
        } finally {
            env.set(lenv)
        }
    }

    private fun buildRun() {
        val change = changes.get()
        if (change != null && !change.built) {
            build()
            // publish diagnostics
            publishDiagnostics(File(change.path).toURI().toString())
        }
    }
}

data class FileChange(val path: String, val txt: String, val built: Boolean = false)
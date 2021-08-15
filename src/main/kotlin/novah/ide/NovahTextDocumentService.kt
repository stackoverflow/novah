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

import novah.ide.features.*
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.TextDocumentService
import java.util.concurrent.CompletableFuture

class NovahTextDocumentService(private val server: NovahServer) : TextDocumentService {

    private val hover = HoverFeature(server)
    private val formatting = FormattingFeature(server)
    private val symbols = SymbolsFeature(server)
    private val folding = FoldingFeature(server)
    private val semanticTokens = SemanticTokensFeature(server)
    private val completion = CompletionFeature(server)
    private val gotoDefinition = GotoDefinitionFeature(server)
    private val references = RerefencesFeature(server)
    val codeAction = CodeActionFeature(server)

    override fun didOpen(params: DidOpenTextDocumentParams) {
        val uri = params.textDocument.uri
        val cleaned = IdeUtil.uriToFile(uri).toURI().toString()
        server.logger().info("opened $uri")
        server.publishDiagnostics(cleaned)
    }

    override fun didChange(params: DidChangeTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logger().info("changed $uri")
        val file = IdeUtil.uriToFile(uri)
        if (file.extension == "novah") {
            server.resetEnv()
            server.addChange(file.absolutePath, params.contentChanges[0].text)
        }
    }

    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logger().info("closed $uri")
    }

    override fun didSave(params: DidSaveTextDocumentParams) {
        val uri = params.textDocument.uri
        server.logger().info("saved $uri")
        val file = IdeUtil.uriToFile(uri)
        if (file.extension == "novah") {
            server.resetEnv()
            server.addChange(file.absolutePath)
        }
    }

    override fun hover(params: HoverParams): CompletableFuture<Hover> {
        return hover.onHover(params)
    }

    override fun formatting(params: DocumentFormattingParams): CompletableFuture<MutableList<out TextEdit>> {
        return formatting.onFormat(params)
    }

    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        return symbols.onDocumentSymbols(params)
    }

    override fun foldingRange(params: FoldingRangeRequestParams): CompletableFuture<MutableList<FoldingRange>> {
        return folding.onFolding(params)
    }

    override fun semanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        return semanticTokens.onSemanticTokensFull(params)
    }

    override fun completion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        return completion.onCompletion(params)
    }

    override fun definition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        return gotoDefinition.onDefinition(params)
    }

    override fun codeAction(params: CodeActionParams): CompletableFuture<MutableList<Either<Command, CodeAction>>> {
        return codeAction.onCodeAction(params)
    }

    override fun references(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        return references.onReferences(params)
    }
}
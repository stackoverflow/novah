package novah.ide.features

import com.google.gson.JsonPrimitive
import novah.frontend.error.Action
import novah.frontend.error.CompilerProblem
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.*
import java.util.concurrent.CompletableFuture

class CodeActionFeature(private val server: NovahServer) {

    private val errCache = mutableMapOf<String, CompilerProblem>()

    fun onCodeAction(params: CodeActionParams): CompletableFuture<MutableList<Either<Command, CodeAction>>> {
        fun run(): MutableList<Either<Command, CodeAction>>? {
            server.logger().log("received code action request for ${params.textDocument.uri}")

            val key = params.context.diagnostics.getOrNull(0)?.data as? JsonPrimitive ?: return null
            if (!key.isString) return null
            val err = errCache[key.asString] ?: return null
            
            val action = actionFor(err, params.textDocument.uri) ?: return null
            
            return mutableListOf(Either.forRight(action))
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun actionFor(err: CompilerProblem, uri: String): CodeAction? = when (err.action) {
        is Action.NoType -> {
            val action = CodeAction("Add type annotation")
            action.kind = "quickfix"
            val range = IdeUtil.spanToRange(err.span)
            range.end = range.start
            val tedit = TextEdit(range, err.action.inferedTty + "\n")
            val docid = VersionedTextDocumentIdentifier(uri, 1)
            val tdedit = TextDocumentEdit(docid, mutableListOf(tedit))
            action.edit = WorkspaceEdit(mutableListOf(Either.forLeft(tdedit)))
            action
        }
        is Action.None -> null
    }
    
    fun storeError(err: CompilerProblem): String? = when (err.action) {
        is Action.None -> null
        else -> {
            val id = UUID.randomUUID().toString()
            errCache[id] = err
            id
        }
    }

    fun resetCache() = errCache.clear()
}
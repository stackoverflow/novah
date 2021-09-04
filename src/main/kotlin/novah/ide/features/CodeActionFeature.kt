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
package novah.ide.features

import com.google.gson.JsonPrimitive
import novah.ast.source.DeclarationRef
import novah.ast.source.Import
import novah.data.mapBoth
import novah.formatter.Formatter
import novah.frontend.Span
import novah.frontend.error.Action
import novah.frontend.error.CompilerProblem
import novah.ide.EnvResult
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.*
import java.util.concurrent.CompletableFuture

class CodeActionFeature(private val server: NovahServer) {

    private val errCache = mutableMapOf<String, CompilerProblem>()

    fun onCodeAction(params: CodeActionParams): CompletableFuture<MutableList<Either<Command, CodeAction>>> {
        fun run(envRes: EnvResult): MutableList<Either<Command, CodeAction>>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)
            server.logger().log("received code action request for ${file.absolutePath}")

            val key = params.context.diagnostics.getOrNull(0)?.data as? JsonPrimitive ?: return null
            if (!key.isString) return null
            val err = errCache[key.asString] ?: return null

            val change = envRes.change?.txt

            val action = actionFor(err, params.textDocument.uri, change) ?: return null

            return mutableListOf(Either.forRight(action))
        }

        return server.runningEnv().thenApply(::run)
    }

    private fun actionFor(err: CompilerProblem, uri: String, code: String?): CodeAction? = when (err.action) {
        is Action.NoType -> {
            val range = IdeUtil.spanToRange(err.span)
            range.end = range.start
            makeAction("Add type annotation", range, err.action.inferedTty + "\n", uri)
        }
        is Action.UnusedImport -> {
            if (code != null) {
                IdeUtil.parseCode(code).mapBoth({ mod ->
                    mod.imports.find { it.span().matches(err.span.startLine, err.span.startColumn) }?.let {
                        val range = IdeUtil.spanToRange(it.span())
                        val imp = removeImport(it, err.action.vvar, err.span)
                        makeAction("Remove unused import", range, Formatter().show(imp), uri)
                    }
                }) { null }
            } else null
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

    private fun removeImport(imp: Import, name: String, span: Span): Import {
        return when (imp) {
            is Import.Raw -> imp
            is Import.Exposing -> {
                var defs = if (name[0].isUpperCase()) {
                    imp.defs.map { d ->
                        when (d)  {
                            is DeclarationRef.RefVar -> d
                            is DeclarationRef.RefType -> {
                                if (d.ctors != null) {
                                    val cs = d.ctors.filter { it.value != name && it.span != span }
                                    d.copy(ctors = cs)
                                } else d
                            }
                        }
                    }
                } else imp.defs
                defs = defs.filter {
                    when (it) {
                        is DeclarationRef.RefVar -> it.name != name && it.span != span
                        is DeclarationRef.RefType -> it.name != name && it.span != span
                    }
                }
                imp.copy(defs = defs)
            }
        }
    }

    private fun makeAction(title: String, range: Range, text: String, uri: String): CodeAction {
        val action = CodeAction(title)
        action.kind = "quickfix"
        val tedit = TextEdit(range, text)
        val docid = VersionedTextDocumentIdentifier(uri, 1)
        val tdedit = TextDocumentEdit(docid, mutableListOf(tedit))
        action.edit = WorkspaceEdit(mutableListOf(Either.forLeft(tdedit)))
        return action
    }
}
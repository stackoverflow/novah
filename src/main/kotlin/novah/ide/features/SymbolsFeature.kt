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

import novah.ast.canonical.Decl
import novah.ast.canonical.Module
import novah.ast.canonical.show
import novah.frontend.typechecker.TArrow
import novah.ide.IdeUtil
import novah.ide.IdeUtil.spanToRange
import novah.ide.NovahServer
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class SymbolsFeature(private val server: NovahServer) {

    fun onDocumentSymbols(params: DocumentSymbolParams): CompletableFuture<MutableList<Either<SymbolInformation, DocumentSymbol>>> {
        fun run(): MutableList<Either<SymbolInformation, DocumentSymbol>>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            server.logger().log("received symbol request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null

            return mutableListOf(Either.forRight(moduleToSymbol(mod.ast)))
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun moduleToSymbol(ast: Module): DocumentSymbol {
        val modRange = spanToRange(ast.name.span)
        val modSym = DocumentSymbol(ast.name.value, SymbolKind.Module, modRange, modRange, "module ${ast.name.value}")

        val decls = ast.decls.map { decl ->
            when (decl) {
                is Decl.TypeDecl -> typeDeclToSymbol(decl)
                is Decl.ValDecl -> {
                    DocumentSymbol(
                        decl.name.name,
                        if (decl.type is TArrow) SymbolKind.Function else SymbolKind.Variable,
                        spanToRange(decl.span),
                        spanToRange(decl.name.span),
                        decl.type?.show(true, pretty = true)
                    )
                }
            }
        }

        modSym.children = decls
        return modSym
    }

    private fun typeDeclToSymbol(d: Decl.TypeDecl): DocumentSymbol {
        val tySym = DocumentSymbol(
            d.name,
            SymbolKind.Class,
            spanToRange(d.span),
            spanToRange(d.span),
            d.show()
        )
        val ctors = d.dataCtors.map {
            DocumentSymbol(
                it.name,
                SymbolKind.Constructor,
                spanToRange(it.span),
                spanToRange(it.span)
            )
        }
        val tvars = d.tyVars.map {
            DocumentSymbol(
                it,
                SymbolKind.TypeParameter,
                spanToRange(d.span),
                spanToRange(d.span)
            )
        }
        tySym.children = tvars + ctors
        return tySym
    }
}
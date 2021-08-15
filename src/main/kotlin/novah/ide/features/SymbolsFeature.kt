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
import novah.ast.canonical.show
import novah.frontend.typechecker.TArrow
import novah.ide.IdeUtil
import novah.ide.IdeUtil.spanToRange
import novah.ide.NovahServer
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
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

            return mutableListOf(Either.forRight(moduleToSymbol(mod)))
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun moduleToSymbol(mod: FullModuleEnv): DocumentSymbol {
        val ast = mod.ast
        val modRange = spanToRange(ast.name.span)
        val modSym = DocumentSymbol(ast.name.value, SymbolKind.Module, modRange, modRange, "module ${ast.name.value}")

        val decls = ast.decls.map { decl ->
            when (decl) {
                is Decl.TypeDecl -> typeDeclToSymbol(decl)
                is Decl.ValDecl -> {
                    DocumentSymbol(
                        decl.name.name,
                        if (decl.signature?.type is TArrow) SymbolKind.Function else SymbolKind.Variable,
                        spanToRange(decl.span),
                        spanToRange(decl.name.span),
                        decl.signature?.type?.show(true, typeVarsMap = mod.typeVarsMap)
                    )
                }
            }
        }
        val aliases = mod.aliases.map { ta ->
            val sym = DocumentSymbol(
                ta.name,
                SymbolKind.Interface,
                spanToRange(ta.span),
                spanToRange(ta.span),
                ta.type.show()
            )
            val tvars = ta.tyVars.map {
                DocumentSymbol(
                    it,
                    SymbolKind.TypeParameter,
                    spanToRange(ta.span),
                    spanToRange(ta.span)
                )
            }
            sym.children = tvars
            sym
        }

        modSym.children = decls + aliases
        return modSym
    }

    private fun typeDeclToSymbol(d: Decl.TypeDecl): DocumentSymbol {
        val tySym = DocumentSymbol(
            d.name.value,
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
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
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.ast.canonical.everywhere
import novah.frontend.Span
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.FoldingRange
import org.eclipse.lsp4j.FoldingRangeKind
import org.eclipse.lsp4j.FoldingRangeRequestParams
import java.util.concurrent.CompletableFuture

class FoldingFeature(private val server: NovahServer) {

    fun onFolding(params: FoldingRangeRequestParams): CompletableFuture<MutableList<FoldingRange>> {
        fun run(): MutableList<FoldingRange>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            server.logger().log("received folding request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null

            return calcFolds(mod.ast)
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun calcFolds(ast: Module): MutableList<FoldingRange> {
        val folds = mutableListOf<FoldingRange>()

        // import folding
        val imps = ast.imports.filter { !it.isAuto() }.map { it.span() }
        val allImps = (ast.foreigns.map { it.span } + imps).sortedBy { it.startLine }
        if (allImps.size > 1) {
            val fold = FoldingRange(allImps[0].startLine - 1, allImps.last().endLine - 1)
            fold.kind = FoldingRangeKind.Imports
            folds += fold
        }

        // function folding
        ast.decls.forEach { d ->
            val span = d.span

            if (span.isMultiline()) {
                folds += spanToFold(span)
            }
        }

        return folds
    }

    private fun spanToFold(span: Span) = FoldingRange(span.startLine - 1, span.endLine - 1)
}
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
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.SemanticTokens
import org.eclipse.lsp4j.SemanticTokensLegend
import org.eclipse.lsp4j.SemanticTokensParams
import java.util.concurrent.CompletableFuture

class SemanticTokensFeature(private val server: NovahServer) {

    fun onSemanticTokensFull(params: SemanticTokensParams): CompletableFuture<SemanticTokens> {
        fun run(): SemanticTokens? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            server.logger().log("received semantic tokens request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null

            return genTokens(mod.ast)
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun genTokens(ast: Module): SemanticTokens {
        var prevLine = 0
        var prevChar = 0

        val tks = ast.decls.filterIsInstance<Decl.ValDecl>().flatMap {
            val name = it.name
            val startLine = name.span.startLine - 1
            val startChar = name.span.startColumn - 1
            val deltaLine = startLine - prevLine
            val deltaChar = if (startLine == prevLine) startChar - prevChar else startChar
            val res = listOf(deltaLine, deltaChar, name.span.length(), 5, 0)
            prevLine = startLine
            prevChar = startChar
            res
        }

        return SemanticTokens(tks)
    }

    companion object {
        val legend = SemanticTokensLegend(
            listOf(
                "namespace",
                "type",
                "typeParameter",
                "parameter",
                "variable",
                "function",
                "comment",
                "string",
                "number",
                "operator"
            ),
            listOf("defaultLibrary")
        )
    }
}
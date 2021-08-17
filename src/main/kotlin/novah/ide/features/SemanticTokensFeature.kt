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

import novah.ast.canonical.*
import novah.ast.source.DeclarationRef
import novah.ast.source.Import
import novah.frontend.Span
import novah.frontend.typechecker.TConst
import novah.frontend.typechecker.TVar
import novah.frontend.typechecker.Type
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

        fun genLine(span: Span, type: Int, modifiers: Int = 0): List<Int> {
            val startLine = span.startLine - 1
            val startChar = span.startColumn - 1
            val deltaLine = startLine - prevLine
            val deltaChar = if (startLine == prevLine) startChar - prevChar else startChar
            val res = listOf(deltaLine, deltaChar, span.length(), type, modifiers)
            prevLine = startLine
            prevChar = startChar
            return res
        }

        fun genTypeLine(type: Type): List<Int> {
            val tokens = mutableListOf<List<Int>>()
            type.everywhereUnit {
                if (it.span != null) {
                    when (it) {
                        is TConst -> tokens += genLine(it.span!!, TYPE)
                        is TVar -> tokens += genLine(it.span!!, VAR)
                    }
                }
            }
            return tokens.flatten()
        }

        val imps = ast.imports.filterIsInstance<Import.Exposing>().flatMap { imp ->
            imp.defs.flatMap { def ->
                when (def) {
                    is DeclarationRef.RefVar -> genLine(def.span, FUNCTION)
                    is DeclarationRef.RefType -> {
                        val res = genLine(def.binder.span, TYPE)
                        if (def.ctors != null && def.ctors.isNotEmpty()) {
                            res + def.ctors.flatMap { genLine(it.span, FUNCTION) }
                        } else res
                    }
                }
            }
        }

        val tks = ast.decls.filterIsInstance<Decl.ValDecl>().flatMap {
            val decl = genLine(it.name.span, FUNCTION, 1)

            val tokens = mutableListOf<List<Int>>()
            val parNames = mutableSetOf<String>()
            var destruct = 0
            var exp = if (it.exp is Expr.Ann) it.exp.exp else it.exp
            // create tokens for lambda params
            while (exp is Expr.Lambda) {
                val name = exp.binder.name
                if (!name.startsWith("var$")) {
                    parNames += name
                    tokens += genLine(exp.binder.span, PARAM)
                } else destruct++
                exp = exp.body
            }
            // create tokens for destructuring patterns params
            while (exp is Expr.Match && destruct > 0) {
                destruct--
                exp.cases[0].patterns[0].everywhereUnit { p ->
                    when (p) {
                        is Pattern.Var -> {
                            parNames += p.v.name
                            tokens += genLine(p.span, PARAM)
                        }
                    }
                }
            }
            exp.everywhereUnit { e ->
                tokens += when (e) {
                    is Expr.Var -> {
                        if (e.name in parNames) genLine(e.span, PARAM)
                        else if (e.isOp) genLine(e.span, OP)
                        else listOf()
                    }
                    is Expr.ImplicitVar -> {
                        if (e.name in parNames) genLine(e.span, PARAM)
                        else listOf()
                    }
                    is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64 -> genLine(e.span, NUM)
                    is Expr.TypeCast -> genTypeLine(e.cast)
                    is Expr.Ann -> genTypeLine(e.annType)
                    is Expr.NativeMethod -> genLine(e.span, METHOD)
                    else -> listOf()
                }
            }
            decl + tokens.flatten()
        }

        return SemanticTokens(imps + tks)
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
                "operator",
                "method"
            ),
            listOf("declaration", "defaultLibrary")
        )

        const val TYPE = 1
        const val FUNCTION = 5
        const val PARAM = 3
        const val VAR = 4
        const val OP = 9
        const val NUM = 8
        const val METHOD = 10
    }
}
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
import novah.ast.source.Visibility
import novah.frontend.typechecker.Type
import novah.ide.IdeUtil
import novah.ide.NovahServer
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class HoverFeature(private val server: NovahServer) {

    fun onHover(params: HoverParams): CompletableFuture<Hover> {
        fun run(): Hover? {
            val path = IdeUtil.uriToFile(params.textDocument.uri)
            val moduleName = env().sourceMap()[path.toPath()] ?: return null

            val mod = env().modules()[moduleName] ?: return null
            val line = params.position.line + 1
            val col = params.position.character + 1
            logger().log("hovering on ${mod.ast.name.value} $line:$col")

            val decl = searchPosition(mod.ast, line, col) ?: return null
            val msg = when (decl) {
                is Decl.TypeDecl -> declToHover(decl)
                is Decl.ValDecl -> {
                    if (decl.exp.span.matches(line, col)) expToHover(searchExpr(decl, line, col), line, col)
                    else {
                        val ty = searchType(decl.type, line, col)
                        ty?.show(true) ?: declToHover(decl)
                    }
                }
            } ?: return null

            return Hover(Either.forLeft(msg))
        }
        return CompletableFuture.supplyAsync(::run)
    }

    private fun env() = server.env()
    private fun logger() = server.logger()

    private fun searchPosition(mod: Module, line: Int, col: Int): Decl? {
        return mod.decls.find { it.span.matches(line, col) }
    }

    private fun searchExpr(decl: Decl, line: Int, col: Int): Expr? {
        return if (decl is Decl.ValDecl) {
            var exp: Expr? = null
            decl.exp.everywhere {
                if (exp == null && it.span.matches(line, col) &&
                    (it.type != null || it is Expr.Lambda && it.binder.type != null)
                ) {
                    exp = it
                }
                it
            }
            exp
        } else null
    }

    private fun searchType(type: Type?, line: Int, col: Int): Type? {
        var ty: Type? = null
        type?.everywhereUnitBottomUp {
            if (ty == null && it.span?.matches(line, col) == true) {
                ty = it
            }
        }
        return ty
    }

    private fun declToHover(decl: Decl) = when (decl) {
        is Decl.TypeDecl -> {
            val header = if (decl.isPublic()) "pub ${decl.name}" else decl.name
            if (decl.tyVars.isEmpty()) header
            else "$header ${decl.tyVars.joinToString(" ")}"
        }
        is Decl.ValDecl -> {
            val header = if (decl.isPublic()) "pub ${decl.name.name}" else decl.name.name
            if (decl.type != null) {
                "$header : ${decl.type.show(qualified = true)}"
            } else header
        }
    }

    private fun expToHover(expr: Expr?, line: Int, col: Int): String? {
        if (expr == null) return null
        if (expr.type != null) return expr.type!!.show(true)
        if (expr !is Expr.Lambda) return null
        if (expr.binder.span.matches(line, col)) return expr.binder.type?.show(true)
        return null
    }

    private fun Decl.TypeDecl.isPublic() = visibility == Visibility.PUBLIC
    private fun Decl.ValDecl.isPublic() = visibility == Visibility.PUBLIC
}
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
import novah.frontend.Span
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.ReferenceParams
import java.util.concurrent.CompletableFuture

class RerefencesFeature(private val server: NovahServer) {

    fun onReferences(params: ReferenceParams): CompletableFuture<MutableList<out Location>> {
        fun run(): MutableList<Location>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            val line = params.position.line + 1
            val col = params.position.character + 1
            server.logger().info("got find references request for ${file.absolutePath} at $line:$col")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null

            val ctx = findContext(line, col, mod.ast) ?: return null

            return findReferences(ctx, mod.ast, env.modules())
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun findReferences(ctx: RefCtx, mod: Module, mods: Map<String, FullModuleEnv>): MutableList<Location> {
        val refs = mutableListOf<Location>()
        when (ctx) {
            is LocalCtx -> {
                var exp: Expr? = null
                ctx.decl.exp.everywhereUnit {
                    if (it is Expr.Let && it.letDef.binder.name == ctx.name) exp = it
                    else if (it is Expr.Match && caseContainsVar(ctx.name, it)) exp = it
                }
                exp?.everywhereUnit { e ->
                    if (isVar(e, ctx.name, null))
                        refs += location(e.span, mod.name.value, mod.sourceName)
                }
            }
            is DeclCtx -> {
                mods.forEach { (module, fmv) ->
                    val modName = if (ctx.module == module) null else ctx.module
                    fmv.ast.decls.filterIsInstance<Decl.ValDecl>().forEach { decl ->
                        decl.exp.everywhereUnit { e ->
                            if (isVar(e, ctx.name, modName))
                                refs += location(e.span, module, fmv.ast.sourceName)
                        }
                    }
                }
            }
        }
        return refs
    }

    sealed class RefCtx()
    data class LocalCtx(val name: String, val decl: Decl.ValDecl) : RefCtx()
    data class DeclCtx(val name: String, val module: String?) : RefCtx()

    private fun findContext(line: Int, col: Int, mod: Module): RefCtx? {
        var ctx: RefCtx? = null
        val decls = mod.decls.filterIsInstance<Decl.ValDecl>()
        val names = decls.map { it.name.name }.toSet()
        
        for (tdecl in mod.decls.filterIsInstance<Decl.TypeDecl>()) {
            if (!tdecl.span.matches(line, col)) continue
            
            for (ctor in tdecl.dataCtors) { 
                if (ctor.span.matches(line, col)) {
                    return DeclCtx(ctor.name, mod.name.value) 
                }
            }
        }
        
        for (decl in decls) {
            if (ctx != null) break
            if (!decl.span.matches(line, col)) continue

            if (decl.name.span.matches(line, col)) return DeclCtx(decl.name.name, mod.name.value)
            decl.exp.everywhereUnit { e ->
                if (e.span.matches(line, col)) {
                    when (e) {
                        is Expr.Var -> {
                            ctx = if (e.moduleName != null || e.name in names) {
                                DeclCtx(e.name, e.moduleName)
                            } else LocalCtx(e.name, decl)
                        }
                        is Expr.Constructor -> {
                            ctx = DeclCtx(e.name, e.moduleName)
                        }
                        is Expr.ImplicitVar -> {
                            ctx = if (e.moduleName != null || e.name in names) {
                                DeclCtx(e.name, e.moduleName)
                            } else LocalCtx(e.name, decl)
                        }
                        is Expr.Let -> {
                            if (e.letDef.binder.span.matches(line, col)) {
                                ctx = LocalCtx(e.letDef.binder.name, decl)
                            }
                        }
                    }
                }
            }
        }
        return ctx
    }

    private fun caseContainsVar(name: String, exp: Expr.Match): Boolean {
        return exp.cases.any { c ->
            c.patterns.any { p ->
                var found = false
                p.everywhereUnit {
                    when (it) {
                        is Pattern.Var -> if (it.name == name) found = true
                        is Pattern.Named -> if (it.name.value == name) found = true
                    }
                }
                found
            }
        }
    }

    private fun isVar(e: Expr, name: String, moduleName: String?): Boolean = when (e) {
        is Expr.Var -> e.name == name && e.moduleName == moduleName
        is Expr.Constructor -> e.name == name && e.moduleName == moduleName
        is Expr.ImplicitVar -> e.name == name && e.moduleName == moduleName
        else -> false
    }

    private fun location(span: Span, moduleName: String, sourceName: String): Location {
        val range = IdeUtil.spanToRange(span)
        val uri = server.locationUri(moduleName, sourceName)
        return Location(uri, range)
    }
}
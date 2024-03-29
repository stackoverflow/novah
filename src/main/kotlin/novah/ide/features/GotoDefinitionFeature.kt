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
import novah.ast.canonical.everywhereUnit
import novah.ast.source.DeclarationRef
import novah.ast.source.Import
import novah.frontend.Span
import novah.ide.EnvResult
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class GotoDefinitionFeature(private val server: NovahServer) {

    fun onDefinition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        fun run(envRes: EnvResult): Either<MutableList<out Location>, MutableList<out LocationLink>>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = envRes.env
            server.logger().log("received go to definition request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            val line = params.position.line + 1
            val col = params.position.character + 1

            val def = findDefinition(mod.ast, line, col, env.modules())
            return if (def != null) Either.forLeft(mutableListOf(def)) else null
        }
        return server.runningEnv().thenApply(::run)
    }

    private fun findDefinition(ast: Module, line: Int, col: Int, mods: Map<String, FullModuleEnv>): Location? {
        var location: Location? = null
        fun goto(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.find { it is Decl.ValDecl && it.name.value == name }?.let { newLocation(mod, it.span) }
        }

        fun gotoType(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.find { it is Decl.TypeDecl && it.name.value == name }?.let { newLocation(mod, it.span) }
        }

        fun gotoCtor(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.filterIsInstance<Decl.TypeDecl>().flatMap { it.dataCtors }.find { it.name.value == name }
                ?.let { newLocation(mod, it.span) }
        }

        // look into imports
        for (imp in ast.imports) {
            if (imp.span().matches(line, col)) {
                if (imp.module.span.matches(line, col)) {
                    val mod = mods[imp.module.value] ?: break
                    val loc = server.locationUri(imp.module.value, mod.ast.sourceName)
                    return Location(loc, IdeUtil.spanToRange(mod.ast.name.span))
                }
                if (imp is Import.Exposing) {
                    for (def in imp.defs) {
                        if (def.span.matches(line, col)) {
                            when (def) {
                                is DeclarationRef.RefVar -> return goto(def.name, imp.module.value)
                                is DeclarationRef.RefType -> {
                                    if (def.binder.span.matches(line, col)) {
                                        return gotoType(def.name, imp.module.value)
                                    }
                                    if (def.ctors != null && def.ctors.isNotEmpty()) {
                                        for (ctor in def.ctors) {
                                            if (ctor.span.matches(line, col)) {
                                                return gotoCtor(ctor.value, imp.module.value)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // look into declarations
        for (d in ast.decls.filterIsInstance<Decl.ValDecl>()) {
            if (location != null) break
            if (d.span.matches(line, col)) {
                d.exp.everywhereUnit { e ->
                    if (e.span.matches(line, col)) {
                        when (e) {
                            is Expr.Var -> location = goto(e.name, e.moduleName ?: ast.name.value)
                            is Expr.ImplicitVar -> location = goto(e.name, e.moduleName ?: ast.name.value)
                            is Expr.Constructor -> location = gotoCtor(e.name, e.moduleName ?: ast.name.value)
                            else -> {}
                        }
                    }
                }
            }
        }
        return location
    }

    private fun newLocation(mod: Module, span: Span) =
        Location(server.locationUri(mod.name.value, mod.sourceName), IdeUtil.spanToRange(span))
}
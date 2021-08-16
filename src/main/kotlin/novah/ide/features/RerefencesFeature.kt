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
import novah.frontend.typechecker.TConst
import novah.frontend.typechecker.Type
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class RerefencesFeature(private val server: NovahServer) {

    /**
     * Finds all references to the symbol at the cursor.
     */
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

            return findReferences(ctx, mod, env.modules()).second
        }

        return CompletableFuture.supplyAsync(::run)
    }

    /**
     * Finds out if it's OK to rename the symbol at the cursor.
     */
    fun onPrepareRename(params: PrepareRenameParams): CompletableFuture<Either<Range, PrepareRenameResult>> {
        fun run(): Either<Range, PrepareRenameResult>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            val line = params.position.line + 1
            val col = params.position.character + 1
            server.logger().info("got prepare rename request for ${file.absolutePath} at $line:$col")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            if (mod.isStdlib) return null

            val ctx = findContext(line, col, mod.ast) ?: return null
            if (!isValidRename(ctx, env)) return null

            val (origin, _) = findReferences(ctx, mod, env.modules())
            if (origin == null) return null
            return Either.forLeft(IdeUtil.spanToRange(ctx.span))
        }

        return CompletableFuture.supplyAsync(::run)
    }

    /**
     * Renames the symbol at the cursor, including the definition.
     */
    fun onRename(params: RenameParams): CompletableFuture<WorkspaceEdit> {
        fun run(): WorkspaceEdit? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            val line = params.position.line + 1
            val col = params.position.character + 1
            server.logger().info("got rename request for ${file.absolutePath} at $line:$col")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            if (mod.isStdlib) return null

            val ctx = findContext(line, col, mod.ast) ?: return null
            if (!isValidRename(ctx, env)) return null

            val (origin, locations) = findReferences(ctx, mod, env.modules())
            if (origin == null) return null
            return makeRename(params.newName, origin, locations)
        }

        return CompletableFuture.supplyAsync(::run)
    }

    /**
     * Find all references (locations) represented by the context.
     * The origin is just used by rename, as we need to rename not only the references
     * but also the original definition.
     */
    private fun findReferences(
        ctx: RefCtx,
        mod: FullModuleEnv,
        mods: Map<String, FullModuleEnv>
    ): Pair<Origin?, MutableList<Location>> {
        val refs = mutableListOf<Location>()
        var origin: Origin? = null
        when (ctx) {
            is LocalCtx -> {
                var exp: Expr? = null
                ctx.decl.exp.everywhereUnit {
                    if (it is Expr.Let && it.letDef.binder.name == ctx.name) {
                        exp = it
                        origin = Origin(SrcLocal(it.letDef.binder.span), mod.ast)
                    } else if (it is Expr.Match) {
                        val span = caseContainsVar(ctx.name, it)
                        if (span != null) {
                            exp = it
                            origin = Origin(SrcLocal(span), mod.ast)
                        }
                    } else if (it is Expr.Lambda && it.binder.name == ctx.name) {
                        exp = it
                        origin = Origin(SrcLocal(it.binder.span), mod.ast)
                    }
                }
                exp?.everywhereUnit { e ->
                    if (isVar(e, ctx.name, null))
                        refs += location(e.span, mod.ast.name.value, mod.ast.sourceName)
                }
            }
            is LambdaCtx -> {
                val lam = ctx.lambda
                origin = Origin(SrcLocal(lam.binder.span), mod.ast)

                lam.body.everywhereUnit { e ->
                    if (isVar(e, lam.binder.name, null))
                        refs += location(e.span, mod.ast.name.value, mod.ast.sourceName)
                }
            }
            is DeclCtx -> {
                for ((module, fmv) in mods) {
                    val modName = if (ctx.module == module) null else ctx.module
                    fmv.ast.decls.filterIsInstance<Decl.ValDecl>().forEach { decl ->
                        if (decl.name.name == ctx.name) origin = Origin(SrcDecl(decl), fmv.ast)
                        decl.exp.everywhereUnit { e ->
                            if (isVar(e, ctx.name, modName))
                                refs += location(e.span, module, fmv.ast.sourceName)
                        }
                    }
                }
            }
            is TypeCtx -> {
                for ((module, fmv) in mods) {
                    fmv.ast.decls.filterIsInstance<Decl.TypeDecl>().forEach { decl ->
                        val fqn = "$module.${decl.name.value}"
                        if (fqn == ctx.name) origin = Origin(SrcDecl(decl), fmv.ast)
                    }
                    fmv.ast.decls.filterIsInstance<Decl.ValDecl>().forEach { decl ->
                        decl.exp.everywhereUnit { e ->
                            when (e) {
                                is Expr.Ann -> {
                                    e.annType.everywhereUnit { t ->
                                        if (t is TConst && t.name == ctx.name) {
                                            refs += location(t.span ?: e.span, module, fmv.ast.sourceName)
                                        }
                                    }
                                }
                                is Expr.TypeCast -> {
                                    e.cast.everywhereUnit { t ->
                                        if (t is TConst && t.name == ctx.name) {
                                            refs += location(t.span ?: e.span, module, fmv.ast.sourceName)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return origin to refs
    }

    private sealed class RefCtx(open val span: Span)
    private data class LocalCtx(val name: String, val decl: Decl.ValDecl, override val span: Span) : RefCtx(span)
    private data class DeclCtx(val name: String, val module: String?, override val span: Span) : RefCtx(span)
    private data class TypeCtx(val name: String, override val span: Span) : RefCtx(span)
    private data class LambdaCtx(val lambda: Expr.Lambda) : RefCtx(lambda.binder.span)

    private sealed class OriginSource
    private class SrcDecl(val decl: Decl) : OriginSource()
    private class SrcLocal(val span: Span) : OriginSource()

    private data class Origin(val src: OriginSource, val module: Module)

    /**
     * Finds the symbol at line and col and returns contextual information
     * to find all references later.
     */
    private fun findContext(line: Int, col: Int, mod: Module): RefCtx? {
        var ctx: RefCtx? = null
        val decls = mutableListOf<Decl.ValDecl>()
        val tdecls = mutableListOf<Decl.TypeDecl>()
        mod.decls.forEach { d ->
            if (d is Decl.ValDecl) decls += d
            else if (d is Decl.TypeDecl) tdecls += d
        }
        val names = decls.map { it.name.name }.toSet()

        fun findType(ty: Type) {
            ty.everywhereUnit {
                if (it.span?.matches(line, col) == true && it is TConst)
                    ctx = TypeCtx(it.name, ty.span!!)
            }
        }

        for (tdecl in tdecls) {
            if (!tdecl.span.matches(line, col)) continue

            if (tdecl.name.span.matches(line, col)) {
                return TypeCtx("${mod.name.value}.${tdecl.name.value}", tdecl.name.span)
            }
            for (ctor in tdecl.dataCtors) {
                if (ctor.span.matches(line, col)) {
                    return DeclCtx(ctor.name, mod.name.value, ctor.span)
                }
            }
        }

        for (decl in decls) {
            if (ctx != null) break
            if (!decl.span.matches(line, col)) continue

            if (decl.name.span.matches(line, col)) return DeclCtx(decl.name.name, mod.name.value, decl.name.span)
            if (decl.signature != null && decl.signature.type.span?.matches(line, col) == true) {
                findType(decl.signature.type)
                return ctx
            }
            decl.exp.everywhereUnit { e ->
                if (e.span.matches(line, col)) {
                    when (e) {
                        is Expr.Var -> {
                            if (!e.name.startsWith("var$")) {
                                ctx = if (e.moduleName != null || e.name in names) {
                                    DeclCtx(e.name, e.moduleName, e.span)
                                } else LocalCtx(e.name, decl, e.span)
                            }
                        }
                        is Expr.Constructor -> {
                            ctx = DeclCtx(e.name, e.moduleName, e.span)
                        }
                        is Expr.ImplicitVar -> {
                            ctx = if (e.moduleName != null || e.name in names) {
                                DeclCtx(e.name, e.moduleName, e.span)
                            } else LocalCtx(e.name, decl, e.span)
                        }
                        is Expr.Let -> {
                            val binder = e.letDef.binder
                            if (binder.span.matches(line, col)) {
                                ctx = LocalCtx(binder.name, decl, binder.span)
                            }
                        }
                        is Expr.Ann -> {
                            if (e.annType.span?.matches(line, col) == true) {
                                findType(e.annType)
                            }
                        }
                        is Expr.TypeCast -> {
                            if (e.cast.span?.matches(line, col) == true) {
                                findType(e.cast)
                            }
                        }
                        is Expr.Lambda -> {
                            if (e.binder.span.matches(line, col) && !e.binder.name.startsWith("var$")) {
                                ctx = LambdaCtx(e)
                            }
                        }
                    }
                }
            }
        }
        return ctx
    }

    private fun isValidRename(ctx: RefCtx, env: Environment): Boolean = when (ctx) {
        is LocalCtx, is LambdaCtx -> true
        is DeclCtx -> ctx.module == null || env.modules().containsKey(ctx.module)
        is TypeCtx -> {
            val mod = IdeUtil.getModule(ctx.name)
            mod != null && env.modules().containsKey(mod)
        }
    }

    private fun makeRename(newName: String, origin: Origin, locations: List<Location>): WorkspaceEdit {
        val edits = locations.groupBy { it.uri }
            .map<String?, List<Location>, Either<TextDocumentEdit, ResourceOperation>?> { (uri, ls) ->
                val edits = ls.map { l -> TextEdit(l.range, newName) }
                Either.forLeft(TextDocumentEdit(VersionedTextDocumentIdentifier(uri, null), edits))
            }.toMutableList()

        val uri = server.locationUri(origin.module.name.value, origin.module.sourceName)
        val tedit = when (origin.src) {
            is SrcDecl -> {
                when (val d = origin.src.decl) {
                    is Decl.ValDecl -> {
                        val ed = TextEdit(IdeUtil.spanToRange(d.name.span), newName)
                        if (d.signature != null) {
                            val ed2 = TextEdit(IdeUtil.spanToRange(d.signature.span), newName)
                            listOf(ed, ed2)
                        } else listOf(ed)
                    }
                    is Decl.TypeDecl -> {
                        listOf(TextEdit(IdeUtil.spanToRange(d.name.span), newName))
                    }
                }
            }
            is SrcLocal -> {
                listOf(TextEdit(IdeUtil.spanToRange(origin.src.span), newName))
            }
        }
        val tdedit = TextDocumentEdit(VersionedTextDocumentIdentifier(uri, null), tedit)
        edits += Either.forLeft(tdedit)

        return WorkspaceEdit(edits)
    }

    private fun caseContainsVar(name: String, exp: Expr.Match): Span? {
        var found: Span? = null
        exp.cases.forEach { c ->
            c.patterns.forEach { p ->
                p.everywhereUnit {
                    when (it) {
                        is Pattern.Var -> if (it.name == name) found = it.span
                        is Pattern.Named -> if (it.name.value == name) found = it.name.span
                    }
                }
            }
        }
        return found
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
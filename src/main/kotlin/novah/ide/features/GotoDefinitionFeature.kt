package novah.ide.features

import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.ast.canonical.everywhereUnit
import novah.ast.source.DeclarationRef
import novah.ast.source.Import
import novah.frontend.Span
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import novah.main.FullModuleEnv
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture
import kotlin.io.path.invariantSeparatorsPathString

class GotoDefinitionFeature(private val server: NovahServer) {

    fun onDefinition(params: DefinitionParams): CompletableFuture<Either<MutableList<out Location>, MutableList<out LocationLink>>> {
        fun run(): Either<MutableList<out Location>, MutableList<out LocationLink>>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val env = server.env()
            server.logger().log("received go to definition request for ${file.absolutePath}")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            val line = params.position.line + 1
            val col = params.position.character + 1

            val def = findDefinition(mod.ast, line, col, env.modules())
            return if (def != null) Either.forLeft(mutableListOf(def)) else null
        }
        return CompletableFuture.supplyAsync(::run)
    }

    private fun findDefinition(ast: Module, line: Int, col: Int, mods: Map<String, FullModuleEnv>): Location? {
        var location: Location? = null
        fun goto(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.find { it is Decl.ValDecl && it.name.name == name }?.let { newLocation(mod, it.span) }
        }

        fun gotoType(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.find { it is Decl.TypeDecl && it.name == name }?.let { newLocation(mod, it.span) }
        }

        fun gotoCtor(name: String, moduleName: String): Location? {
            val mod = mods[moduleName]?.ast ?: return null
            return mod.decls.filterIsInstance<Decl.TypeDecl>().flatMap { it.dataCtors }.find { it.name == name }
                ?.let { newLocation(mod, it.span) }
        }

        // look into imports
        for (imp in ast.imports) {
            if (imp.span().matches(line, col)) {
                if (imp.module.span.matches(line, col)) {
                    val mod = mods[imp.module.value] ?: break
                    val loc = locationUri(imp.module.value, mod.ast.sourceName)
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
                            is Expr.Constructor -> location = goto(e.name, e.moduleName ?: ast.name.value)
                        }
                    }
                }
            }
        }
        return location
    }

    private fun newLocation(mod: Module, span: Span) =
        Location(locationUri(mod.name.value, mod.sourceName), IdeUtil.spanToRange(span))

    private fun locationUri(moduleName: String, sourceName: String): String {
        return if (moduleName in Environment.stdlibModuleNames()) {
            val path = server.stdlibFiles[sourceName]!!
            "novah:${Paths.get(path).invariantSeparatorsPathString}"
        } else IdeUtil.fileToUri(sourceName)
    }
}
package novah.ide.features

import novah.ast.canonical.DataConstructor
import novah.ast.canonical.Decl
import novah.ast.canonical.Module
import novah.ast.canonical.show
import novah.ast.source.Import
import novah.frontend.Comment
import novah.frontend.typechecker.PRIM
import novah.frontend.typechecker.TArrow
import novah.frontend.typechecker.primModule
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.lang.StringBuilder
import java.util.concurrent.CompletableFuture

class CompletionFeature(private val server: NovahServer) {

    fun onCompletion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        fun run(): Either<MutableList<CompletionItem>, CompletionList>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val change = server.change() ?: return null
            val env = server.lastSuccessfulEnv() ?: return null
            val (line, col) = params.position.line to params.position.character
            val name = getPartialName(change.txt, line, col) ?: return null
            server.logger().log("received completion request for ${file.absolutePath} at $line:$col text `$name`")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null

            return Either.forLeft(findCompletion(mod.ast, env, name))
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun findCompletion(ast: Module, env: Environment, name: String): MutableList<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()
        // find completions in own module
        completions.addAll(findCompletionInModule(ast, name, null, true))

        ast.imports.forEach { imp ->
            val mod = if (imp.module.value == PRIM) primModule else env.modules()[imp.module.value]!!.ast
            when (imp) {
                is Import.Exposing -> {
                    val exposes = imp.defs.map { it.name }.toSet()
                    val comps = findCompletionInModule(mod, name, imp.alias, pred = { it in exposes })
                    completions.addAll(comps)
                }
                is Import.Raw -> completions.addAll(findCompletionInModule(mod, name, imp.alias))
            }
        }
        return completions
    }

    private fun findCompletionInModule(
        ast: Module,
        name: String,
        alias: String?,
        ownModule: Boolean = false,
        pred: (String) -> Boolean = { true }
    ): MutableList<CompletionItem> {
        val comps = mutableListOf<CompletionItem>()
        val module = ast.name.value
        ast.decls.forEach { d ->
            when (d) {
                is Decl.TypeDecl -> {
                    if ((ownModule || d.isPublic()) && d.name.startsWith(name) && pred(d.name)) {
                        val ci = CompletionItem(name(alias, d.name))
                        ci.kind = CompletionItemKind.Class
                        ci.documentation = getDoc(d.comment, module)
                        ci.detail = d.show()
                        comps += ci
                    }
                    d.dataCtors.forEach { dc ->
                        if ((ownModule || dc.isPublic()) && dc.name.startsWith(name) && pred(dc.name)) {
                            val ci = CompletionItem(name(alias, dc.name))
                            ci.kind = CompletionItemKind.Constructor
                            ci.documentation = getDoc(d.comment, module)
                            ci.detail = getCtorDetails(dc, d.show())
                            comps += ci
                        }
                    }
                }
                is Decl.ValDecl -> {
                    if ((ownModule || d.isPublic()) && d.name.name.startsWith(name) && pred(d.name.name)) {
                        val ci = CompletionItem(name(alias, d.name.name))
                        ci.kind = getKind(d)
                        ci.documentation = getDoc(d.comment, module)
                        ci.detail = getDetail(d)
                        comps += ci
                    }
                }
            }
        }
        return comps
    }

    private fun getDoc(com: Comment?, module: String): Either<String, MarkupContent> {
        val doc = if (com == null) "### $module" else "### $module\n\n${com.comment}"
        return Either.forRight(MarkupContent("markdown", doc))
    }

    private fun getKind(d: Decl.ValDecl): CompletionItemKind {
        if (d.type != null) {
            return if (d.type is TArrow) CompletionItemKind.Function
            else CompletionItemKind.Value
        }
        val typ = d.exp.type
        if (typ != null) {
            return if (typ is TArrow) CompletionItemKind.Function
            else CompletionItemKind.Value
        }
        return CompletionItemKind.Function
    }

    private fun getDetail(d: Decl.ValDecl): String? {
        val typ = d.type ?: d.exp.type ?: return null
        return typ.show(true)
    }

    private fun getCtorDetails(dc: DataConstructor, typeName: String): String {
        return if (dc.args.isEmpty()) typeName
        else (dc.args.map { it.show(true) } + typeName).joinToString(" -> ")
    }

    private fun getPartialName(txt: String, line: Int, col: Int): String? {
        val l = txt.lines().getOrNull(line) ?: return null
        val b = StringBuilder()
        var i = col - 1
        while (i >= 0 && l[i].isLetterOrDigit()) {
            b.append(l[i])
            i--
        }
        return b.toString().reversed()
    }

    private fun name(alias: String?, name: String) = if (alias != null) "$alias.$name" else name
}
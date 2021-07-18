package novah.ide.features

import novah.ast.canonical.*
import novah.ast.source.ForeignImport
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.ast.source.name
import novah.frontend.Comment
import novah.frontend.typechecker.PRIM
import novah.frontend.typechecker.TArrow
import novah.frontend.typechecker.primModule
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
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

            return if (params.context.triggerKind == CompletionTriggerKind.Invoked) {
                val comps = findCompletion(mod.ast, env, name, line + 1, col + 1, incomplete = true)
                Either.forRight(CompletionList(true, comps))
            } else {
                Either.forLeft(findCompletion(mod.ast, env, name, line + 1, col + 1, incomplete = false))
            }
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun findCompletion(
        ast: Module,
        env: Environment,
        name: String,
        line: Int,
        col: Int,
        incomplete: Boolean
    ): MutableList<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()
        val imported = mutableSetOf<String>()

        // find completions in own module
        completions.addAll(findCompletionInModule(ast, name, null, line, col, true))

        // find all imported names
        ast.imports.forEach { imp ->
            imported += imp.module.value
            val mod = if (imp.module.value == PRIM) primModule else env.modules()[imp.module.value]!!.ast
            when (imp) {
                is Import.Exposing -> {
                    val exposes = imp.defs.map { it.name }.toSet()
                    val comps = findCompletionInModule(mod, name, imp.alias, line, col, pred = { it in exposes })
                    completions.addAll(comps)
                }
                is Import.Raw -> completions.addAll(findCompletionInModule(mod, name, imp.alias, line, col))
            }
        }

        // find all imported foreign names
        ast.foreigns.forEach { imp ->
            when (imp) {
                is ForeignImport.Type -> {
                    val type = imp.name()
                    if (type.startsWith(name)) {
                        val ci = CompletionItem(type)
                        ci.detail = imp.type
                        ci.kind = CompletionItemKind.Class
                        completions += ci
                    }
                }
                is ForeignImport.Ctor -> {
                    if (imp.alias.startsWith(name)) {
                        val ci = CompletionItem(imp.alias)
                        ci.detail = imp.type
                        ci.kind = CompletionItemKind.Constructor
                        completions += ci
                    }
                }
                else -> {
                    val foreign = imp.name()
                    if (foreign.startsWith(name)) {
                        val ci = CompletionItem(foreign)
                        ci.kind =
                            if (imp is ForeignImport.Method) CompletionItemKind.Method else CompletionItemKind.Property
                        completions += ci
                    }
                }
            }
        }

        if (incomplete) return completions

        // lastly, add non-imported symbols
        // TODO: non-imported symbols need a code action to import the module
        env.modules().filter { (k, _) -> k !in imported }.forEach { (mod, env) ->
            env.env.decls.forEach { (sym, ref) ->
                if (ref.visibility == Visibility.PUBLIC && sym.startsWith(name)) {
                    val ci = CompletionItem(sym)
                    ci.kind = if (ref.type is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                    ci.detail = ref.type.show(true)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    completions += ci
                }
            }
            env.env.types.forEach { (sym, ref) ->
                // TODO: add type constructors (needs change to `ModuleRefs`)
                if (ref.visibility == Visibility.PUBLIC && sym.startsWith(name)) {
                    val ci = CompletionItem(sym)
                    ci.kind = CompletionItemKind.Class
                    ci.detail = ref.type.show(true)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    completions += ci
                }
            }
        }
        return completions
    }

    private fun findCompletionInModule(
        ast: Module,
        name: String,
        alias: String?,
        line: Int,
        col: Int,
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
                    // local variables
                    if (ownModule && d.span.matches(line, col)) {
                        d.exp.everywhereAccumulating { if (it is Expr.Let) listOf(it) else emptyList() }
                            .filter { it.span.matches(line, col) }
                            .forEach { let ->
                                val binder = let.letDef.binder.name
                                if (!binder.startsWith("$")) {
                                    val ci = CompletionItem(binder)
                                    ci.kind = CompletionItemKind.Value
                                    ci.detail = let.letDef.expr.type?.show(true)
                                    comps += ci
                                }
                            }
                    }
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
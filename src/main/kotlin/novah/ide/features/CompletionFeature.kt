package novah.ide.features

import novah.ast.canonical.*
import novah.ast.source.DeclarationRef
import novah.ast.source.ForeignImport
import novah.ast.source.Import
import novah.ast.source.name
import novah.data.LabelMap
import novah.formatter.Formatter
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.typechecker.*
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.concurrent.CompletableFuture

class CompletionFeature(private val server: NovahServer) {

    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
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

            return when (params.context.triggerKind) {
                CompletionTriggerKind.Invoked -> {
                    val comps = findCompletion(mod.ast, env, name, line + 1, incomplete = true)
                    Either.forRight(CompletionList(true, comps))
                }
                CompletionTriggerKind.TriggerForIncompleteCompletions -> {
                    Either.forLeft(findCompletion(mod.ast, env, name, line + 1, incomplete = false))
                }
                CompletionTriggerKind.TriggerCharacter -> {
                    if (name == "do") null
                    else {
                        val comps = findRecord(mod.ast, env, name, line + 1)
                        comps?.let { Either.forLeft(it.toMutableList()) }
                    }
                }
            }
        }

        return CompletableFuture.supplyAsync(::run)
    }

    private fun findCompletion(
        ast: Module,
        env: Environment,
        name: String,
        line: Int,
        incomplete: Boolean
    ): MutableList<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()
        val imported = mutableSetOf<String>()
        val exposed = mutableMapOf<String, Import.Exposing>()

        // find completions in own module
        completions.addAll(findCompletionInModule(ast, name, null, line, true))

        // find all imported names
        ast.imports.forEach { imp ->
            val mod = if (imp.module.value == PRIM) primModule else env.modules()[imp.module.value]!!.ast
            when (imp) {
                is Import.Exposing -> {
                    exposed[imp.module.value] = imp
                    val exposes = imp.defs.map { it.name }.toSet()
                    val comps = findCompletionInModule(mod, name, imp.alias, line, pred = { it in exposes })
                    completions.addAll(comps)
                }
                is Import.Raw -> {
                    imported += imp.module.value
                    completions.addAll(findCompletionInModule(mod, name, imp.alias, line))
                }
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

        val lastImportLine = ast.imports.maxByOrNull { it.span().startLine }!!.span().endLine
        val (lineToAdd, prefix) = if (lastImportLine < 0) ast.name.span.endLine to "\n" else lastImportLine to ""

        fun makeImportEdit(mod: String, sym: String): TextEdit {
            val fmt = Formatter()
            val expo = exposed[mod]
            return if (expo != null && expo.defs.none { it.name == sym }) {
                val range = IdeUtil.spanToRange(expo.span)
                val decl = if (sym[0].isUpperCase()) DeclarationRef.RefType(sym, Span.empty())
                else DeclarationRef.RefVar(sym, Span.empty())

                val imp = expo.copy(defs = expo.defs + decl)
                server.logger().info("import edit for $mod $sym")
                TextEdit(range, fmt.show(imp))
            } else {
                TextEdit(range(lineToAdd, 1, lineToAdd, 1), "${prefix}import $mod ($sym)\n")
            }
        }

        // lastly, add non-imported symbols
        env.modules().filter { (k, _) -> k !in imported }.forEach { (mod, env) ->
            env.env.decls.forEach { (sym, ref) ->
                // TODO: add type constructors (needs change to `ModuleRefs`)
                if (ref.visibility.isPublic() && sym.startsWith(name) && sym[0].isLowerCase()) {
                    val ci = CompletionItem(sym)
                    ci.kind = if (ref.type is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                    ci.detail = ref.type.show(true)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    ci.additionalTextEdits = listOf(makeImportEdit(mod, sym))
                    completions += ci
                }
            }
            env.env.types.forEach { (sym, ref) ->
                if (ref.visibility.isPublic() && sym.startsWith(name)) {
                    val ci = CompletionItem(sym)
                    ci.kind = CompletionItemKind.Class
                    ci.detail = ref.type.show(true)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    ci.additionalTextEdits = listOf(makeImportEdit(mod, sym))
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
                    // local variables and function parameters
                    if (ownModule && d.span.matchesLine(line)) {
                        val pars = collectParameters(d) { it.startsWith(name) }
                        comps.addAll(pars.map { (binder, type) ->
                            val ci = CompletionItem(binder)
                            ci.detail = type?.show(true)
                            ci.kind = if (type is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                            ci
                        })
                        d.exp.everywhereAccumulating { if (it is Expr.Let) listOf(it) else emptyList() }
                            .filter { it.span.matchesLine(line) }
                            .forEach { let ->
                                val binder = let.letDef.binder.name
                                if (binder.startsWith(name) && !binder.startsWith("$")) {
                                    val ci = CompletionItem(binder)
                                    ci.kind = if (let.letDef.expr.type is TArrow) {
                                        CompletionItemKind.Function
                                    } else CompletionItemKind.Value
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

    private fun findRecord(ast: Module, env: Environment, name: String, line: Int): List<CompletionItem>? {
        fun addLabels(labels: LabelMap<Type>): List<CompletionItem> {
            return labels.map { kv ->
                val ty = kv.value().first()
                val ci = CompletionItem(kv.key())
                ci.kind = if (ty is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                ci.detail = ty.show(true)
                ci
            }
        }

        // search first in the module itself
        val ownModule = env.modules()[ast.name.value]!!
        val rec = ownModule.env.decls[name]
        if (rec != null && rec.type is TRecord) {
            return addLabels((rec.type.row as TRowExtend).labels)
        }

        // then search in the parameters of functions
        for (d in ast.decls) {
            if (d is Decl.ValDecl && d.span.matchesLine(line)) {
                val pars = collectParameters(d) { it == name }
                if (pars.isNotEmpty() && pars[0].second is TRecord) {
                    val (_, ty) = pars[0]
                    return addLabels(((ty as TRecord).row as TRowExtend).labels)
                }
            }
        }

        // finally, search for imported values
        for (imp in ast.imports) {
            val mod = env.modules()[imp.module.value]?.env ?: break
            when (imp) {
                is Import.Exposing -> {
                    for (d in imp.defs) {
                        if (d.name == name) {
                            val decl = mod.decls[name]
                            if (decl != null && decl.type is TRecord) {
                                return addLabels((decl.type.row as TRowExtend).labels)
                            }
                        }
                    }
                }
                is Import.Raw -> {
                    for ((binder, d) in mod.decls) {
                        if (binder == name && d.visibility.isPublic() && d.type is TRecord) {
                            return addLabels((d.type.row as TRowExtend).labels)
                        }
                    }
                }
            }
        }
        return null
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

    private fun collectParameters(d: Decl.ValDecl, pred: (String) -> Boolean): List<Pair<String, Type?>> {
        fun paramType(ty: Type): Type {
            return if (ty is TArrow) ty.args[0]
            else ty
        }

        val comps = mutableListOf<Pair<String, Type?>>()
        var exp = if (d.exp is Expr.Ann) d.exp.exp else d.exp
        while (exp is Expr.Lambda) {
            val binder = exp.binder.name
            if (pred(binder) && !binder.startsWith("$")) {
                comps += exp.binder.name to exp.type?.let(::paramType)
            }
            exp = if (exp.body is Expr.Ann) (exp.body as Expr.Ann).exp else exp.body
        }
        return comps
    }

    private fun getPartialName(txt: String, line: Int, col: Int): String? {
        val l = txt.lines().getOrNull(line) ?: return null
        val b = StringBuilder()
        var i = col - 1
        if (col > 0 && l[i] == '.') i--
        while (i >= 0 && l[i].isLetterOrDigit()) {
            b.append(l[i])
            i--
        }
        return b.toString().reversed()
    }

    private fun name(alias: String?, name: String) = if (alias != null) "$alias.$name" else name

    companion object {
        private fun range(sLine: Int, sCol: Int, eLine: Int, eCol: Int) =
            Range(Position(sLine, sCol), Position(eLine, eCol))
    }
}
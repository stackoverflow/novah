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
import novah.data.LabelMap
import novah.data.Reflection
import novah.data.mapBoth
import novah.formatter.Formatter
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.typechecker.*
import novah.ide.EnvResult
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.Environment
import novah.main.ModuleEnv
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

class CompletionFeature(private val server: NovahServer) {

    private var typeVarsMap = mapOf<Int, String>()

    @Suppress("WHEN_ENUM_CAN_BE_NULL_IN_JAVA")
    fun onCompletion(params: CompletionParams): CompletableFuture<Either<MutableList<CompletionItem>, CompletionList>> {
        fun run(envRes: EnvResult): Either<MutableList<CompletionItem>, CompletionList>? {
            val file = IdeUtil.uriToFile(params.textDocument.uri)

            val change = envRes.change ?: return null
            if (change.txt == null) return null
            val env = server.lastSuccessfulEnv() ?: return null
            val (lineC, colC) = params.position.line to params.position.character
            val name = getPartialName(change.txt, lineC, colC) ?: return null
            server.logger().log("received completion request for ${file.absolutePath} at $lineC:$colC text `$name`")
            val moduleName = env.sourceMap()[file.toPath()] ?: return null
            val mod = env.modules()[moduleName] ?: return null
            typeVarsMap = mod.typeVarsMap
            val line = lineC + 1
            val ctx = findContext(change.txt, line, colC + 1)

            return when (params.context.triggerKind) {
                CompletionTriggerKind.Invoked -> {
                    val comps = complete(mod.ast, env, name, line, ctx, incomplete = true)
                    Either.forRight(CompletionList(true, comps))
                }
                CompletionTriggerKind.TriggerForIncompleteCompletions -> {
                    Either.forLeft(complete(mod.ast, env, name, line, ctx, incomplete = false))
                }
                CompletionTriggerKind.TriggerCharacter -> {
                    when {
                        name == "do" -> null
                        params.context.triggerCharacter == "#" -> {
                            val comps = findInJava(name, env, mod.ast, line)
                            server.logger().log("providing completion: ${comps?.size}")
                            comps?.let { Either.forLeft(it) }
                        }
                        else -> {
                            val comps = if (name[0].isUpperCase()) {
                                findNamespacedDecl(mod.ast, env, name, false)
                            } else findRecord(mod.ast, env, name, line)
                            comps?.let { Either.forLeft(it.toMutableList()) }
                        }
                    }
                }
            }
        }

        return server.runningEnv().thenApply(::run)
    }

    private fun complete(
        ast: Module,
        env: Environment,
        name: String,
        line: Int,
        context: Context,
        incomplete: Boolean
    ): MutableList<CompletionItem> = when (context) {
        is Context.NoCompletion -> mutableListOf()
        is Context.ModuleCtx -> findModule(name, env)
        is Context.ImportCtx -> findImport(name, context.mod, env)
        is Context.TypeCtx -> {
            val split = name.split(".")
            if (split.size < 2) findCompletion(ast, env, name, line, true, incomplete)
            else findNamespacedDecl(ast, env, split[0], true, split[1])
        }
        is Context.AnyCtx -> {
            val split = name.split(".")
            if (split.size < 2) findCompletion(ast, env, name, line, false, incomplete)
            else findNamespacedDecl(ast, env, split[0], false, split[1])
        }
    }

    private fun findCompletion(
        ast: Module,
        env: Environment,
        name: String,
        line: Int,
        typesOnly: Boolean,
        incomplete: Boolean
    ): MutableList<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()
        val imported = mutableSetOf<String>()
        val exposed = mutableMapOf<String, Import.Exposing>()

        // find completions in own module
        completions.addAll(findCompletionInModule(ast, name, null, line, typesOnly, true))

        // find all imported names
        ast.imports.forEach { imp ->
            val alias = imp.alias()
            // add import aliases to completions
            if (alias != null) {
                val ci = CompletionItem(alias)
                ci.detail = imp.module.value
                ci.kind = CompletionItemKind.Module
                completions += ci
            }

            val mod = if (imp.module.value == PRIM) primModule else env.modules()[imp.module.value]!!.ast
            when (imp) {
                is Import.Exposing -> {
                    exposed[imp.module.value] = imp
                    val exposes = imp.defs.map { it.name }.toSet()
                    val comps = findCompletionInModule(mod, name, imp.alias, line, typesOnly, pred = { it in exposes })
                    completions.addAll(comps)
                }
                is Import.Raw -> {
                    imported += imp.module.value
                    completions.addAll(findCompletionInModule(mod, name, imp.alias, line, typesOnly))
                }
            }
        }

        // find all imported foreign names
        for (imp in ast.foreigns) {
            val type = imp.name()
            if (type.startsWith(name)) {
                val ci = CompletionItem(type)
                ci.detail = imp.type
                ci.kind = CompletionItemKind.Class
                completions += ci
            }
        }

        if (incomplete) return completions

        val lastImportLine = ast.imports.maxByOrNull { it.span().startLine }!!.span().endLine
        val (lineToAdd, prefix) = if (lastImportLine < 0) ast.name.span.endLine to "\n" else lastImportLine to ""

        // TODO: auto insert of imports is based on the last successful build
        //       so it overwrites or adds to the wrong place sometimes
        fun makeImportEdit(mod: String, sym: String, type: String?): TextEdit {
            val expo = exposed[mod]
            return if (expo != null && expo.defs.none { it.name == sym }) {
                val range = IdeUtil.spanToRange(expo.span)
                val ref = expo.defs.find { it.name == type } as? DeclarationRef.RefType

                // the type is already imported, just add the constructor
                if (ref != null) {
                    val defs = expo.defs.map { d ->
                        if (d.name == type && d is DeclarationRef.RefType) {
                            val ctors = when {
                                d.ctors != null && (sym in d.ctors.map { it.value } || d.ctors.isEmpty()) -> d.ctors
                                d.ctors != null -> d.ctors + Spanned.empty(sym)
                                else -> listOf(Spanned.empty(sym))
                            }
                            d.copy(ctors = ctors)
                        } else d
                    }
                    val imp = expo.copy(defs = defs)
                    return TextEdit(range, Formatter().show(imp))
                }
                val decl = when {
                    type != null -> {
                        DeclarationRef.RefType(Spanned(Span.empty(), type), Span.empty(), listOf(Spanned.empty(sym)))
                    }
                    sym[0].isUpperCase() -> DeclarationRef.RefType(Spanned(Span.empty(), sym), Span.empty())
                    else -> DeclarationRef.RefVar(sym, Span.empty())
                }

                val imp = expo.copy(defs = expo.defs + decl)
                TextEdit(range, Formatter().show(imp))
            } else {
                val imp = if (type != null) "$type($sym)" else sym
                TextEdit(range(lineToAdd, 1, lineToAdd, 1), "${prefix}import $mod ($imp)\n")
            }
        }

        // lastly, add non-imported symbols
        for ((mod, envv) in env.modules().filter { (k, _) -> k !in imported }) {
            val ctorMap = makeCtorMap(envv.env)

            envv.env.types.forEach { (sym, ref) ->
                if (ref.visibility.isPublic() && sym.startsWith(name)) {
                    val ci = CompletionItem(sym)
                    ci.kind = CompletionItemKind.Class
                    ci.detail = ref.type.show(qualified = true, typeVarsMap = typeVarsMap)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    ci.additionalTextEdits = listOf(makeImportEdit(mod, sym, null))
                    completions += ci
                }
            }
            if (typesOnly) continue
            envv.env.decls.forEach { (sym, ref) ->
                if (ref.visibility.isPublic() && sym.startsWith(name)) {
                    val ci = CompletionItem(sym)
                    ci.kind = if (ref.type is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                    ci.detail = ref.type.show(qualified = true, typeVarsMap = typeVarsMap)
                    ci.documentation = Either.forRight(MarkupContent("markdown", "### $mod"))
                    ci.additionalTextEdits = listOf(makeImportEdit(mod, sym, ctorMap[sym]))
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
        typesOnly: Boolean,
        ownModule: Boolean = false,
        pred: (String) -> Boolean = { true }
    ): MutableList<CompletionItem> {
        val comps = mutableListOf<CompletionItem>()
        val module = ast.name.value
        for (d in ast.decls) {
            when (d) {
                is Decl.TypeDecl -> {
                    val tname = d.name.value
                    if ((ownModule || d.isPublic()) && tname.startsWith(name) && pred(tname)) {
                        val ci = CompletionItem(name(alias, tname))
                        ci.kind = CompletionItemKind.Class
                        ci.documentation = getDoc(d.comment, module)
                        ci.detail = d.show()
                        comps += ci
                    }
                    if (typesOnly) continue
                    d.dataCtors.forEach { dc ->
                        val dcname = dc.name.value
                        if ((ownModule || dc.isPublic()) && dcname.startsWith(name) && pred(dcname)) {
                            val ci = CompletionItem(name(alias, dcname))
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
                            ci.detail = type?.show(qualified = true, typeVarsMap = typeVarsMap)
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
                                    ci.detail = let.letDef.expr.type?.show(qualified = true, typeVarsMap = typeVarsMap)
                                    comps += ci
                                }
                            }
                    }
                    if ((ownModule || d.isPublic()) && d.name.value.startsWith(name) && pred(d.name.value)) {
                        val ci = CompletionItem(name(alias, d.name.value))
                        ci.kind = getKind(d)
                        ci.detail = getDetail(d)
                        ci.documentation = getDoc(d.comment, module)
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
                ci.detail = ty.show(qualified = true, typeVarsMap = typeVarsMap)
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
                break
            }
        }

        // finally, search for imported values
        for (imp in ast.imports) {
            val mod = env.modules()[imp.module.value]?.env ?: continue
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

    private fun findNamespacedDecl(
        ast: Module,
        env: Environment,
        alias: String,
        typesOnly: Boolean,
        toFind: String = ""
    ): MutableList<CompletionItem> {
        val comps = mutableListOf<CompletionItem>()

        val imp = ast.imports.find { it.alias() == alias } ?: return comps
        val modName = imp.module.value
        val mod = env.modules()[modName]?.env ?: return comps
        when (imp) {
            is Import.Exposing -> {
                for (d in imp.defs) {
                    if (!d.name.startsWith(toFind)) continue
                    val tdecl = mod.types[d.name]
                    if (tdecl != null) {
                        val ci = CompletionItem(d.name)
                        ci.kind = CompletionItemKind.Class
                        ci.detail = tdecl.type.show(qualified = true, typeVarsMap = typeVarsMap)
                        ci.documentation = getDoc(tdecl.comment, modName)
                        comps += ci
                    }
                    if (typesOnly) continue

                    val decl = mod.decls[d.name]
                    if (decl != null) {
                        val ci = CompletionItem(d.name)
                        ci.kind = if (decl.type is TArrow) {
                            CompletionItemKind.Function
                        } else CompletionItemKind.Value
                        ci.detail = decl.type.show(qualified = true, typeVarsMap = typeVarsMap)
                        ci.documentation = getDoc(decl.comment, modName)
                        comps += ci
                    }
                }
            }
            is Import.Raw -> {
                for ((sym, d) in mod.types) {
                    if (!d.visibility.isPublic() || !sym.startsWith(toFind)) continue
                    val ci = CompletionItem(sym)
                    ci.kind = CompletionItemKind.Class
                    ci.detail = d.type.show(qualified = true, typeVarsMap = typeVarsMap)
                    ci.documentation = getDoc(d.comment, modName)
                    comps += ci
                }
                if (!typesOnly) {
                    for ((sym, d) in mod.decls) {
                        if (!d.visibility.isPublic() || !sym.startsWith(toFind)) continue
                        val ci = CompletionItem(sym)
                        ci.kind = if (d.type is TArrow) {
                            CompletionItemKind.Function
                        } else CompletionItemKind.Value
                        ci.detail = d.type.show(qualified = true, typeVarsMap = typeVarsMap)
                        ci.documentation = getDoc(d.comment, modName)
                        comps += ci
                    }
                }
            }
        }
        return comps
    }

    private fun findInJava(name: String, env: Environment, mod: Module, line: Int): MutableList<CompletionItem>? {
        val comps = mutableListOf<CompletionItem>()

        fun genFields(fields: List<Field>) {
            fields.forEach {
                val comp = CompletionItem(it.name)
                comp.kind = CompletionItemKind.Field
                comp.detail = it.genericType.typeName
                val text = if (it.name[0].isUpperCase()) "`${it.name}`" else it.name
                comp.insertText = "-$text"
                comps += comp
            }
        }

        fun genMethods(methods: List<Method>) {
            methods.forEach { m ->
                val comp = CompletionItem(m.name)
                comp.kind = CompletionItemKind.Method
                var details = m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.typeName }
                details += " : ${m.returnType.typeName}"
                comp.detail = details
                var i = 1
                val format = m.parameterTypes.joinToString { "\${${i++}:${it.simpleName}}" }
                val method = if (m.name[0].isUpperCase()) "`${m.name}`" else m.name
                comp.insertText = "$method($format)"
                comp.insertTextFormat = InsertTextFormat.Snippet
                comps += comp
            }
        }
        
        fun genAllNonStatic(ty: Type) {
            val jtype = Reflection.findJavaType(ty) ?: return
            val clazz = env.classLoader().safeFindClass(jtype) ?: return
            val fields = clazz.fields.filter { Reflection.isPublic(it) && !Reflection.isStatic(it) }
            val methods =
                clazz.methods.filter { Reflection.isPublic(it) && !Reflection.isStatic(it) && !it.isBridge }
            genFields(fields)
            genMethods(methods)
        }

        if (name[0].isUpperCase()) { // Static method/field/constructor
            val fqtImp = mod.foreigns.find { it.name() == name }?.type
            val fqt = fqtImp ?: findJavaClass(name) ?: return null
            val clazz = env.classLoader().safeFindClass(fqt) ?: return null
            val fields = clazz.fields.filter { Reflection.isPublic(it) && Reflection.isStatic(it) }
            val methods = clazz.methods.filter { Reflection.isPublic(it) && Reflection.isStatic(it) && !it.isBridge }
            val ctors = clazz.constructors.filter { Reflection.isPublic(it) }
            genFields(fields)
            genMethods(methods)
            ctors.forEach { c ->
                val comp = CompletionItem("new")
                comp.kind = CompletionItemKind.Constructor
                val details = c.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.typeName }
                var i = 1
                val format = c.parameterTypes.joinToString { "\${${i++}:${it.simpleName}}" }
                comp.insertText = "new($format)"
                comp.insertTextFormat = InsertTextFormat.Snippet
                comp.detail = details
                comps += comp
            }
        } else { // non-static method/field
            // search first in the module itself
            val ownModule = env.modules()[mod.name.value]!!
            val obj = ownModule.env.decls[name]
            if (obj != null) {
                genAllNonStatic(obj.type)
                return comps
            }

            // then search in the parameters of functions
            for (d in mod.decls) {
                if (d is Decl.ValDecl && d.span.matchesLine(line)) {
                    val pars = collectParameters(d) { it == name }
                    if (pars.isNotEmpty()) {
                        val (_, ty) = pars[0]
                        if (ty == null) return null
                        genAllNonStatic(ty)
                        return comps
                    }
                    break
                }
            }

            // finally, search for imported values
            for (imp in mod.imports) {
                val m = env.modules()[imp.module.value]?.env ?: continue
                when (imp) {
                    is Import.Exposing -> {
                        for (d in imp.defs) {
                            if (d.name == name) {
                                val decl = m.decls[name]
                                if (decl != null) {
                                    genAllNonStatic(decl.type)
                                    return comps
                                }
                            }
                        }
                    }
                    is Import.Raw -> {
                        for ((binder, d) in m.decls) {
                            if (binder == name && d.visibility.isPublic()) {
                                genAllNonStatic(d.type)
                                return comps
                            }
                        }
                    }
                }
            }
        }
        return comps
    }

    private fun findImport(toFind: String, mod: String, env: Environment): MutableList<CompletionItem> {
        val completions = mutableListOf<CompletionItem>()

        val fmenv = env.modules()[mod] ?: return completions
        for ((name, decl) in fmenv.env.decls) {
            if (!decl.visibility.isPublic()) break
            if (name.startsWith(toFind)) {
                val ci = CompletionItem(name)
                ci.kind = if (decl.type is TArrow) CompletionItemKind.Function else CompletionItemKind.Value
                ci.detail = decl.type.show(qualified = true, typeVarsMap = fmenv.typeVarsMap)
                ci.documentation = getDoc(decl.comment, mod)
                completions += ci
            }
        }

        for ((name, tdecl) in fmenv.env.types) {
            if (!tdecl.visibility.isPublic()) break
            if (name.startsWith(toFind)) {
                val ci = CompletionItem(name)
                ci.kind = CompletionItemKind.Class
                ci.detail = tdecl.type.show(qualified = true, typeVarsMap = fmenv.typeVarsMap)
                ci.documentation = getDoc(tdecl.comment, mod)
                completions += ci
            }
        }

        for (alias in fmenv.aliases) {
            if (!alias.visibility.isPublic()) break
            if (alias.name.startsWith(toFind)) {
                val ci = CompletionItem(alias.name)
                ci.kind = CompletionItemKind.Class
                ci.detail = alias.type.show()
                ci.documentation = getDoc(alias.comment, mod)
                completions += ci
            }
        }

        return completions
    }

    private fun findModule(name: String, env: Environment): MutableList<CompletionItem> {
        return env.modules().filter { (k, _) -> k.startsWith(name) }.map { (k, v) ->
            val prefix = name.lastIndexOf('.')
            val comp = if (prefix != -1) k.substring(prefix + 1) else k
            val ci = CompletionItem(comp)
            ci.kind = CompletionItemKind.Module
            ci.detail = "module $k"
            ci.documentation = getDoc(v.comment, k)
            ci
        }.toMutableList()
    }

    private fun findContext(code: String, line: Int, col: Int): Context {
        return IdeUtil.parseCode(code).mapBoth({ mod ->
            for (imp in mod.imports) {
                if (imp.span().matches(line, col)) {
                    return if (imp.module.span.matches(line, col)) Context.ModuleCtx
                    else if (imp.module.span.after(line, col)) Context.ImportCtx(imp.module.value)
                    else Context.NoCompletion
                }
            }

            for (d in mod.decls) {
                if (d.span.matches(line, col)) {
                    return when (d) {
                        is novah.ast.source.Decl.TypeDecl -> Context.TypeCtx
                        is novah.ast.source.Decl.TypealiasDecl -> Context.TypeCtx
                        is novah.ast.source.Decl.ValDecl -> Context.AnyCtx
                    }
                }
            }

            Context.NoCompletion
        }) { Context.AnyCtx }
    }

    private fun getDetail(d: Decl.ValDecl): String? {
        val typ = d.signature?.type ?: d.exp.type ?: return null
        return typ.show(qualified = true, typeVarsMap = typeVarsMap)
    }

    private fun getCtorDetails(dc: DataConstructor, typeName: String): String {
        return if (dc.args.isEmpty()) typeName
        else (dc.args.map { it.show(qualified = true, typeVarsMap = typeVarsMap) } + typeName).joinToString(" -> ")
    }

    private fun findJavaClass(name: String): String? = when (name) {
        "Int", "Int32" -> "java.lang.Integer"
        "String" -> "java.lang.String"
        "Char" -> "java.lang.Character"
        "Int64" -> "java.lang.Long"
        "Byte" -> "java.lang.Byte"
        "Int16" -> "java.lang.Short"
        "Float32" -> "java.lang.Float"
        "Float64" -> "java.lang.Double"
        "Boolean" -> "java.lang.Boolean"
        "BigInteger" -> "java.math.BigInteger"
        "BigDecimal" -> "java.math.BigDecimal"
        "Object" -> "java.lang.Object"
        "List" -> "io.lacuna.bifurcan.List"
        "Set" -> "io.lacuna.bifurcan.Set"
        "Map" -> "io.lacuna.bifurcan.Map"
        "Range" -> "novah.range.Range"
        else -> null
    }

    sealed class Context {
        data class ImportCtx(val mod: String) : Context()
        object ModuleCtx : Context()
        object TypeCtx : Context()
        object AnyCtx : Context()
        object NoCompletion : Context()
    }

    companion object {
        private fun getDoc(com: Comment?, module: String): Either<String, MarkupContent> {
            val doc = if (com == null) "### $module" else "### $module\n\n${com.comment}"
            return Either.forRight(MarkupContent("markdown", doc))
        }

        private fun getKind(d: Decl.ValDecl): CompletionItemKind {
            if (d.signature?.type != null) {
                return if (d.signature.type is TArrow) CompletionItemKind.Function
                else CompletionItemKind.Value
            }
            val typ = d.exp.type
            if (typ != null) {
                return if (typ is TArrow) CompletionItemKind.Function
                else CompletionItemKind.Value
            }
            return CompletionItemKind.Function
        }

        private fun collectParameters(d: Decl.ValDecl, pred: (String) -> Boolean): List<Pair<String, Type?>> {
            fun paramType(ty: Type): Type {
                return if (ty is TArrow) ty.args[0]
                else ty
            }

            val comps = mutableListOf<Pair<String, Type?>>()
            d.exp.everywhereUnit { e ->
                when (e) {
                    is Expr.Lambda -> {
                        val binder = e.binder.name
                        if (pred(binder) && !binder.startsWith("$")) {
                            comps += binder to e.type?.let(::paramType)
                        }
                    }
                    is Expr.Let -> {
                        val binder = e.letDef.binder.name
                        if (pred(binder) && !binder.startsWith("$")) {
                            comps += binder to e.letDef.expr.type
                        }
                    }
                    else -> {}
                }
            }
            return comps
        }

        private fun getPartialName(txt: String, line: Int, col: Int): String? {
            val l = txt.lines().getOrNull(line) ?: return null
            val b = StringBuilder()
            var i = col - 1
            if (col > 0 && (l[i] == '.' || l[i] == '#')) i--
            while (i >= 0 && (l[i].isLetterOrDigit() || l[i] == '.')) {
                b.append(l[i])
                i--
            }
            val res = b.toString().reversed()
            return res.ifBlank { null }
        }

        private fun makeCtorMap(menv: ModuleEnv): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for ((name, ref) in menv.types) {
                for (ctor in ref.ctors) {
                    map[ctor] = name
                }
            }
            return map
        }

        private fun name(alias: String?, name: String) = if (alias != null) "$alias.$name" else name

        @Suppress("SameParameterValue")
        private fun range(sLine: Int, sCol: Int, eLine: Int, eCol: Int) =
            Range(Position(sLine, sCol), Position(eLine, eCol))
    }
}
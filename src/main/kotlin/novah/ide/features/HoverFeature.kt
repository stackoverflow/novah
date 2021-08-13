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
import novah.frontend.Comment
import novah.frontend.Lexer
import novah.frontend.typechecker.Type
import novah.ide.IdeUtil
import novah.ide.NovahServer
import novah.main.DeclRef
import novah.main.FullModuleEnv
import novah.main.TypeDeclRef
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.MarkupContent
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CompletableFuture

class HoverFeature(private val server: NovahServer) {

    private val typeVarsMap = mutableMapOf<Int, String>()

    fun onHover(params: HoverParams): CompletableFuture<Hover> {
        fun run(): Hover? {
            val path = IdeUtil.uriToFile(params.textDocument.uri)
            val env = server.env()
            val moduleName = env.sourceMap()[path.toPath()] ?: return null

            val mod = env.modules()[moduleName] ?: return null
            val line = params.position.line + 1
            val col = params.position.character + 1
            //server.logger().log("hovering on ${mod.ast.name.value} $line:$col")

            val ctx = findContext(line, col, mod.ast, env.modules()) ?: return null
            typeVarsMap.putAll(mod.typeVarsMap)

            return Hover(MarkupContent("markdown", contextToHover(ctx)))
        }
        return CompletableFuture.supplyAsync(::run)
    }

    private fun contextToHover(ctx: HoverCtx): String {
        return when (ctx) {
            is ModuleCtx -> {
                val name = ctx.name
                val source = if (ctx.alias != null) "module $name as ${ctx.alias}" else "module $name"
                makeHover(source, ctx.mod.comment)
            }
            is ImportDeclCtx -> {
                val ref = ctx.ref
                var source = "module ${ctx.module}\n\n"
                if (ref.visibility.isPublic()) {
                    source += if (ref.isInstance) "pub instance\n"
                    else "pub\n"
                }
                val name = if (Lexer.isOperator(ctx.name)) "(${ctx.name})" else ctx.name
                source += "$name : ${ref.type.show(qualified = true, typeVarsMap = ctx.tvars)}"
                makeHover(source, ref.comment)
            }
            is ImportTypeDeclCtx -> {
                val ref = ctx.ref
                var source = "module ${ctx.module}\n\n"
                if (ref.visibility.isPublic()) source += "pub\n"
                if (ref.isOpaque) source += "opaque "
                source += "type ${ctx.name} : ${ref.type.show(qualified = true, typeVarsMap = ctx.tvars)}"
                makeHover(source, ref.comment)
            }
            is DeclCtx -> {
                val d = ctx.decl
                var source = ""
                if (d.isPublic()) {
                    source += if (d.isInstance) "pub instance\n"
                    else "pub\n"
                }
                source += if (d.isOperator) "(${d.name.name})" else d.name.name
                val type = d.signature?.type ?: d.exp.type
                if (type != null) {
                    source += " : ${type.show(qualified = true, typeVarsMap = typeVarsMap)}"
                }
                makeHover(source, d.comment)
            }
            is LetCtx -> {
                val name = if (Lexer.isOperator(ctx.let.binder.name)) "(${ctx.let.binder.name})"
                else ctx.let.binder.name
                var source = if (ctx.let.isInstance) "instance\n$name" else name
                source += " : ${ctx.type.show(qualified = true, typeVarsMap = typeVarsMap)}"
                novah(source)
            }
            is LocalRefCtx -> {
                val name = if (Lexer.isOperator(ctx.name)) "(${ctx.name})" else ctx.name
                novah("$name : ${ctx.type.show(qualified = true, typeVarsMap = typeVarsMap)}")
            }
            is MethodCtx -> {
                java(ctx.method.toString()) + "\n***\n" +
                        novah("${ctx.name} : ${ctx.type.show(qualified = true, typeVarsMap = typeVarsMap)}")
            }
            is CtorCtx -> {
                val src = "(constructor)\n${ctx.name} : ${ctx.type.show(qualified = true, typeVarsMap = typeVarsMap)}"
                java(ctx.ctor.toString()) + "\n***\n" + novah(src)
            }
            is FieldCtx -> {
                val kind = if (ctx.getter) "getter" else "setter"
                java(ctx.field.toString()) + "\n***\n" +
                        novah("($kind)\n${ctx.name} : ${ctx.type.show(qualified = true, typeVarsMap = typeVarsMap)}")
            }
        }
    }

    private sealed class HoverCtx
    private class ModuleCtx(val name: String, val alias: String?, val mod: Module) : HoverCtx()
    private class ImportDeclCtx(val name: String, val module: String, val ref: DeclRef, val tvars: Map<Int, String>) :
        HoverCtx()

    private class ImportTypeDeclCtx(
        val name: String,
        val module: String,
        val ref: TypeDeclRef,
        val tvars: Map<Int, String>
    ) : HoverCtx()

    private class DeclCtx(val decl: Decl.ValDecl) : HoverCtx()
    private class LocalRefCtx(val name: String, val type: Type) : HoverCtx()
    private class LetCtx(val let: LetDef, val type: Type) : HoverCtx()
    private class MethodCtx(val name: String, val type: Type, val method: Method) : HoverCtx()
    private class CtorCtx(val name: String, val type: Type, val ctor: Constructor<*>) : HoverCtx()
    private class FieldCtx(val name: String, val type: Type, val field: Field, val getter: Boolean) : HoverCtx()

    private fun findContext(line: Int, col: Int, ast: Module, mods: Map<String, FullModuleEnv>): HoverCtx? {

        fun searchTypeRefs(d: DeclarationRef.RefType, moduleName: String, fmv: FullModuleEnv): HoverCtx? {
            // TODO search aliases
            if (d.binder.span.matches(line, col)) {
                val ref = fmv.env.types[d.name]
                return if (ref != null) {
                    ImportTypeDeclCtx(d.name, moduleName, ref, fmv.typeVarsMap)
                } else null
            }
            if (d.ctors == null || d.ctors.isEmpty()) return null
            for (ctor in d.ctors) {
                if (ctor.span.matches(line, col)) {
                    val ref = fmv.env.decls[ctor.value]
                    return if (ref != null) {
                        ImportDeclCtx(ctor.value, moduleName, ref, fmv.typeVarsMap)
                    } else null
                }
            }
            return null
        }

        // search imports
        for (imp in ast.imports) {
            if (imp.span().matches(line, col)) {
                // context is import
                val modName = imp.module.value
                val fmv = mods[modName]!!
                if (imp.module.span.matches(line, col)) return ModuleCtx(modName, imp.alias(), fmv.ast)
                if (imp is Import.Exposing) {
                    for (d in imp.defs) {
                        if (d.span.matches(line, col)) {
                            return when (d) {
                                is DeclarationRef.RefVar -> {
                                    val ref = fmv.env.decls[d.name]
                                    if (ref != null) {
                                        ImportDeclCtx(d.name, modName, ref, fmv.typeVarsMap)
                                    } else null
                                }
                                is DeclarationRef.RefType -> searchTypeRefs(d, modName, fmv)
                            }
                        }
                    }
                }
                return null
            }
        }

        val ownMod = mods[ast.name.value]!!

        fun searchExpression(d: Decl.ValDecl): HoverCtx? {
            var ctx: HoverCtx? = null
            d.exp.everywhereUnit { e ->
                if (ctx == null && e.span.matches(line, col)) {
                    when (e) {
                        is Expr.Var -> {
                            val ownRef = ownMod.env.decls[e.name]
                            if (e.moduleName != null) {
                                val v = mods[e.moduleName]?.env?.decls?.get(e.name)
                                if (v != null) {
                                    ctx = ImportDeclCtx(e.name, e.moduleName, v, mods[e.moduleName]!!.typeVarsMap)
                                }
                            } else if (ownRef != null) {
                                ctx = ImportDeclCtx(e.name, ast.name.value, ownRef, ownMod.typeVarsMap)
                            } else if (!e.name.startsWith("var$")) {
                                ctx = LocalRefCtx(e.name, e.type!!)
                            }
                        }
                        is Expr.ImplicitVar -> {
                            val ownRef = ownMod.env.decls[e.name]
                            if (e.moduleName != null) {
                                val v = mods[e.moduleName]?.env?.decls?.get(e.name)
                                if (v != null) {
                                    ctx = ImportDeclCtx(e.name, e.moduleName, v, mods[e.moduleName]!!.typeVarsMap)
                                }
                            } else if (ownRef != null) {
                                ctx = ImportDeclCtx(e.name, ast.name.value, ownRef, ownMod.typeVarsMap)
                            } else if (!e.name.startsWith("var$")) {
                                ctx = LocalRefCtx(e.name, e.type!!)
                            }
                        }
                        is Expr.Constructor -> {
                            val ownRef = ownMod.env.decls[e.name]
                            if (e.moduleName != null) {
                                val v = mods[e.moduleName]?.env?.decls?.get(e.name)
                                if (v != null) {
                                    ctx = ImportDeclCtx(e.name, e.moduleName, v, mods[e.moduleName]!!.typeVarsMap)
                                }
                            } else if (ownRef != null) {
                                ctx = ImportDeclCtx(e.name, ast.name.value, ownRef, ownMod.typeVarsMap)
                            } else if (!e.name.startsWith("var$")) {
                                ctx = LocalRefCtx(e.name, e.type!!)
                            }
                        }
                        is Expr.Lambda -> {
                            val bind = e.binder
                            if (bind.span.matches(line, col)
                                && !bind.name.startsWith("var$")
                                && bind.type != null
                            ) {
                                ctx = LocalRefCtx(bind.name, bind.type!!)
                            }
                        }
                        is Expr.Match -> {
                            e.cases.forEach { c ->
                                c.patterns.forEach { pat ->
                                    if (pat.span.matches(line, col)) {
                                        pat.everywhereUnit { p ->
                                            if (p.span.matches(line, col)) {
                                                when (p) {
                                                    is Pattern.Var -> {
                                                        if (p.type != null) ctx = LocalRefCtx(p.name, p.type!!)
                                                    }
                                                    is Pattern.Ctor -> {
                                                        if (p.type != null && p.ctor.span.matches(line, col))
                                                            ctx = LocalRefCtx(p.ctor.name, p.type!!)
                                                    }
                                                    is Pattern.Wildcard -> {
                                                        if (p.type != null) ctx = LocalRefCtx("_", p.type!!)
                                                    }
                                                    is Pattern.Named -> {
                                                        if (p.type != null) ctx = LocalRefCtx(p.name.value, p.type!!)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        is Expr.Let -> {
                            val bind = e.letDef.binder
                            if (bind.span.matches(line, col) && e.letDef.expr.type != null) {
                                ctx = LetCtx(e.letDef, e.letDef.expr.type!!)
                            }
                        }
                        is Expr.NativeMethod -> ctx = MethodCtx(e.name, e.type!!, e.method)
                        is Expr.NativeConstructor -> ctx = CtorCtx(e.name, e.type!!, e.ctor)
                        is Expr.NativeFieldGet -> ctx = FieldCtx(e.name, e.type!!, e.field, getter = true)
                        is Expr.NativeFieldSet -> ctx = FieldCtx(e.name, e.type!!, e.field, getter = false)
                        is Expr.RecordSelect -> {
                            if (e.label.span.matches(line, col))
                                ctx = LocalRefCtx(e.label.value, e.type!!)
                        }
                    }
                }
            }
            return ctx
        }

        // search value declarations
        for (d in ast.decls.filterIsInstance<Decl.ValDecl>()) {
            if (d.span.matches(line, col)) {
                // context is value declaration
                if (d.name.span.matches(line, col)) return DeclCtx(d)
                if (d.signature != null && d.signature.span.matches(line, col)) return DeclCtx(d)
                if (d.exp.span.matches(line, col)) return searchExpression(d)
            }
        }

        return null
    }

    companion object {
        private fun commentToMarkdown(c: Comment) = c.comment.replace("\n", "  \n")

        private fun novah(src: String) = "```novah\n$src\n```"

        private fun java(src: String) = "```java\n$src\n```"

        private fun makeHover(source: String, comment: Comment?): String {
            val prefix = novah(source)
            return if (comment != null) "$prefix\n***\n${commentToMarkdown(comment)}" else prefix
        }
    }
}
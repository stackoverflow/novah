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
package novah.formatter

import novah.Util.joinToStr
import novah.ast.source.*
import novah.data.show
import novah.frontend.Comment

/**
 * The canonical formatter for the language. Like gofmt
 */
class Formatter {

    private val builder = StringBuilder()
    private var tab = ""

    fun format(ast: Module): String = show(ast)

    fun show(m: Module): String {
        val cmt = if (m.comment != null) {
            show(m.comment!!, true)
        } else ""
        builder.append(cmt)

        builder.append("module ${m.name}")

        if (m.imports.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(m.imports.sortedBy { it.module }.joinToString("\n") { show(it) })
        }
        if (m.foreigns.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(m.foreigns.joinToString("\n") { show(it) })
        }
        if (m.decls.isNotEmpty()) {
            builder.append("\n\n")
            for (d in m.decls) {
                if (d != m.decls[0]) {
                    builder.append("\n\n")
                }
                builder.append(show(d))
            }
        }
        return builder.toString()
    }

    fun show(i: Import): String {
        val cmt = if (i.comment != null) show(i.comment!!, true) else ""
        var exposes = ""
        if (i is Import.Exposing) {
            exposes = i.defs.joinToString(", ", prefix = " (", postfix = ")")
            if (exposes.length > maxColumns) exposes = showList(i.defs.map { it.toString() })
        }
        val alias = if (i.alias() != null) " as ${i.alias()}" else ""
        return "${cmt}import ${i.module}$exposes$alias"
    }

    fun show(fi: ForeignImport): String {
        // TODO: show comments
        fun alias(al: String?) = if (al != null) " as $al" else ""
        fun sep(static: Boolean) = if (static) ":" else "."
        val imp = when (fi) {
            is ForeignImport.Type -> "type " + fi.type + alias(fi.alias)
            is ForeignImport.Method -> fi.type + sep(fi.static) + fi.name + fi.pars.joinToString(
                prefix = "(",
                postfix = ")"
            ) + alias(fi.alias)
            is ForeignImport.Ctor -> "new " + fi.type + fi.pars.joinToString(
                prefix = "(",
                postfix = ")"
            ) + alias(fi.alias)
            is ForeignImport.Getter -> "get " + fi.type + sep(fi.static) + fi.name + alias(fi.alias)
            is ForeignImport.Setter -> "set " + fi.type + sep(fi.static) + fi.name + alias(fi.alias)
        }
        return "foreign import $imp"
    }

    fun show(d: Decl): String {
        val cmt = if (d.comment != null) show(d.comment!!, true) else ""
        val vis = if (d.visibility == Visibility.PUBLIC) "pub\n" else ""
        return cmt + when (d) {
            is Decl.TypeDecl -> {
                val visi = if (d.dataCtors[0].visibility == Visibility.PUBLIC) "pub+\n" else vis
                visi + show(d)
            }
            is Decl.ValDecl -> vis + show(d)
            is Decl.TypealiasDecl -> vis + show(d)
        }
    }

    fun show(ta: Decl.TypealiasDecl): String {
        return "typealias ${ta.name} " + ta.tyVars.joinToStr(" ", postfix = " ") + "= ${show(ta.type)}"
    }

    fun show(d: Decl.TypeDecl): String {
        val dd = "type ${d.name}" + d.tyVars.joinToStr(" ", prefix = " ")
        return if (d.dataCtors.size == 1) "$dd = ${show(d.dataCtors[0])}"
        else {
            dd + withIndent { d.dataCtors.joinToString("\n$tab| ", prefix = "$tab= ") { show(it) } }
        }
    }

    fun show(name: String, type: Type): String {
        val td = "$name : " + show(type)
        return if (td.length > maxColumns) {
            name + withIndent { showIndented(type) }
        } else td
    }

    fun show(d: Decl.ValDecl): String {
        var prefix = ""
        if (d.type != null) prefix = show(d.name, d.type) + "\n" + prefix
        prefix += d.name + d.patterns.joinToStr(" ", prefix = " ") { show(it) } + " ="

        return if (shouldNewline(d.exp)) {
            prefix + withIndent { tab + show(d.exp) }
        } else "$prefix " + show(d.exp)
    }

    fun show(d: DataConstructor): String {
        return d.name + d.args.joinToStr(" ", prefix = " ") { show(it) }
    }

    fun show(e: Expr): String {
        val cmt = if (e.comment != null) show(e.comment!!) + tab else ""
        return cmt + when (e) {
            is Expr.Do -> {
                "do" + withIndent { e.exps.joinToString("\n$tab", prefix = tab) { show(it) } }
            }
            is Expr.Match -> {
                val expsStr = e.exps.joinToString { show(it) }
                "case $expsStr of" + withIndent { e.cases.joinToString("\n$tab", prefix = tab) { show(it) } }
            }
            is Expr.Let -> {
                val str = if (shouldNewline(e.body)) withIndent { "$tab${show(e.body)}" } else show(e.body)
                "let " + withIndent(false) {
                    withIndent(false) {
                        e.letDefs.joinToStr("\n$tab") { show(it) }
                    }
                } + "\n${tab}in $str"
            }
            is Expr.DoLet -> {
                "let " + withIndent(false) {
                    withIndent(false) {
                        e.letDefs.joinToStr("\n$tab") { show(it) }
                    }
                }
            }
            is Expr.If -> {
                val simple = e.thenCase.isSimple() && e.elseCase.isSimple()
                if (simple) "if ${show(e.cond)} then ${show(e.thenCase)} else ${show(e.elseCase)}"
                else "if ${show(e.cond)}\n${tab}then ${show(e.thenCase)}\n${tab}else ${show(e.elseCase)}"
            }
            is Expr.App -> {
                "${show(e.fn)} ${show(e.arg)}"
            }
            is Expr.Ann -> {
                "${show(e.exp)} : ${show(e.type)}"
            }
            is Expr.Lambda -> {
                val shown = if (shouldNewline(e.body)) withIndent { tab + show(e.body) } else show(e.body)
                "\\" + e.patterns.joinToString(" ") { show(it) } + " -> $shown"
            }
            is Expr.Var -> e.toString()
            is Expr.Operator -> e.toString()
            is Expr.ImplicitVar -> "{{${e.name}}}"
            is Expr.Constructor -> e.toString()
            is Expr.IntE -> e.text
            is Expr.LongE -> e.text
            is Expr.FloatE -> e.text
            is Expr.DoubleE -> e.text
            is Expr.StringE -> "\"${e.v}\""
            is Expr.CharE -> "'${e.v}'"
            is Expr.Bool -> "${e.v}"
            is Expr.Parens -> "(${show(e.exp)})"
            is Expr.Unit -> "()"
            is Expr.RecordEmpty -> "{}"
            is Expr.RecordSelect -> "${show(e.exp)}.${e.labels.joinToStr(".")}"
            is Expr.RecordRestrict -> "{ - ${show(e.exp)} | ${e.label} }"
            is Expr.RecordExtend -> "{ ${e.labels.show(::showLabel)} | ${show(e.exp)} }"
            is Expr.VectorLiteral -> e.exps.joinToString(prefix = "[", postfix = "]")
            is Expr.SetLiteral -> e.exps.joinToString(prefix = "#{", postfix = "}")
            is Expr.Underscore -> "_"
            is Expr.BinApp -> "${show(e.left)} ${show(e.op)} ${show(e.right)}"
        }
    }

    private fun showLabel(l: String, e: Expr): String = "$l: ${show(e)}"

    private fun show(c: Case): String {
        val guard = if (c.guard != null) " if ${show(c.guard)}" else ""
        return c.patterns.joinToString { show(it) } + "$guard -> " + show(c.exp)
    }

    private fun show(p: Pattern): String = when (p) {
        is Pattern.Wildcard -> "_"
        is Pattern.Var -> p.name
        is Pattern.Ctor -> show(p.ctor) + p.fields.joinToStr(" ", prefix = " ") { show(it) }
        is Pattern.LiteralP -> show(p.lit)
        is Pattern.Parens -> "(${show(p.pattern)})"
        is Pattern.Record -> "{ ${p.labels.show { l, pt -> "$l: ${show(pt)}" }}"
        is Pattern.Vector -> "[${p.elems.joinToString { show(it) }}]"
        is Pattern.VectorHT -> "[${show(p.head)} :: ${show(p.tail)}]"
        is Pattern.Named -> "${show(p.pat)} as ${p.name}"
        is Pattern.Unit -> "()"
        is Pattern.TypeTest -> ":? ${show(p.type)}" + if (p.alias != null) " as ${p.alias}" else ""
        is Pattern.ImplicitVar -> "{{${p.name}}}"
    }

    private fun show(p: LiteralPattern): String = when (p) {
        is LiteralPattern.BoolLiteral -> show(p.e)
        is LiteralPattern.CharLiteral -> show(p.e)
        is LiteralPattern.StringLiteral -> show(p.e)
        is LiteralPattern.IntLiteral -> show(p.e)
        is LiteralPattern.LongLiteral -> show(p.e)
        is LiteralPattern.FloatLiteral -> show(p.e)
        is LiteralPattern.DoubleLiteral -> show(p.e)
    }

    private fun show(l: LetDef): String = when (l) {
        is LetDef.DefBind -> {
            val typ = if (l.type != null) "${l.name} : ${show(l.type)}\n$tab" else ""
            "${typ}${l.name}" + l.patterns.joinToStr(" ", prefix = " ") { show(it) } + " = ${show(l.expr)}"
        }
        is LetDef.DefPattern -> {
            "${show(l.pat)} = ${show(l.expr)}"
        }
    }

    fun show(t: Type): String = when (t) {
        is Type.TFun -> {
            val tlist = mutableListOf<String>()
            var fn = t
            while (fn is Type.TFun) {
                tlist += show(fn.arg)
                fn = fn.ret
            }
            tlist += show(fn)
            tlist.joinToString(" -> ")
        }
        is Type.TForall -> "forall " + t.names.joinToString(" ") + ". ${show(t.type)}"
        is Type.TApp -> show(t.type) + t.types.joinToStr(" ", prefix = " ") { show(it) }
        is Type.TParens -> "(${show(t.type)})"
        is Type.TConst -> t.fullname()
        is Type.TRecord -> "{ ${show(t.row)} }"
        is Type.TRowEmpty -> "{}"
        is Type.TRowExtend -> "{ ${t.labels.show(::showLabelType)} | ${show(t.row)} }"
        is Type.TImplicit -> "{{ ${show(t.type)} }}"
    }

    private fun showLabelType(l: String, ty: Type): String = "$l : ${show(ty)}"

    private fun showIndented(t: Type, prefix: String = ":"): String = when (t) {
        is Type.TFun -> {
            val tlist = mutableListOf<String>()
            var fn = t
            while (fn is Type.TFun) {
                tlist += show(fn.arg)
                fn = fn.ret
            }
            tlist += show(fn)
            tlist.joinToString("\n$tab-> ", prefix = "$tab$prefix ")
        }
        is Type.TForall -> {
            val str = if (t.type is Type.TFun) showIndented(t.type, ".") else show(t.type)
            "$tab$prefix forall " + t.names.joinToString(" ", postfix = "\n") + str
        }
        else -> show(t)
    }

    fun show(c: Comment, newline: Boolean = false): String = if (c.isMulti) {
        val end = if (newline) "\n" else ""
        "$tab/*\n$tab * " + c.comment.split("\n").joinToString("\n$tab *") + "\n$tab */$end"
    } else "// ${c.comment}\n"

    private fun Expr.isSimple(): Boolean = when (this) {
        is Expr.IntE, is Expr.FloatE, is Expr.StringE, is Expr.CharE, is Expr.Bool -> true
        is Expr.Var, is Expr.Operator -> true
        else -> false
    }

    private fun showList(list: List<String>, start: String = "(", end: String = ")"): String = withIndent {
        when {
            list.isEmpty() -> "$tab$start$end"
            list.size == 1 -> "$tab$start ${list[0]} $end"
            else -> "$tab$start " + list.joinToString("\n$tab, ") + "\n$tab$end"
        }
    }

    private fun shouldNewline(e: Expr) = e is Expr.Let || e is Expr.If || e is Expr.Match

    private inline fun withIndent(shouldBreak: Boolean = true, f: () -> String): String {
        val oldIndent = tab
        tab = "$tab${tabSize}"
        val res = f()
        tab = oldIndent
        return if (shouldBreak) "\n$res" else res
    }

    companion object {
        const val maxColumns = 120
        const val tabSize = "  " // 2 spaces
    }
}
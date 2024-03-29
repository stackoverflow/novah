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
import novah.data.Labels
import novah.data.show
import novah.data.showLabel
import novah.frontend.Comment
import novah.frontend.Span
import java.util.LinkedList

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

        if (m.metadata != null) builder.append(show(m.metadata) + "\n")
        builder.append("module ${m.name.value}")

        val imps = m.imports.filter { !it.isAuto() }
        if (imps.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(imps.sortedBy { it.module.value }.joinToString("\n") { show(it) })
        }
        if (m.foreigns.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(m.foreigns.sortedBy { it.type }.joinToString("\n") { show(it) })
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
        val alias = if (i.alias() != null) " as ${i.alias()}" else ""
        if (i is Import.Exposing) {
            exposes = i.defs.joinToString(", ", prefix = " (", postfix = ")")
            val str = "import ${i.module.value}$exposes$alias"
            if (str.length > totalColumns)
                exposes = showList(i.defs.map { it.toString() })
        }
        return "${cmt}import ${i.module.value}$exposes$alias"
    }

    fun show(fi: ForeignImport): String {
        // TODO: show comments
        fun alias(al: String?) = if (al != null) " as $al" else ""

        val imp = fi.type + alias(fi.alias)
        return "foreign import $imp"
    }

    fun show(d: Decl): String {
        val cmt = if (d.comment != null) show(d.comment!!, true) else ""
        val attrs = if (d.metadata != null) show(d.metadata!!) + "\n" else ""
        val vis = if (d.visibility == Visibility.PUBLIC) "pub\n" else ""
        return cmt + attrs + when (d) {
            is Decl.TypeDecl -> {
                val visi = if (d.dataCtors[0].visibility == Visibility.PUBLIC) "pub+\n" else vis
                visi + show(d)
            }
            is Decl.ValDecl -> {
                when {
                    d.visibility == Visibility.PUBLIC && d.isInstance -> "pub instance\n"
                    d.visibility == Visibility.PUBLIC -> "pub\n"
                    d.isInstance -> "instance\n"
                    else -> ""
                } + show(d)
            }
            is Decl.TypealiasDecl -> vis + show(d)
        }
    }

    fun show(ta: Decl.TypealiasDecl): String {
        val ty = withIndentKeep { show(ta.type) }
        return "typealias ${ta.name} " + ta.tyVars.joinToStr(" ", postfix = " ") + "= $ty"
    }

    fun show(d: Decl.TypeDecl): String {
        val decl = "type ${d.name}" + d.tyVars.joinToStr(" ", prefix = " ")
        return if (d.dataCtors.size == 1) {
            val ctor = show(d.dataCtors[0])
            if (ctor.contains('\n')) "$decl =" + withIndent { tab + show(d.dataCtors[0]) }
            else "$decl = $ctor"
        } else {
            decl + withIndent { d.dataCtors.joinToString("\n$tab| ", prefix = "$tab= ") { show(it) } }
        }
    }

    fun show(name: String, type: Type, isOperator: Boolean): String {
        val valName = if (isOperator) "($name)" else name
        val td = "$valName : " + show(type)
        return if (td.contains('\n')) {
            valName + withIndent { "$tab: " + show(type) }
        } else td
    }

    fun show(d: Decl.ValDecl): String {
        var prefix = ""
        if (d.signature?.type != null) prefix = show(d.name, d.signature.type, d.isOperator) + "\n"
        val name = if (d.isOperator) "(${d.name})" else d.name
        prefix += name + d.patterns.joinToStr(" ", prefix = " ") { show(it) } + " ="

        return if (d.exp.isSimpleExpr() || d.exp is Expr.Match || d.exp is Expr.Computation) {
            "$prefix ${show(d.exp)}"
        } else prefix + withIndent { tab + show(d.exp) }
    }

    fun show(d: DataConstructor): String {
        return d.name.value + d.args.joinToStr(" ", prefix = " ") {
            if (it is Type.TRecord) show(it) else withIndentKeep { show(it) }
        }
    }

    fun show(e: Expr, op: OpExpr? = null): String {
        val cmt = if (e.comment != null) show(e.comment!!) + tab else ""
        return cmt + when (e) {
            is Expr.Do -> showExprList(e.exps)
            is Expr.Match -> {
                val expsStr = e.exps.joinToString { show(it) }
                "case $expsStr of" + withIndent { e.cases.joinToString("\n$tab", prefix = tab) { show(it) } }
            }
            is Expr.Let -> {
                val str = if (e.body.isSimpleExpr()) "in " + show(e.body)
                else "in" + withIndent { tab + show(e.body) }
                "let " + show(e.letDef, e.span) + "\n${tab}$str"
            }
            is Expr.DoLet -> "let ${show(e.letDef, e.span)}"
            is Expr.LetBang -> {
                if (e.body == null)
                    "let! ${show(e.letDef, e.span)}"
                else
                    "let! ${show(e.letDef, e.span)} in ${show(e.body)}"
            }
            is Expr.For -> {
                val str = "for ${show(e.letDef, e.span, isFor = true)} do ${show(e.body)}"
                // respect new lines
                if (str.length > maxColumns || e.span.startLine < e.body.span.startLine) {
                    "for ${show(e.letDef, e.span, isFor = true)} do" + withIndent { tab + show(e.body) }
                } else str
            }
            is Expr.DoBang -> "do! ${show(e.exp)}"
            is Expr.If -> {
                if (e.elseCase != null) {
                    val simple = "if ${show(e.cond)} then ${show(e.thenCase)} else ${show(e.elseCase)}"
                    if (!e.isMultiLine() && simple.length < maxColumns && !simple.contains('\n')) simple
                    else {
                        val prefix = withIndent(false) { "if ${show(e.cond)} then\n${tab}${show(e.thenCase)}\n" }
                        val els = "${tab}else\n"
                        withIndent(false) { "$prefix$els$tab${show(e.elseCase)}" }
                    }
                } else {
                    val simple = "if ${show(e.cond)} then ${show(e.thenCase)}"
                    if (simple.length < maxColumns && !simple.contains('\n')) simple
                    else withIndent(false) { "if ${show(e.cond)} then\n${tab}${show(e.thenCase)}" }
                }
            }
            is Expr.Return -> "return ${show(e.exp)}"
            is Expr.Yield -> "yield ${show(e.exp)}"
            is Expr.App -> {
                val shown = "${show(e.fn)} ${show(e.arg)}"
                if (shown.endLineLength() > maxColumns) {
                    val pars = LinkedList<Expr>()
                    pars.addLast(e.arg)
                    var app = e.fn
                    while (app is Expr.App) {
                        pars.addFirst(app.arg)
                        app = app.fn
                    }
                    pars.addFirst(app)
                    show(pars.removeFirst()) + withIndent {
                        pars.joinToString("\n$tab", prefix = tab) { show(it) }
                    }
                } else shown
            }
            is Expr.Ann -> {
                val ty = withIndentKeep { show(e.type) }
                "${show(e.exp)} : $ty"
            }
            is Expr.Lambda -> {
                // respect new lines
                val shown = if (e.span.startLine < e.body.span.startLine) {
                    " ->" + withIndent { tab + show(e.body) }
                } else {
                    val simple = " -> ${show(e.body)}"
                    if (!simple.contains('\n') && simple.length + tab.length < maxColumns) simple
                    else " ->" + withIndent { tab + show(e.body) }
                }

                "\\" + e.patterns.joinToString(" ") { show(it) } + shown
            }
            is Expr.Var -> if (e.alias != null) "${e.alias}.${e.name}" else e.name
            is Expr.Operator -> {
                val str = if (e.alias != null) "${e.alias}.${e.name}" else e.name
                if (e.isPrefix) "`$str`" else str
            }
            is Expr.ImplicitVar -> if (e.alias != null) "{{${e.alias}.${e.name}}" else "{{${e.name}}"
            is Expr.Constructor -> if (e.alias != null) "${e.alias}.${e.name}" else e.name
            is Expr.Int32 -> e.text
            is Expr.Int64 -> e.text
            is Expr.Float32 -> e.text
            is Expr.Float64 -> e.text
            is Expr.Bigint -> e.text
            is Expr.Bigdec -> e.text
            is Expr.StringE -> if (e.multi) "\"\"\"${e.v}\"\"\"" else "\"${e.raw}\""
            is Expr.PatternLiteral -> "#\"${e.raw}\""
            is Expr.CharE -> "'${e.raw}'"
            is Expr.Bool -> "${e.v}"
            is Expr.Parens -> {
                if (e.exp is Expr.BinApp)
                    withIndent(false) { "(${show(e.exp)})" }
                else
                    "(${show(e.exp)})"
            }
            is Expr.Unit -> "()"
            is Expr.Deref -> "@" + show(e.exp)
            is Expr.RecordEmpty -> "{}"
            is Expr.RecordSelect -> "${show(e.exp)}.${e.labels.joinToStr(".") { it.value }}"
            is Expr.RecordRestrict -> {
                val show = "{ - ${e.labels.joinToString { showLabel(it) }} | ${show(e.exp)} }"
                if (show.length > maxColumns || e.isMultiLine()) {
                    val labels = withIndentKeep {
                        e.labels.joinToString("\n$tab, ", postfix = "\n$tab| ${show(e.exp)}") { showLabel(it) }
                    }
                    "{ - $labels\n$tab}"
                } else show
            }
            is Expr.RecordUpdate -> {
                fun showRecUp(up: RecUpdate): String {
                    val sep = if (up.isSet) "=" else "->"
                    val labels = up.labels.joinToString(".") { showLabel(it.value) }
                    return ".$labels $sep ${show(up.value)}"
                }
                val shown = "{ ${e.updates.joinToString { showRecUp(it) }} | ${show(e.exp)} }"
                if (shown.length > maxColumns || e.isMultiLine()) {
                    val str = e.updates.joinToString("\n$tab, ") { withIndentKeep { showRecUp(it) } }
                    "{ $str\n$tab}"
                } else shown
            }
            is Expr.RecordExtend -> {
                val labels = e.labels.show(", ", ::showLabelExpr)
                val shown = if (e.exp is Expr.RecordEmpty) "{ $labels }" else "{ $labels | ${show(e.exp)} }"
                if (shown.length > maxColumns || e.isMultiLine()) {
                    val str = e.labels.showMulti { l, exp -> withIndentKeep { showLabelExpr(l, exp) } }
                    if (e.exp is Expr.RecordEmpty) "{ $str\n$tab}" else "{ $str\n$tab| ${show(e.exp)}\n$tab}"
                } else shown
            }
            is Expr.RecordMerge -> {
                var show1 = show(e.exp1)
                var show2 = show(e.exp2)
                val len = show1.length + show2.length + 4
                if (show1.contains('\n') || show2.contains('\n') || len > maxColumns || e.isMultiLine()) {
                    show1 = withIndentKeep { tab + show(e.exp1) }
                    show2 = withIndentKeep { tab + show(e.exp2) }
                    "{ +\n$show1\n$tab  ,\n$show2\n$tab}"
                } else "{ + $show1, $show2 }"
            }
            is Expr.ListLiteral -> {
                val simple = e.exps.joinToString(prefix = "[", postfix = "]") { show(it) }
                if (simple.length > maxColumns || e.isMultiLine()) {
                    e.exps.joinToString("\n$tab, ", prefix = "[ ", postfix = "\n$tab]") { show(it) }
                } else simple
            }
            is Expr.SetLiteral -> {
                val simple = e.exps.joinToString(prefix = "#{", postfix = "}") { show(it) }
                if (simple.length > maxColumns || e.isMultiLine()) {
                    e.exps.joinToString("\n$tab , ", prefix = "#{ ", postfix = "\n$tab }") { show(it) }
                } else simple
            }
            is Expr.Index -> "${show(e.exp)}.[${show(e.index)}]"
            is Expr.Underscore -> "_"
            is Expr.BinApp -> {
                val opstr = show(e.op)
                if (op == null || op.op != opstr) {
                    val simple = "${show(e.left)} $opstr ${show(e.right)}"
                    if (simple.length > maxColumns) {
                        show(e.left, op = Left(opstr)) + "\n$tab$opstr ${show(e.right, op = Right(opstr))}"
                    } else simple
                } else if (op is Left) {
                    show(e.left, op) + "\n$tab${show(e.op)} ${show(e.right, op = Right(op.op))}"
                } else "${show(e.left, op)}\n$tab${show(e.op)} ${show(e.right, op)}"
            }
            is Expr.Throw -> "throw ${show(e.exp)}"
            is Expr.TryCatch -> {
                val tr = "try ${show(e.tryExpr)}\n${tab}catch" + withIndent {
                    e.cases.joinToString("\n$tab", prefix = tab) { show(it) }
                }
                if (e.finallyExp != null) {
                    tr + "\n${tab}finally ${show(e.finallyExp)}"
                } else tr
            }
            is Expr.While -> {
                val cond = show(e.cond)
                "while $cond do" + withIndent { tab + showExprList(e.exps) }
            }
            is Expr.Computation -> {
                "do." + e.builder.name + withIndent { e.exps.joinToString("\n$tab", prefix = tab) { show(it) } }
            }
            is Expr.TypeCast -> show(e.exp) + " as " + withIndentKeep { show(e.cast) }
            is Expr.BangBang -> "${show(e.exp)}!!"
            is Expr.UnderscoreBangBang -> "_!!"
            is Expr.ForeignStaticField -> "${e.clazz.value}#-${e.field.value}"
            is Expr.ForeignField -> "${show(e.exp)}#-${e.field.value}"
            is Expr.ForeignStaticMethod -> {
                val prefix = "${e.clazz.value}#${e.method.value}"
                prefix + e.args.joinToString(", ", prefix = "(", postfix = ")") { show(it) }
            }
            is Expr.ForeignMethod -> {
                val prefix = "${show(e.exp)}#${e.method.value}"
                prefix + e.args.joinToString(", ", prefix = "(", postfix = ")") { show(it) }
            }
        }
    }

    private fun showExprList(exps: List<Expr>): String {
        val str = StringBuilder()
        var last: Expr? = null
        for (exp in exps) {
            if (last != null) {
                // respect empty new lines between statements
                val adjacent = exp.span.startLine - 1 == last.span.endLine
                if (adjacent) str.append("\n$tab")
                else str.append("\n\n$tab")
            }
            str.append(show(exp))
            last = exp
        }
        return str.toString()
    }

    private fun showLabelExpr(l: String, e: Expr): String =
        if (e is Expr.Var && e.name == l && e.alias == null) l else "$l: ${show(e)}"

    private fun showLabelExprMeta(l: String, e: Expr): String =
        if (e is Expr.Bool && e.v) l else "$l: ${show(e)}"

    private fun show(c: Case): String {
        val guard = if (c.guard != null) " if ${show(c.guard)}" else ""
        val exp = show(c.exp)
        return if (exp.contains('\n') || exp.length > maxColumns) {
            withIndent(false) {
                c.patterns.joinToString { show(it) } + "$guard ->\n$tab" + show(c.exp)
            }
        } else c.patterns.joinToString { show(it) } + "$guard -> $exp"
    }

    private fun show(p: Pattern): String = when (p) {
        is Pattern.Wildcard -> "_"
        is Pattern.Var -> show(p.v)
        is Pattern.Ctor -> show(p.ctor) + p.fields.joinToStr(" ", prefix = " ") { show(it) }
        is Pattern.LiteralP -> show(p.lit)
        is Pattern.Parens -> "(${show(p.pattern)})"
        is Pattern.Record -> "{${p.labels.show { l, pt ->
            if (pt is Pattern.Var && pt.v.name == l && pt.v.alias == null) l else "$l: ${show(pt)}"
        }}}"
        is Pattern.ListP -> {
            val tail = if (p.tail != null) " :: ${show(p.tail)}" else ""
            "[${p.elems.joinToString { show(it) }}$tail]"
        }
        is Pattern.Named -> "${show(p.pat)} as ${p.name.value}"
        is Pattern.Unit -> "()"
        is Pattern.TypeTest -> {
            val ty = withIndentKeep { show(p.type) }
            ":? $ty" + if (p.alias != null) " as ${p.alias}" else ""
        }
        is Pattern.ImplicitPattern -> "{{${show(p.pat)}}}"
        is Pattern.TypeAnnotation -> "${show(p.pat)} : ${show(p.type)}"
        is Pattern.TuplePattern -> "${show(p.p1)} ; ${show(p.p2)}"
        is Pattern.RegexPattern -> "#\"${show(p.regex)}\""
    }

    private fun show(p: LiteralPattern): String = when (p) {
        is LiteralPattern.BoolLiteral -> show(p.e)
        is LiteralPattern.CharLiteral -> show(p.e)
        is LiteralPattern.StringLiteral -> show(p.e)
        is LiteralPattern.Int32Literal -> show(p.e)
        is LiteralPattern.Int64Literal -> show(p.e)
        is LiteralPattern.Float32Literal -> show(p.e)
        is LiteralPattern.Float64Literal -> show(p.e)
        is LiteralPattern.BigintLiteral -> show(p.e)
        is LiteralPattern.BigdecPattern -> show(p.e)
    }

    private fun show(l: LetDef, span: Span, isFor: Boolean = false): String {
        val sep = if (isFor) "in" else "="
        val prefix = when (l) {
            is LetDef.DefBind -> {
                val typ = if (l.type != null) {
                    withIndentKeep { "${l.name} : ${show(l.type)}" } + "\n$tab"
                } else ""
                "${typ}${l.name}" + l.patterns.joinToStr(" ", prefix = " ") { show(it) } + " $sep"
            }
            is LetDef.DefPattern -> "${show(l.pat)} $sep"
        }
        val simple = "$prefix ${show(l.expr)}"
        val hasLineBreak = span.startLine < l.expr.span.startLine
        val isMatchComp = (l.expr is Expr.Match || l.expr is Expr.Computation)
                && simple.endLineLength() + tab.length + 4 < maxColumns
        val isSimple = !simple.contains('\n') && simple.length + tab.length + 4 < maxColumns
        return if (!hasLineBreak && (isMatchComp || isSimple)) simple
        else prefix + withIndent { tab + show(l.expr) }
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
            val str = tlist.joinToString(" -> ")
            if (str.length > maxColumns) tlist.joinToString("\n$tab-> ")
            else str
        }
        is Type.TApp -> show(t.type) + t.types.joinToStr(" ", prefix = " ") { show(it) }
        is Type.TParens -> "(${show(t.type)})"
        is Type.TConst -> t.fullname()
        is Type.TRecord -> show(t.row)
        is Type.TRowEmpty -> "{}"
        is Type.TRowExtend -> {
            var labels = t.labels.show(", ", ::showLabelType)
            val str = if (t.row is Type.TRowEmpty) "{ $labels }" else "{ $labels | ${show(t.row)} }"
            if (str.length > maxColumns) {
                withIndentKeep {
                    labels = t.labels.show("\n$tab, ") { lab, ty ->
                        withIndentKeep { showLabelType(lab, ty) }
                    }
                    if (t.row is Type.TRowEmpty) "{ $labels\n$tab}" else "{ $labels\n$tab| ${show(t.row)}\n$tab}"
                }
            } else str
        }
        is Type.TImplicit -> "{{ ${show(t.type)} }}"
    }

    fun show(attrs: Metadata): String {
        val e = attrs.data
        val labels = e.labels.show(", ", ::showLabelExprMeta)
        return "#[$labels]"
    }

    private fun showLabelType(l: String, ty: Type): String = "$l : ${show(ty)}"

    fun show(c: Comment, newline: Boolean = false): String = if (c.isMulti) {
        val end = if (newline) "\n" else ""
        "$tab/*\n$tab * " + c.comment.split("\n").joinToString("\n$tab *") + "\n$tab */$end"
    } else {
        val comment = c.comment.replace("\n", "\n//").replace("\n//\n", "\n\n")
        "//$comment\n"
    }

    private fun Expr.isSimpleExpr(): Boolean = when (this) {
        is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64,
        is Expr.StringE, is Expr.CharE, is Expr.Bool,
        is Expr.Var, is Expr.Operator -> true
        is Expr.Parens -> exp.isSimpleExpr()
        else -> false
    }

    private fun Expr.isMultiLine(): Boolean = span.startLine < span.endLine

    private fun showList(list: List<String>, start: String = "(", end: String = ")"): String = withIndent {
        when {
            list.isEmpty() -> "$tab$start$end"
            list.size == 1 -> "$tab$start ${list[0]} $end"
            else -> "$tab$start " + list.joinToString("\n$tab, ") + "\n$tab$end"
        }
    }

    private fun <V> Labels<V>.showMulti(fn: (String, V) -> String): String = this.show("\n$tab, ", fn)

    private fun String.endLineLength(): Int {
        val l = this.indexOf('\n')
        return if (l == -1) this.length else l
    }

    private inline fun withIndent(shouldBreak: Boolean = true, f: () -> String): String {
        val oldIndent = tab
        tab = "$tab${tabSize}"
        val res = f()
        tab = oldIndent
        return if (shouldBreak) "\n$res" else res
    }

    private inline fun withIndentKeep(f: () -> String): String = withIndent(shouldBreak = false, f)

    sealed class OpExpr(val op: String)
    class Left(op: String) : OpExpr(op)
    class Right(op: String) : OpExpr(op)

    companion object {
        const val maxColumns = 80
        const val totalColumns = 120
        const val tabSize = "  " // 2 spaces
    }
}
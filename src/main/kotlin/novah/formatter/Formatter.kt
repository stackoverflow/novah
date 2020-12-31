package novah.formatter

import novah.ast.source.*
import novah.frontend.*

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

        builder.append("module ${m.fullName()}${show(m.exports)}")

        if (m.imports.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(m.imports.sortedBy { it.fullName() }.joinToString("\n") { show(it) })
        }
        if (m.decls.isNotEmpty()) {
            var last = m.decls[0]
            builder.append("\n\n")
            for (d in m.decls) {
                if (d != m.decls[0]) {
                    if (last is Decl.TypeDecl && d is Decl.ValDecl && last.name == d.name) builder.append("\n")
                    else builder.append("\n\n")
                }
                builder.append(show(d))
                last = d
            }
        }
        return builder.toString()
    }

    fun show(me: ModuleExports): String = when (me) {
        is ModuleExports.Hiding -> " hiding" + showList(me.hides.map { it.toString() })
        is ModuleExports.Exposing -> " exposing" + showList(me.exports.map { it.toString() })
        is ModuleExports.ExportAll -> ""
    }

    fun show(i: Import): String {
        val cmt = if (i.comment != null) show(i.comment!!, true) else ""
        var exposes = ""
        if (i is Import.Exposing) {
            exposes = i.defs.joinToString(", ", prefix = " (", postfix = ")")
            if (exposes.length > maxColumns) exposes = showList(i.defs.map { it.toString() })
        }
        val alias = if (i.alias() != null) " as ${i.alias()}" else ""
        return "${cmt}import ${i.fullName()}$exposes$alias"
    }

    fun show(d: Decl): String {
        val cmt = if (d.comment != null) show(d.comment!!, true) else ""
        return cmt + when (d) {
            is Decl.DataDecl -> show(d)
            is Decl.TypeDecl -> show(d)
            is Decl.ValDecl -> show(d)
        }
    }

    fun show(d: Decl.DataDecl): String {
        val dd = "type ${d.name}" + d.tyVars.joinToStr(" ", prefix = " ")
        return if (d.dataCtors.size == 1) "$dd = ${show(d.dataCtors[0])}"
        else {
            dd + withIndent { d.dataCtors.joinToString("\n$tab| ", prefix = "$tab= ") { show(it) } }
        }
    }

    fun show(d: Decl.TypeDecl): String {
        val td = "${d.name} :: " + show(d.type)
        return if (td.length > maxColumns) {
            d.name + withIndent { showIndented(d.type) }
        } else td
    }

    fun show(d: Decl.ValDecl): String {
        val prefix = d.name + d.binders.joinToStr(" ", prefix = " ") + " ="

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
                "case ${show(e.exp)} of" + withIndent { e.cases.joinToString("\n$tab", prefix = tab) { show(it) } }
            }
            is Expr.Let -> {
                val str = if (shouldNewline(e.body)) withIndent { "$tab${show(e.body)}" } else show(e.body)
                "let " + withIndent(false) {
                    withIndent(false) {
                        e.letDefs.joinToStr("\n$tab") { show(it) }
                    }
                } + "\n${tab}in $str"
            }
            is Expr.If -> {
                val simple = e.thenCase.isSimple() && e.elseCase.isSimple()
                if (simple) "if ${show(e.cond)} then ${show(e.thenCase)} else ${show(e.elseCase)}"
                else "if ${show(e.cond)}\n${tab}then ${show(e.thenCase)}\n${tab}else ${show(e.elseCase)}"
            }
            is Expr.App -> {
                val exps = Application.unparseApplication(e)
                exps.joinToString(" ") { show(it) }
            }
            is Expr.Ann -> {
                "${show(e.exp)} :: ${show(e.type)}"
            }
            is Expr.Lambda -> {
                val shown = if (shouldNewline(e.body)) withIndent { tab + show(e.body) } else show(e.body)
                "\\" + e.binders.joinToString(" ") + " -> $shown"
            }
            is Expr.Var -> e.toString()
            is Expr.Operator -> e.toString()
            is Expr.Constructor -> e.toString()
            is Expr.IntE -> e.text
            is Expr.LongE -> e.text
            is Expr.FloatE -> e.text
            is Expr.DoubleE -> e.text
            is Expr.StringE -> "\"${e.v}\""
            is Expr.CharE -> "'${e.v}'"
            is Expr.Bool -> "${e.v}"
            is Expr.Parens -> "(${show(e.exp)})"
        }
    }

    private fun show(c: Case): String {
        return show(c.pattern) + " -> " + show(c.exp)
    }

    private fun show(p: Pattern): String = when (p) {
        is Pattern.Wildcard -> "_"
        is Pattern.Var -> p.name
        is Pattern.Ctor -> show(p.ctor) + p.fields.joinToStr(" ", prefix = " ") { show(it) }
        is Pattern.LiteralP -> show(p.lit)
        is Pattern.Parens -> "(${show(p.pattern)})"
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

    private fun show(l: LetDef): String {
        val typ = if (l.type != null) "${l.name} :: ${show(l.type)}\n$tab" else ""
        return "${typ}${l.name}" + l.binders.joinToStr(" ", prefix = " ") + " = ${show(l.expr)}"
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
        is Type.TConstructor -> t.name + t.types.joinToStr(" ", prefix = " ") { show(it) }
        is Type.TParens -> "(${show(t.type)})"
        is Type.TVar -> t.name
    }

    private fun showIndented(t: Type, prefix: String = "::"): String = when (t) {
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

        /**
         * Like [joinToString] but don't append the prefix/suffix if
         * the list is empty.
         */
        private fun <T> List<T>.joinToStr(
            separator: CharSequence = ", ",
            prefix: CharSequence = "",
            postfix: CharSequence = "",
            limit: Int = -1,
            truncated: CharSequence = "...",
            transform: ((T) -> CharSequence)? = null
        ): String {
            val pre = if (isEmpty()) "" else prefix
            val pos = if (isEmpty()) "" else postfix
            return joinTo(StringBuilder(), separator, pre, pos, limit, truncated, transform).toString()
        }
    }
}
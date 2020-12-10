package novah.formatter

import novah.ast.source.*
import novah.frontend.*
import novah.frontend.typechecker.Type

/**
 * The canonical formatter for the language. Like gofmt
 */
class Formatter(private val ast: Module) {

    private val builder = StringBuilder()
    private var tab = ""

    fun format(): String = ast.show()

    private fun Module.show(): String {
        val cmt = if (comment != null) {
            comment!!.show() + "\n"
        } else ""
        builder.append(cmt)

        builder.append("module ${fullName()}${exports.show()}")

        if (imports.isNotEmpty()) {
            builder.append("\n\n")
            builder.append(imports.sortedBy { it.fullName() }.joinToString("\n") { it.show() })
        }
        if (decls.isNotEmpty()) {
            var last = decls[0]
            builder.append("\n\n")
            for (d in decls) {
                if (d != decls[0]) {
                    if (last is Decl.TypeDecl && d is Decl.ValDecl && last.name == d.name) builder.append("\n")
                    else builder.append("\n\n")
                }
                builder.append(d.show())
                last = d
            }
        }
        return builder.toString()
    }

    private fun ModuleExports.show(): String = when (this) {
        is ModuleExports.Hiding -> " hiding" + showList(hides)
        is ModuleExports.Exposing -> " exposing" + showList(exports)
        is ModuleExports.ExportAll -> ""
    }

    private fun Import.show(): String {
        val cmt = if (comment != null) comment!!.show() else ""
        var exposes = ""
        if (this is Import.Exposing) {
            exposes = defs.joinToString(", ", prefix = " (", postfix = ")")
            if (exposes.length > maxColumns) exposes = showList(defs)
        }
        val alias = if (alias() != null) " as ${alias()}" else ""
        return "${cmt}import ${fullName()}$exposes$alias"
    }

    private fun Decl.show(): String = when (this) {
        is Decl.DataDecl -> this.show()
        is Decl.TypeDecl -> this.show()
        is Decl.ValDecl -> this.show()
    }

    private fun Decl.DataDecl.show(): String {
        val dd = "type $name" + tyVars.joinToStr(" ", prefix = " ")
        return if (dataCtors.size == 1) "$dd = ${dataCtors[0].show()}"
        else {
            dd + withIndent { dataCtors.joinToString("\n$tab| ", prefix = "$tab= ") { it.show() } }
        }
    }

    private fun Decl.TypeDecl.show(): String {
        val td = "$name :: " + type.show()
        return if (td.length > maxColumns) {
            name + withIndent { type.showIndented() }
        } else td
    }

    private fun Decl.ValDecl.show(): String {
        val exp = if (exp is Expr.Ann) exp.exp else exp
        val (lambdaVars, expr) = exp.unnestLambdas()
        val prefix = name + lambdaVars.joinToStr(" ", prefix = " ") + " ="

        return if (shouldNewline(expr)) {
            prefix + withIndent { tab + expr.show() }
        } else "$prefix " + expr.show()
    }

    private fun DataConstructor.show(): String {
        return name + args.joinToStr(" ", prefix = " ") { it.show() }
    }

    private fun Expr.show(nested: Boolean = false): String {
        // TODO: show comment
        return when (this) {
            is Expr.Do -> {
                "do" + withIndent { exps.joinToString("\n$tab", prefix = tab) { it.show() } }
            }
            is Expr.Match -> {
                "case ${exp.show()} of" + withIndent { cases.joinToString("\n$tab", prefix = tab) { it.show() } }
            }
            is Expr.Let -> {
                // unroll lets
                val defs = mutableListOf<LetDef>()
                var exp = this
                while (exp is Expr.Let) {
                    defs += exp.letDef
                    exp = exp.body
                }
                val str = if (shouldNewline(exp)) withIndent { "$tab${exp.show()}" } else exp.show()
                "let " + withIndent(false) {
                    withIndent(false) {
                        defs.joinToStr("\n$tab") { it.show() }
                    }
                } + "\n${tab}in $str"
            }
            is Expr.If -> {
                val simple = thenCase.isSimple() && elseCase.isSimple()
                val str = if (simple) "if ${cond.show()} then ${thenCase.show()} else ${elseCase.show()}"
                else "if ${cond.show()}\n${tab}then ${thenCase.show()}\n${tab}else ${elseCase.show()}"
                if (nested) "($str)" else str
            }
            is Expr.App -> {
                val exps = Application.unparseApplication(this)
                val str = exps.joinToString(" ") { it.show() }
                if (nested) "($str)" else str
            }
            is Expr.Ann -> {
                val str = "${exp.show()} :: ${type.show()}"
                if (nested) "($str)" else str
            }
            is Expr.Lambda -> {
                val (lambdaVars, exp) = if (this.nested) {
                    unnestLambdas()
                } else {
                    listOf(binder) to body
                }
                val shown = if (shouldNewline(exp)) withIndent { exp.show() } else exp.show()
                val str = "\\" + lambdaVars.joinToString(" ") + " -> $shown"
                if (nested) "($str)" else str
            }
            is Expr.Var -> moduleName.joinToStr(".", postfix = ".") + name
            is Expr.IntE -> "$i"
            is Expr.FloatE -> "$f"
            is Expr.StringE -> s
            is Expr.CharE -> "'$c'"
            is Expr.Bool -> "$b"
            is Expr.Operator -> name
            is Expr.Parens -> exps.joinToString(" ", prefix = "(", postfix = ")") { it.show() }
            else -> "$this"
        }
    }

    private fun Case.show(): String {
        return pattern.show() + " -> " + exp.show()
    }

    private fun Pattern.show(nested: Boolean = false): String = when (this) {
        is Pattern.Wildcard -> "_"
        is Pattern.Var -> name
        is Pattern.Ctor -> {
            val str = name + fields.joinToStr(" ", prefix = " ") { it.show(true) }
            if (nested) "($str)" else str
        }
        is Pattern.LiteralP -> lit.show()
    }

    private fun LiteralPattern.show(): String = when (this) {
        is LiteralPattern.BoolLiteral -> "$b"
        is LiteralPattern.CharLiteral -> "'$c'"
        is LiteralPattern.StringLiteral -> s
        is LiteralPattern.IntLiteral -> "$i"
        is LiteralPattern.FloatLiteral -> "$f"
    }

    private fun LetDef.show(): String {
        val typ = if (type != null) "$name :: ${type.show()}\n$tab" else ""
        val (lambdaVars, exp) = expr.unnestLambdas()
        return "${typ}$name" + lambdaVars.joinToStr(" ", prefix = " ") + " = ${exp.show()}"
    }

    private fun Type.show(nested: Boolean = false): String = when (this) {
        is Type.TFun -> {
            val tlist = mutableListOf<String>()
            var fn = this
            while (fn is Type.TFun) {
                tlist += fn.arg.show(true)
                fn = fn.ret
            }
            tlist += fn.show(true)
            val str = tlist.joinToString(" -> ")
            if (nested) "($str)" else str
        }
        is Type.TForall -> {
            val (forallVars, exp) = unnestForalls(this)
            val str = "forall " + forallVars.joinToString(" ") + ". ${exp.show()}"
            if (nested) "($str)" else str
        }
        is Type.TConstructor -> {
            val str = name + types.joinToStr(" ", prefix = " ") { it.show(true) }
            if (nested) "($str)" else str
        }
        else -> "$this"
    }

    private fun Type.showIndented(prefix: String = "::"): String = when (this) {
        is Type.TFun -> {
            val tlist = mutableListOf<String>()
            var fn = this
            while (fn is Type.TFun) {
                tlist += fn.arg.show(true)
                fn = fn.ret
            }
            tlist += fn.show(true)
            tlist.joinToString("\n$tab-> ", prefix = "$tab$prefix ")
        }
        is Type.TForall -> {
            val (forallVars, exp) = unnestForalls(this)
            val str = if (exp is Type.TFun) exp.showIndented(".") else exp.show()
            "$tab$prefix forall " + forallVars.joinToString(" ", postfix = "\n") + str
        }
        else -> show()
    }

    private fun Comment.show(): String = if (isMulti) {
        "$tab/*\n$tab * " + comment.split("\n").joinToString("\n$tab *") + "\n$tab */"
    } else "// $comment"

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

    private fun Expr.unnestLambdas(): Pair<List<String>, Expr> {
        val lambdaVars = mutableListOf<String>()
        if (this is Expr.Lambda && !nested) return lambdaVars to this
        var expr = this
        var prev = true
        while (expr is Expr.Lambda) {
            if (!prev) break
            lambdaVars += expr.binder
            prev = expr.nested
            expr = expr.body
        }
        return lambdaVars to expr
    }

    private fun unnestForalls(forall: Type.TForall): Pair<List<String>, Type> {
        val forallVars = mutableListOf<String>()
        var fa: Type = forall
        while (fa is Type.TForall) {
            forallVars += fa.name
            fa = fa.type
        }
        return forallVars to fa
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
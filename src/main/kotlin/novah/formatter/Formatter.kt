package novah.formatter

import novah.frontend.*
import novah.frontend.typechecker.Type

/**
 * The canonical formatter for the language. Like gofmt
 */
class Formatter(private val ast: Module) {

    private val builder = StringBuilder()
    private var column = 1
    private var indent = ""

    fun format(): String {
        ast.format()
        if (ast.imports.isNotEmpty()) {
            doubleLineBreak()
            formatImports(ast.imports)
        }
        if (ast.decls.isNotEmpty()) {
            doubleLineBreak()
            formatDecls(ast.decls)
        }
        return builder.toString()
    }

    private fun Module.format() {
        if (comment != null) {
            comment!!.format()
        }
        print("module ${fullName()}")
        exports.format()
    }

    private fun ModuleExports.format() {
        if (this is ModuleExports.Hiding) {
            print(" hiding")
            printList(hides.sorted())
        }
        if (this is ModuleExports.Exposing) {
            print(" exposing")
            printList(exports.sorted())
        }
    }

    private fun formatImports(imports: List<Import>) {
        val imps = imports.sortedBy { it.fullName() }

        for (imp in imps) {
            if (imp != imps.first()) lineBreak()
            imp.format()
        }
    }

    private fun Import.format() {
        if (comment != null) comment!!.format()
        print("import ${fullName()}")
        if (this is Import.Exposing) {
            if (!exceed(calculateHSize(defs, ", "))) {
                print(" ( ")
                print(defs.joinToString(", "))
                print(" )")
            } else {
                printList(defs)
            }
        }
        if (alias() != null) {
            print(" as ${alias()}")
        }
    }

    private fun formatDecls(decls: List<Decl>) {
        var last = decls.first()
        for (decl in decls) {
            if (decl != decls.first()) {
                if (last is Decl.TypeDecl && decl is Decl.ValDecl && last.name == decl.name) lineBreak()
                else doubleLineBreak()
            }
            if (decl.comment != null) decl.comment!!.format()
            when (decl) {
                is Decl.DataDecl -> decl.format()
                is Decl.TypeDecl -> decl.format()
                is Decl.ValDecl -> decl.format()
            }
            last = decl
        }
    }

    private fun Decl.DataDecl.format() {
        print("type $name")
        if (tyVars.isNotEmpty()) print(" " + tyVars.joinToString(" "))

        val singleLine = dataCtors.joinToString(" | ")
        if (exceed(singleLine.length, maxColumns / 3)) {
            withIndent {
                print("= " + dataCtors.first())
                if (dataCtors.size > 1) {
                    for (dc in dataCtors.drop(1)) {
                        lineBreak()
                        print("| $dc")
                    }
                }
            }
        } else {
            print(" = $singleLine")
        }
    }

    private fun Decl.TypeDecl.format() {
        print("$name ::")
        typ.format()
    }

    private fun Decl.ValDecl.format() {
        print(name)
        // unnest lambdas
        val lambdaVars = mutableListOf<String>()
        var expr = if (exp is Expr.Ann) exp.exp else exp
        while (expr is Expr.Lambda && expr.nesting > 0) {
            lambdaVars += expr.binder
            expr = expr.body
        }
        if (lambdaVars.isNotEmpty()) print(" " + lambdaVars.joinToString(" "))
        print(" = ")
        expr.format()
    }

    private fun Expr.format(nested: Boolean = false) {
        when (this) {
            is Expr.Construction -> {
                if (nested) print("(")
                print(ctor)
                for (exp in fields) {
                    print(" ")
                    exp.format(true)
                }
                if (nested) print(")")
            }
            is Expr.Do -> {
                print("do {")
                withIndent {
                    for (exp in exps) {
                        exp.format()
                        if (exp != exps.last())
                            lineBreak()
                    }
                }
                lineBreak()
                print("}")
            }
            is Expr.Match -> {
                print("match ")
                exp.format()
                print(" {")
                withIndent {
                    for (case in cases) {
                        if (case != cases.first()) lineBreak()
                        case.pattern.format()
                        print(" -> ")
                        if (column > maxColumns / 3) {
                            withIndent { case.exp.format() }
                        } else case.exp.format()
                    }
                }
                lineBreak()
                print("}")
            }
            is Expr.If -> {
                val simple = thenCase.isSimple() && elseCase.isSimple()
                if (nested) print("(")
                print("if ")
                cond.format()
                if (simple) {
                    print(" then ")
                    thenCase.format()
                    print(" else ")
                    elseCase.format()
                } else {
                    withIndent {
                        print("then ")
                        thenCase.format()
                        lineBreak()
                        print("else ")
                        elseCase.format()
                    }
                }
                if (nested) print(")")
            }
            is Expr.App -> {
                if (nested) print("(")
                fn.format(true)
                print(" ")
                arg.format(true)
                if (nested) print(")")
            }
            is Expr.IntE -> print("$i")
            is Expr.FloatE -> print("$f")
            is Expr.StringE -> print("\"$s\"")
            is Expr.CharE -> print("'$c'")
            is Expr.Bool -> print("$b")
            is Expr.Var -> {
                if (moduleName.isEmpty()) print(name)
                else print(moduleName.joinToString(".") + "." + name)
            }
            is Expr.Operator -> print(name)
            else -> print("$this")
        }
    }

    private fun Pattern.format(nested: Boolean = false) {
        when (this) {
            is Pattern.Wildcard -> print("_")
            is Pattern.Var -> print(name)
            is Pattern.Ctor -> {
                if (nested) print("(")
                print(name)
                fields.forEach { pt ->
                    print(" ")
                    pt.format(true)
                }
                if (nested) print(")")
            }
            is Pattern.LiteralP -> lit.format()
        }
    }

    private fun LiteralPattern.format() {
        when (this) {
            is LiteralPattern.BoolLiteral -> print("$b")
            is LiteralPattern.CharLiteral -> print("'$c'")
            is LiteralPattern.StringLiteral -> print(s)
            is LiteralPattern.IntLiteral -> print("$i")
            is LiteralPattern.FloatLiteral -> print("$f")
        }
    }

    private fun Type.format() {
        when (this) {
            is Type.TFun -> {
                if (exceed(toString().length)) {
                    withIndent {
                        var fn = this
                        while (fn is Type.TFun) {
                            val f = fn as Type.TFun
                            print("${f.arg} ->")
                            lineBreak()
                            fn = f.ret
                        }
                        print("$fn")
                    }
                } else {
                    print(" ")
                    var fn = this
                    while (fn is Type.TFun) {
                        val f = fn as Type.TFun
                        print("${f.arg} -> ")
                        fn = f.ret
                    }
                    print("$fn")
                }
            }
            is Type.TForall -> {
                // unnest foralls
                val forallVars = mutableListOf<String>()
                var fa = this
                while (fa is Type.TForall) {
                    forallVars += fa.name
                    fa = fa.type
                }
                print(" forall ${forallVars.joinToString(" ")}.")
                fa.format()
            }
            else -> print(" $this")
        }
    }

    private fun Comment.format() {
        if (isMulti) {
            print("/*\n * ")
            print(comment.split("\n").joinToString("\n *"))
            lineBreak()
            print(" */")
        } else print("// $comment")
        lineBreak()
    }

    // Helpers

    private fun Expr.isSimple(): Boolean = when (this) {
        is Expr.IntE -> true
        is Expr.FloatE -> true
        is Expr.StringE -> true
        is Expr.CharE -> true
        is Expr.Bool -> true
        is Expr.Var -> true
        is Expr.Operator -> true
        else -> false
    }

    private fun calculateHSize(list: List<String>, separator: String = ""): Int {
        return list.map(String::length).sum() + (separator.length * (list.size - 1))
    }

    private fun printList(list: List<String>, start: String = "(", end: String = ")") {
        withIndent {
            print("$start ${list.first()}")
            for (exp in list.drop(1)) {
                lineBreak()
                print(", $exp")
            }
            if (list.size > 1) {
                lineBreak()
                print(end)
            } else print(" $end")
        }
    }

    private fun print(s: String) {
        column += s.length
        builder.append(s)
    }

    private fun lineBreak() {
        column = 1 + indent.length
        builder.append("\n$indent")
    }

    private fun doubleLineBreak() {
        column = 1 + indent.length
        builder.append("\n\n$indent")
    }

    private inline fun <T> withIndent(f: () -> T) {
        val oldIndent = indent
        indent = "$indent$tabSize"
        lineBreak()
        f()
        indent = oldIndent
    }

    private fun exceed(amount: Int, max: Int = maxColumns): Boolean = column + amount > max

    companion object {
        const val maxColumns = 120
        const val tabSize = "  " // 2 spaces
    }
}
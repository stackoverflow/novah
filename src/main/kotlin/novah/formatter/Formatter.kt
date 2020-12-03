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
        formatModule(ast)
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

    private fun formatModule(m: Module) {
        if (m.comment != null) {
            formatComment(m.comment!!)
        }
        print("module ${m.fullName()}")
        formatModuleExports(m.exports)
    }

    private fun formatModuleExports(me: ModuleExports) {
        if (me is ModuleExports.Hiding) {
            print(" hiding")
            printList(me.hides.sorted())
        }
        if (me is ModuleExports.Exposing) {
            print(" exposing")
            printList(me.exports.sorted())
        }
    }

    private fun formatImports(imports: List<Import>) {
        val imps = imports.sortedBy { it.fullName() }

        for (imp in imps) {
            if (imp != imps.first()) lineBreak()

            if (imp.comment != null) formatComment(imp.comment!!)
            print("import ${imp.fullName()}")
            if (imp is Import.Exposing) {
                if (!exceed(calculateHSize(imp.defs, ", "))) {
                    print(" ( ")
                    print(imp.defs.joinToString(", "))
                    print(" )")
                } else {
                    printList(imp.defs)
                }
            }
            if (imp.alias() != null) {
                print(" as ${imp.alias()}")
            }
        }
    }

    private fun formatDecls(decls: List<Decl>) {
        var last = decls.first()
        for (decl in decls) {
            if (decl != decls.first()) {
                if (last is Decl.TypeDecl && decl is Decl.ValDecl && last.name == decl.name) lineBreak()
                else doubleLineBreak()
            }
            if (decl.comment != null) formatComment(decl.comment!!)
            when (decl) {
                is Decl.DataDecl -> formatDataDecl(decl)
                is Decl.TypeDecl -> formatTypeDecl(decl)
                is Decl.ValDecl -> formatValDecl(decl)
            }
            last = decl
        }
    }

    private fun formatDataDecl(dd: Decl.DataDecl) {
        print("type ${dd.name}")
        if (dd.tyVars.isNotEmpty()) print(" " + dd.tyVars.joinToString(" "))

        val singleLine = dd.dataCtors.joinToString(" | ")
        if (exceed(singleLine.length, maxColumns / 3)) {
            withIndent {
                print("= " + dd.dataCtors.first())
                if (dd.dataCtors.size > 1) {
                    for (dc in dd.dataCtors.drop(1)) {
                        lineBreak()
                        print("| $dc")
                    }
                }
            }
        } else {
            print(" = $singleLine")
        }
    }

    private fun formatTypeDecl(td: Decl.TypeDecl) {
        print("${td.name} ::")
        formatType(td.typ)
    }

    private fun formatValDecl(vd: Decl.ValDecl) {
        print(vd.name)
        // unnest lambdas
        val lambdaVars = mutableListOf<String>()
        var expr = if (vd.exp is Expr.Ann) vd.exp.exp else vd.exp
        while (expr is Expr.Lambda && expr.nesting > 0) {
            lambdaVars += expr.binder
            expr = expr.body
        }
        if (lambdaVars.isNotEmpty()) print(" " + lambdaVars.joinToString(" "))
        print(" = ")
        formatExpr(expr)
    }

    private fun formatExpr(expr: Expr) {
        when (expr) {
            is Expr.Construction -> {
                print(expr.ctor)
                for (exp in expr.fields) {
                    print(" ")
                    formatExpr(exp)
                }
            }
            is Expr.Do -> {
                print("do {")
                withIndent {
                    for (exp in expr.exps) {
                        formatExpr(exp)
                        if (exp != expr.exps.last())
                            lineBreak()
                    }
                }
                lineBreak()
                print("}")
            }
            is Expr.Match -> {
                print("match ")
                formatExpr(expr.exp)
                print(" {")
                withIndent {
                    for (case in expr.cases) {
                    }
                }
                lineBreak()
                print("}")
            }
            else -> print("$expr")
        }
    }

    private fun formatType(t: Type) {
        when (t) {
            is Type.TFun -> {
                if (exceed(t.toString().length)) {
                    withIndent {
                        var fn = t
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
                    var fn = t
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
                var fa = t
                while (fa is Type.TForall) {
                    forallVars += fa.name
                    fa = fa.type
                }
                print(" forall ${forallVars.joinToString(" ")}.")
                formatType(fa)
            }
            else -> print(" $t")
        }
    }

    private fun formatComment(c: Comment) {
        if (c.isMulti) {
            print("/*\n * ")
            print(c.comment.split("\n").joinToString("\n *"))
            lineBreak()
            print(" */")
        } else print("// ${c.comment}")
        lineBreak()
    }

    // Helpers

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
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
            printList(hides)
        }
        if (this is ModuleExports.Exposing) {
            print(" exposing")
            printList(exports)
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

        if (dataCtors.size > 1) {
            withIndent {
                print("= " + dataCtors.first())
                for (dc in dataCtors.drop(1)) {
                    lineBreak()
                    print("| $dc")
                }
            }
        } else {
            print(" = ${dataCtors.joinToString(" | ")}")
        }
    }

    private fun Decl.TypeDecl.format() {
        print(name)
        if (exceed(typ.toString().length)) {
            lineBreak()
            print("$tabSize:: ")
        } else print(" :: ")
        typ.format()
    }

    private fun Decl.ValDecl.format() {
        print(name)
        val exp = if (exp is Expr.Ann) exp.exp else exp
        val (lambdaVars, expr) = exp.unnestLambdas()
        if (lambdaVars.isNotEmpty()) print(" " + lambdaVars.joinToString(" "))
        if (shouldNewline(expr)) {
            print(" =")
            withIndent { expr.format() }
        } else {
            print(" = ")
            expr.format()
        }
    }

    private fun Expr.format(nested: Boolean = false) {
        if (this.comment != null) {
            comment!!.format()
        }
        when (this) {
            is Expr.Do -> {
                print("do")
                withIndent {
                    for (exp in exps) {
                        exp.format()
                        if (exp != exps.last())
                            lineBreak()
                    }
                }
            }
            is Expr.Match -> {
                print("case ")
                exp.format()
                print(" of")
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
            }
            is Expr.Let -> {
                // unroll lets
                val defs = mutableListOf<LetDef>()
                var exp = this
                while (exp is Expr.Let) {
                    defs += exp.letDef
                    exp = exp.body
                }
                print("let ")
                defs.forEach { def ->
                    if (def != defs.first()) {
                        print("$tabSize$tabSize")
                    }
                    def.format()
                    lineBreak()
                }
                if (shouldNewline(exp)) {
                    print("in")
                    withIndent {
                        exp.format()
                    }
                } else {
                    print("in ")
                    exp.format(nested)
                }
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
                    lineBreak()
                    print("then ")
                    thenCase.format()
                    lineBreak()
                    print("else ")
                    elseCase.format()
                }
                if (nested) print(")")
            }
            is Expr.App -> {
                if (nested) print("(")
                val exps = Application.unparseApplication(this)
                for (e in exps) {
                    if (e != exps.first()) print(" ")
                    e.format()
                }
                if (nested) print(")")
            }
            is Expr.Ann -> {
                if (nested) print("(")
                exp.format()
                print(" :: ")
                type.format()
                if (nested) print(")")
            }
            is Expr.Lambda -> {
                if (nested) print("(")
                val (lambdaVars, exp) = if (this.nested) {
                    unnestLambdas()
                } else {
                    listOf(binder) to body
                }
                print("\\")
                print(lambdaVars.joinToString(" "))
                print(" -> ")
                if (shouldNewline(exp)) {
                    withIndent { exp.format() }
                } else exp.format()
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
            is Expr.Parens -> {
                print("(")
                for (e in exps) {
                    if (e != exps.first()) print(" ")
                    e.format()
                }
                print(")")
            }
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

    private fun LetDef.format() {
        print(name)
        val (lambdaVars, exp) = expr.unnestLambdas()
        if (lambdaVars.isNotEmpty()) {
            print(" ")
            print(lambdaVars.joinToString(" "))
        }
        print(" = ")
        exp.format()
    }

    private fun Type.format(nested: Boolean = false) {
        when (this) {
            is Type.TFun -> {
                if (exceed(toString().length)) {
                    withIndent(false) {
                        var fn = this
                        while (fn is Type.TFun) {
                            val f = fn as Type.TFun
                            if (f.arg is Type.TForall) {
                                f.arg.format(true)
                            } else print("${f.arg}")
                            lineBreak()
                            print("-> ")
                            fn = f.ret
                        }
                        fn.format(nested)
                    }
                } else {
                    var fn = this
                    while (fn is Type.TFun) {
                        val f = fn as Type.TFun
                        if (f.arg is Type.TForall) {
                            f.arg.format(true)
                        } else print("${f.arg}")
                        print(" -> ")
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
                if (nested) print("(")
                print("forall ${forallVars.joinToString(" ")}. ")
                fa.format()
                if (nested) print(")")
            }
            else -> print("$this")
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
        is Expr.IntE, is Expr.FloatE, is Expr.StringE, is Expr.CharE, is Expr.Bool -> true
        is Expr.Var, is Expr.Operator -> true
        else -> false
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

    private fun shouldNewline(e: Expr) = e is Expr.Let || e is Expr.If || e is Expr.Match

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

    private inline fun <T> withIndent(shouldBreak: Boolean = true, f: () -> T) {
        val oldIndent = indent
        indent = "$indent$tabSize"
        if (shouldBreak) lineBreak()
        f()
        indent = oldIndent
    }

    private fun exceed(amount: Int, max: Int = maxColumns): Boolean = column + amount > max

    companion object {
        const val maxColumns = 100
        const val tabSize = "  " // 2 spaces
    }
}
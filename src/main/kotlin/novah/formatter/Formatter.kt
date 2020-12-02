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
            print(m.comment!!.comment)
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

            print("import ${imp.fullName()}")
            if (imp is Import.Exposing) {
                if (calculateHSize(imp.defs, ", ") < maxColumns / 2) {
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
        for (decl in decls) {
            if (decl != decls.first()) doubleLineBreak()
            when (decl) {
                is Decl.DataDecl -> formatDataDecl(decl)
                is Decl.TypeDecl -> formatTypeDecl(decl)
                is Decl.ValDecl -> formatValDecl(decl)
            }
        }
    }

    private fun formatDataDecl(dd: Decl.DataDecl) {

    }

    private fun formatTypeDecl(td: Decl.TypeDecl) {
        print("${td.name} ::")

    }

    private fun formatValDecl(vd: Decl.ValDecl) {

    }

    private fun formatType(t: Type) {

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

    private inline fun <T> withIndent(f: () -> T): T {
        val oldIndent = indent
        indent = "$indent$tabSize"
        lineBreak()
        val res = f()
        indent = oldIndent
        return res
    }

    private fun exceed(amount: Int, max: Int = maxColumns): Boolean = column + amount > max

    companion object {
        const val maxColumns = 120
        const val tabSize = "  " // 2 spaces
    }
}
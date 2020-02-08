package novah.frontend

import io.kotlintest.specs.StringSpec
import novah.frontend.TestUtil.parseResource

class ParserSpec : StringSpec() {
    init {

        "Parser works correctly" {
            val ast = parseResource("Example.novah")

            println(ast.name)
            println(ast.imports)
            println(ast.exports)
            ast.decls.forEach { println(it) }
        }
    }
}
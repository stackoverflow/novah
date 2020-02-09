package novah.frontend

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import novah.Decl
import novah.Expression.*
import novah.Module
import novah.frontend.TestUtil.parseResource
import novah.show

class ParserSpec : StringSpec() {
    init {

        "Parser correctly parse operators" {
            val ast = parseResource("Operators.novah")

            val x = "((`||` ((`||` w) ((`&&` r) x))) p)"
            val fl = "(((`>>` ((`>>` a) b)) c) 1)"
            val fr = "(((`<<` a) ((`<<` b) c)) 1)"
            val l1 = "((`+` 3) ((`*` ((`*` ((`^` 7) 4)) 6)) 9))"
            val l2 = "((`+` ((`*` ((`*` ((`^` 3) 7)) 4)) 6)) 9)"
            val r1 = "((`\$` (bla 3)) ((`\$` (df 4)) pa))"
            val r2 = "((`:` 3) ((`:` 5) ((`:` 7) Nil)))"

            ast.byName("x") shouldBe x
            ast.byName("fl") shouldBe fl
            ast.byName("fr") shouldBe fr
            ast.byName("l1") shouldBe l1
            ast.byName("l2") shouldBe l2
            ast.byName("r1") shouldBe r1
            ast.byName("r2") shouldBe r2
        }

        "Parser correctly parse lambdas" {
            val ast = parseResource("Lambda.novah")

            val simpleL = Lambda("x", listOf(Var("x")))
            val multiL = Lambda("x", listOf(Lambda("y", listOf(Lambda("z", listOf(IntE(1)))))))

            val simple = ast.decls[0] as Decl.VarDecl
            val multi = ast.decls[1] as Decl.VarDecl
            val simple2 = ast.decls[2] as Decl.VarDecl
            val multi2 = ast.decls[3] as Decl.VarDecl

            simple.def.defs[0].expr shouldBe simpleL
            multi.def.defs[0].expr shouldBe multiL

            // unsugared versions should be the same as sugared ones
            simple.def.defs[0].expr shouldBe simple2.def.defs[0].expr
            multi.def.defs[0].expr shouldBe multi2.def.defs[0].expr
        }

        "!Parser works correctly" {
            val ast = parseResource("Example.novah")

            println(ast.show())
        }
    }

    private fun Module.byName(name: String) =
        decls.filterIsInstance<Decl.VarDecl>().map { it.def.defs[0] }.find { it.name == name }?.expr?.show()
}
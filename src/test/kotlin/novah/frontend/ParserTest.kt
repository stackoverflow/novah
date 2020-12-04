package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import novah.formatter.Formatter
import novah.frontend.TestUtil._i
import novah.frontend.TestUtil._var
import novah.frontend.TestUtil.abs
import novah.frontend.TestUtil.parseResource

class ParserSpec : StringSpec({

    fun Module.byName(name: String) =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp?.show()

    fun compareLambdas(l1: Expr.Lambda, l2: Expr.Lambda): Boolean {
        if (l1.binder != l2.binder) return false
        return if (l1.body is Expr.Lambda && l2.body is Expr.Lambda) {
            compareLambdas(l1.body as Expr.Lambda, l2.body as Expr.Lambda)
        } else l1.body == l2.body
    }

    "Parser correctly parses operators" {
        val ast = parseResource("Operators.novah")

        val x = "((w `||` (r `&&` x)) `||` p)"
        val fl = "(((a `>>` b) `>>` c) 1)"
        val fr = "((a `<<` (b `<<` c)) 1)"
        val l1 = "(3 `+` (((7 `^` 4) `*` 6) `*` 9))"
        val l2 = "((((3 `^` 7) `*` 4) `*` 6) `+` 9)"
        val r1 = "((bla 3) `\$` ((df 4) `\$` pa))"
        val r2 = "(3 `:` (5 `:` (7 `:` Nil)))"

        ast.byName("x") shouldBe x
        ast.byName("fl") shouldBe fl
        ast.byName("fr") shouldBe fr
        ast.byName("l1") shouldBe l1
        ast.byName("l2") shouldBe l2
        ast.byName("r1") shouldBe r1
        ast.byName("r2") shouldBe r2
    }

    "Parser correctly parses lambdas" {
        val ast = parseResource("Lambda.novah")

        val simpleL = abs("x", _var("x"))
        val multiL = abs("x", abs("y", abs("z", _i(1))))

        val simple = ast.decls[0] as Decl.ValDecl
        val multi = ast.decls[1] as Decl.ValDecl
        val simple2 = ast.decls[2] as Decl.ValDecl
        val multi2 = ast.decls[3] as Decl.ValDecl

        simple.exp shouldBe simpleL
        multi.exp shouldBe multiL

        // unsugared versions should be the same as sugared ones
        compareLambdas(simple.exp as Expr.Lambda, simple2.exp as Expr.Lambda) shouldBe true
        compareLambdas(multi.exp as Expr.Lambda, multi2.exp as Expr.Lambda) shouldBe true
    }

    "Lexer and Parser correctly parse comments" {
        val ast = parseResource("Comments.novah")

        val data = ast.decls[0] as Decl.DataDecl
        val type = ast.decls[1] as Decl.TypeDecl
        val vard = ast.decls[3] as Decl.ValDecl

        data.comment?.comment should contain("comments on type definitions work")
        type.comment?.comment should contain("comments on var\n types work")
        vard.comment?.comment should contain("comments on var declaration work")
    }

    "Parser correctly type hints" {
        val ast = parseResource("Hints.novah")

        //println(ast.show())
    }

    "Exported definitions have correct visibility" {
        val ast = parseResource("Example.novah")

        //ast.exportList shouldBe listOf("Maybe", "num", "doub", "x", "stuff", "id", "ex", "doIt", "fact")

        val formatter = Formatter(ast)
        println(formatter.format())
        //println(ast.show())
    }
})
package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import novah.ast.source.Decl
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.formatter.Formatter
import novah.frontend.TestUtil._i
import novah.frontend.TestUtil._v
import novah.frontend.TestUtil.abs
import novah.frontend.TestUtil.parseResource

class ParserSpec : StringSpec({

    val fmt = Formatter()

    fun Module.byName(name: String) =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp?.toString()

    fun Module.findApp(name: String) =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp as Expr.App

    fun compareLambdas(lam1: Expr, lam2: Expr): Boolean {
        val l1 = lam1 as Expr.Lambda
        val l2 = lam2 as Expr.Lambda
        if (l1.binders != l2.binders) return false
        return if (l1.body is Expr.Lambda && l2.body is Expr.Lambda) {
            compareLambdas(l1.body as Expr.Lambda, l2.body as Expr.Lambda)
        } else l1.body == l2.body
    }

    "Parser correctly parses operators" {
        val ast = parseResource("Operators.novah")

        val x = "((|| ((|| w) ((&& r) x))) p)"
        val fl = "((((>> ((>> a) b)) c)) 1)"
        val fr = "((((<< a) ((<< b) c))) 1)"
        val l1 = "((+ 3) ((* ((* ((^ 7) 4)) 6)) 9))"
        val l2 = "((+ ((* ((^ 3) (((* 7) 4)))) 6)) 9)"
        val r1 = "(($ (bla 3)) (($ (df 4)) pa))"
        val r2 = "((: 3) ((: 5) ((: 7) Nil)))"
        val ap = "(((fn 3) 4) 5)"
        val a2 = "(fn ((fn2 8)))"
        val co = "(((fn 'x') y) (((Some (((+ 3) 4))) 1)))"

        ast.byName("x") shouldBe x
        ast.byName("fl") shouldBe fl
        ast.byName("fr") shouldBe fr
        ast.byName("l1") shouldBe l1
        ast.byName("l2") shouldBe l2
        ast.byName("r1") shouldBe r1
        ast.byName("r2") shouldBe r2
        ast.byName("ap") shouldBe ap
        ast.byName("a2") shouldBe a2
        ast.byName("co") shouldBe co
    }

    "Parser correctly unparses operators" {
        val ast = parseResource("Operators.novah")

        val x = "w || r && x || p"
        val fl = "(a >> b >> c) 1"
        val fr = "(a << b << c) 1"
        val l1 = "3 + 7 ^ 4 * 6 * 9"
        val l2 = "3 ^ (7 * 4) * 6 + 9"
        val r1 = "bla 3 $ df 4 $ pa"
        val r2 = "3 : 5 : 7 : Nil"
        val ap = "fn 3 4 5"
        val a2 = "fn (fn2 8)"
        val co = "fn 'x' y (Some (3 + 4) 1)"

        fun show(es: List<Expr>) = es.joinToString(" ") { fmt.show(it) }

        show(Application.unparseApplication(ast.findApp("x"))) shouldBe x
        show(Application.unparseApplication(ast.findApp("fl"))) shouldBe fl
        show(Application.unparseApplication(ast.findApp("fr"))) shouldBe fr
        show(Application.unparseApplication(ast.findApp("l1"))) shouldBe l1
        show(Application.unparseApplication(ast.findApp("l2"))) shouldBe l2
        show(Application.unparseApplication(ast.findApp("r1"))) shouldBe r1
        show(Application.unparseApplication(ast.findApp("r2"))) shouldBe r2
        show(Application.unparseApplication(ast.findApp("ap"))) shouldBe ap
        show(Application.unparseApplication(ast.findApp("a2"))) shouldBe a2
        show(Application.unparseApplication(ast.findApp("co"))) shouldBe co
    }

    "Parser correctly parses lambdas" {
        val ast = parseResource("Lambda.novah")

        val simpleL = abs("x", _v("x"))
        val multiL = abs(listOf("x","y", "z"), _i(1))

        val simple = ast.decls[0] as Decl.ValDecl
        val multi = ast.decls[1] as Decl.ValDecl

        simple.exp shouldBe simpleL
        compareLambdas(multi.exp, multiL) shouldBe true
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

        println(fmt.format(ast))
        //println(ast.show())
    }
})
package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.blocking.forAll
import io.kotest.data.row
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import io.kotest.matchers.types.shouldBeInstanceOf
import novah.ast.Desugar
import novah.ast.source.*
import novah.data.Err
import novah.formatter.Formatter
import novah.frontend.TestUtil._i
import novah.frontend.TestUtil._v
import novah.frontend.TestUtil.abs
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.parseResource
import novah.frontend.TestUtil.parseString

class ParserSpec : StringSpec({

    val fmt = Formatter()

    fun Module.byName(name: String) =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp?.toString()

    fun Module.findApp(name: String) =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp as Expr.App

    fun compareFunpattern(f1: FunparPattern, f2: FunparPattern): Boolean = when (f1) {
        is FunparPattern.Ignored -> f2 is FunparPattern.Ignored
        is FunparPattern.Unit -> f2 is FunparPattern.Unit
        is FunparPattern.Bind -> f2 is FunparPattern.Bind && f1.binder.name == f2.binder.name
    }

    fun compareLambdas(lam1: Expr, lam2: Expr): Boolean {
        val l1 = lam1 as Expr.Lambda
        val l2 = lam2 as Expr.Lambda
        if (l1.patterns.size != l2.patterns.size) return false
        if (!l1.patterns.zip(l2.patterns).all { (f1, f2) -> compareFunpattern(f1, f2) }) return false
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
        val r2 = "((:: 3) ((:: 5) ((:: 7) Nil)))"
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
        val r2 = "3 :: 5 :: 7 :: Nil"
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
        val multiL = abs(listOf("x", "y", "z"), _i(1))

        val simple = ast.decls[0] as Decl.ValDecl
        val multi = ast.decls[1] as Decl.ValDecl

        compareLambdas(simple.exp, simpleL) shouldBe true
        compareLambdas(multi.exp, multiL) shouldBe true
    }

    "Lexer and Parser correctly parse comments" {
        val ast = parseResource("Comments.novah")

        val data = ast.decls[0] as Decl.DataDecl
        val type = ast.decls[1] as Decl.ValDecl
        val vard = ast.decls[2] as Decl.ValDecl

        data.comment?.comment should contain("comments on type definitions work")
        type.comment?.comment should contain("comments on var\n types work")
        vard.comment?.comment should contain("comments on var declaration work")
    }

    "Parse type hints correctly" {
        val ast = parseResource("Hints.novah")

        val x = ast.decls.filterIsInstance<Decl.ValDecl>().find { it.name == "x" }?.exp!!

        x.shouldBeInstanceOf<Expr.Ann>()
        x.type shouldBe Type.TVar("String")
    }

    "Complex top level definitions are disallowed" {

        forAll(
            row("decl = if true then 1 else 2"),
            row("decl = let a = 3 in a"),
            row("decl = case 1 of _ -> 0"),
            row("decl = do println 2"),
            row("decl = do println 2 :: Int")
        ) { code ->
            val ast = parseString(code.module())
            val des = Desugar(ast)
            des.desugar().shouldBeInstanceOf<Err<*>>()
        }

        // Annotations should work
        val ast = parseString("decl = 2 : Int".module())
        val des = Desugar(ast)
        des.desugar().unwrap()
    }

    "Constructors with the same name as the type are not allowed unless there's only one" {
        val code = "type Wrong = Wrong | NotWrong".module()

        val ast = parseString(code)
        val des = Desugar(ast)
        des.desugar().shouldBeInstanceOf<Err<*>>()
    }

    "Constructors with the same name as the type are allowed for single constructor types" {
        val code = "type Tuple a b = Tuple a b".module()

        val ast = parseString(code)
        val des = Desugar(ast)
        shouldNotThrowAny {
            des.desugar()
        }
    }

    "Exported definitions have correct visibility" {
        val ast = parseResource("Example.novah")

        //ast.exportList shouldBe listOf("Maybe", "num", "doub", "x", "stuff", "id", "ex", "doIt", "fact")

        println(fmt.format(ast))
        //println(ast.show())
    }
})
package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.types.beInstanceOf
import novah.ast.Desugar
import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.frontend.TestUtil.forall
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.parseString
import novah.frontend.TestUtil.tfun
import novah.frontend.TestUtil.tvar
import novah.frontend.typechecker.Inference.infer
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Prim.tUnit
import novah.frontend.typechecker.Type

class TypedASTSpec : StringSpec({

    beforeTest {
        TestUtil.setupContext()
    }

    fun parseAndDesugar(code: String): Module {
        val ast = parseString(code)
        return Desugar(ast).desugar()
    }

    fun toMap(m: Module): Map<String, Expr> {
        val map = mutableMapOf<String, Expr>()
        m.decls.filterIsInstance<Decl.ValDecl>().forEach { map[it.name] = it.exp }
        return map
    }

    "expressions have types after typechecking" {
        val code = """
            x = 0x123fa
            
            ife = if true then 3 * 4 else 5 + 2
            
            lam = \x -> x > 10
            
            lett = let x = true in x && false
            
            app = println "hello"
            
            fall = \x -> x
        """.module()

        val ast = parseAndDesugar(code)
        val map = toMap(ast)

        map["x"]?.type shouldBe null
        map["ife"]?.type shouldBe null
        map["lam"]?.type shouldBe null
        map["lett"]?.type shouldBe null
        map["app"]?.type shouldBe null
        map["fall"]?.type shouldBe null

        infer(ast)

        map["x"]?.type shouldBe tInt
        map["ife"]?.type shouldBe tInt
        map["lam"]?.type shouldBe tfun(tInt, tBoolean)
        map["lett"]?.type shouldBe tBoolean
        map["app"]?.type shouldBe tUnit
        map["fall"]?.type?.substFreeVar("x") shouldBe forall("x", tfun(tvar("x"), tvar("x")))

        //map.forEach { (k, v) -> println("$k :: ${v.type}") }
    }

    "inner expressions have types after typecheck" {
        val code = """
            x = \y -> "asd"
            
            f = if true then 6 else 5 * 4
            
            a = id 4 :: Int
        """.module()

        val ast = parseAndDesugar(code)
        infer(ast)
        val map = toMap(ast)

        (map["x"] as Expr.Lambda).body.type shouldBe tString

        val iff = map["f"] as Expr.If
        iff.cond.type shouldBe tBoolean
        iff.thenCase.type shouldBe tInt
        iff.elseCase.type shouldBe tInt
        val elseCase = iff.elseCase as Expr.App
        elseCase.arg.type shouldBe tInt
        elseCase.fn.type shouldBe tfun(tInt, tInt)
        (elseCase.fn as Expr.App).fn.type shouldBe tfun(tInt, tfun(tInt, tInt))
        (elseCase.fn as Expr.App).arg.type shouldBe tInt

        (map["a"] as Expr.Ann).exp.type shouldBe tInt
    }

    "metas are resolved after typecheck" {
        val code = """
            ife = if true then 3 * 4 else id 5
            
            lam = \x -> x > 10
            
            lett = let f x = x + x in f 2
            
            fall = \x -> id x
            
            fif = \x -> if true then 5 else id x
            
            doo = \x -> do
                id x
                x + 4
                true
            
        """.module()

        val ast = parseAndDesugar(code)
        infer(ast)

        val tt = TypeTraverser(ast) { _, t ->
            //println("$n :: $t")
            t shouldNot beInstanceOf<Type.TMeta>()
        }
        tt.run()
    }
})
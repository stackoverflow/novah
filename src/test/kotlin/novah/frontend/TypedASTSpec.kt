package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.frontend.TestUtil.findUnbound
import novah.frontend.TestUtil.forall
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName
import novah.frontend.TestUtil.simplify
import novah.frontend.TestUtil.tbound
import novah.frontend.TestUtil.tfun
import novah.frontend.hmftypechecker.tBoolean
import novah.frontend.hmftypechecker.tInt
import novah.frontend.hmftypechecker.tString
import novah.frontend.hmftypechecker.tUnit

class TypedASTSpec : StringSpec({

    fun toMap(m: Module): Map<String, Expr> {
        val map = mutableMapOf<String, Expr>()
        m.decls.filterIsInstance<Decl.ValDecl>().forEach { map[it.name] = it.exp }
        return map
    }

    "expressions have types after typechecking" {
        val code = """
            num = 0x123fa
            
            ife x = if true then 4 else x
            
            lam = \x -> x && true
            
            lett b = let x = true in x && b
            
            app s = println s
            
            fall = \x -> x
        """.module()

        val ast = TestUtil.compileCode(code).ast
        val map = toMap(ast)

        map["num"]?.type shouldBe tInt
        map["ife"]?.type?.simplify() shouldBe tfun(tInt, tInt)
        map["lam"]?.type?.simplify() shouldBe tfun(tBoolean, tBoolean)
        map["lett"]?.type?.simplify() shouldBe tfun(tBoolean, tBoolean)
        map["app"]?.type?.simplify() shouldBe tfun(tString, tUnit)
        map["fall"]?.type?.simpleName() shouldBe "forall t1. t1 -> t1"
    }

    "inner expressions have types after typecheck" {
        val code = """
            id x = x
            
            f1 = \y -> "asd"
            
            f2 a = if true then 6 else a
            
            f3 a = id a : Int
        """.module()

        val ast = TestUtil.compileCode(code).ast
        val map = toMap(ast)

        (map["f1"] as Expr.Lambda).body.type shouldBe tString

        val iff = (map["f2"] as Expr.Lambda).body as Expr.If
        iff.cond.type shouldBe tBoolean
        iff.thenCase.type?.simplify() shouldBe tInt
        iff.elseCase.type?.simplify() shouldBe tInt
        val elseCase = iff.elseCase as Expr.Var
        elseCase.type?.simplify() shouldBe tInt

        ((map["f3"] as Expr.Lambda).body as Expr.Ann).exp.type shouldBe tInt
    }

    "metas are resolved after typecheck" {
        val code = """
            type Maybe a = Just a | Nothing
            
            id x = x
            
            ife a = if true then 3 else id a
            
            lam = \x -> x && true
            
            lett a = let f x = x in f a
            
            fall = \x -> id x
            
            fif = \x -> if true then 5 else id x
            
            doo = \x -> do
                id x
                x
                true
            
            lamb : Int -> String -> Unit
            lamb x y = do
              Just "a"
              ()
            
            main args = do
              lamb 4 "hello"
            
        """.module()

        val ast = TestUtil.compileCode(code).ast

        val tt = TypeTraverser(ast) { _, t ->
            t?.findUnbound() shouldBe emptyList()
        }
        tt.run()
    }
})
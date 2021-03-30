/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Module
import novah.frontend.TestUtil.findUnbound
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

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

        map["num"]?.type?.simpleName() shouldBe "Int32"
        map["ife"]?.type?.simpleName() shouldBe "Int32 -> Int32"
        map["lam"]?.type?.simpleName() shouldBe "Boolean -> Boolean"
        map["lett"]?.type?.simpleName() shouldBe "Boolean -> Boolean"
        map["app"]?.type?.simpleName() shouldBe "t1 -> Unit"
        map["fall"]?.type?.simpleName() shouldBe "t1 -> t1"
    }

    "inner expressions have types after typecheck" {
        val code = """
            id x = x
            
            f1 = \_ -> "asd"
            
            f2 a = if true then 6 else a
            
            f3 a = id a : Int
        """.module()

        val ast = TestUtil.compileCode(code).ast
        val map = toMap(ast)

        (map["f1"] as Expr.Lambda).body.type?.simpleName() shouldBe "String"

        val iff = (map["f2"] as Expr.Lambda).body as Expr.If
        iff.cond.type?.simpleName() shouldBe "Boolean"
        iff.thenCase.type?.simpleName() shouldBe "Int32"
        iff.elseCase.type?.simpleName() shouldBe "Int32"
        val elseCase = iff.elseCase as Expr.Var
        elseCase.type?.simpleName() shouldBe "Int32"

        ((map["f3"] as Expr.Lambda).body as Expr.Ann).exp.type?.simpleName() shouldBe "Int32"
    }

    "metas are resolved after typecheck" {
        val code = """
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
            lamb _ _ = do
              Some "a"
              ()
            
            main _ = do
              lamb 4 "hello"
            
        """.module()

        val ast = TestUtil.compileCode(code).ast

//        val tt = TypeTraverser(ast) { _, t ->
//            t?.findUnbound() shouldBe emptyList()
//        }
//        tt.run()
    }
})
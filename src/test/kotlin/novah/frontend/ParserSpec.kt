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

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.contain
import io.kotest.matchers.types.shouldBeInstanceOf
import novah.ast.Desugar
import novah.ast.source.Decl
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.ast.source.Pattern
import novah.formatter.Formatter
import novah.frontend.TestUtil._i
import novah.frontend.TestUtil._v
import novah.frontend.TestUtil.abs
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.parseResource
import novah.frontend.TestUtil.parseString
import novah.frontend.typechecker.Typechecker
import novah.main.NovahClassLoader

class ParserSpec : StringSpec({

    val fmt = Formatter()

    fun Module.findApp(name: String): Expr =
        decls.filterIsInstance<Decl.ValDecl>().find { it.name == name }?.exp!!

    fun comparePattern(p1: Pattern, p2: Pattern): Boolean = when {
        p1 is Pattern.Var && p2 is Pattern.Var -> p1.v.name == p2.v.name
        p1 is Pattern.ImplicitPattern && p2 is Pattern.ImplicitPattern -> p1.pat == p2.pat
        else -> false
    }
    
    fun compareLambdas(lam1: Expr, lam2: Expr): Boolean {
        val l1 = lam1 as Expr.Lambda
        val l2 = lam2 as Expr.Lambda
        if (l1.patterns.size != l2.patterns.size) return false
        if (!l1.patterns.zip(l2.patterns).all { (f1, f2) -> comparePattern(f1, f2) }) return false
        return if (l1.body is Expr.Lambda && l2.body is Expr.Lambda) {
            compareLambdas(l1.body as Expr.Lambda, l2.body as Expr.Lambda)
        } else l1.body == l2.body
    }

    "Parser correctly parses operators" {
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

        fmt.show(ast.findApp("x")) shouldBe x
        fmt.show(ast.findApp("fl")) shouldBe fl
        fmt.show(ast.findApp("fr")) shouldBe fr
        fmt.show(ast.findApp("l1")) shouldBe l1
        fmt.show(ast.findApp("l2")) shouldBe l2
        fmt.show(ast.findApp("r1")) shouldBe r1
        fmt.show(ast.findApp("r2")) shouldBe r2
        fmt.show(ast.findApp("ap")) shouldBe ap
        fmt.show(ast.findApp("a2")) shouldBe a2
        fmt.show(ast.findApp("co")) shouldBe co
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
        val ast = parseResource("comments.novah")

        val data = ast.decls[0] as Decl.TypeDecl
        val type = ast.decls[1] as Decl.ValDecl
        val vard = ast.decls[2] as Decl.ValDecl

        data.comment?.comment should contain("comments on type definitions work")
        type.comment?.comment should contain("\n comments on var\n types work\n")
        vard.comment?.comment should contain("comments on var declaration work")
    }

    "Parse type hints correctly" {
        val ast = parseResource("Hints.novah")

        val x = ast.decls.filterIsInstance<Decl.ValDecl>().find { it.name == "x" }?.exp!!

        x.shouldBeInstanceOf<Expr.Ann>()
        fmt.show(x.type) shouldBe "String"
    }

    "Constructors with the same name as the type are not allowed unless there's only one" {
        val code = "type Wrong = Wrong | NotWrong".module()

        val ast = parseString(code)
        val des = Desugar(ast, Typechecker(NovahClassLoader(null)))
        des.desugar()
        val errs = des.errors()
        errs.size shouldBe 1
        
        errs.first().msg shouldBe "Multi constructor type cannot have the same name as their type: Wrong."
    }

    "Constructors with the same name as the type are allowed for single constructor types" {
        val code = "type Tuple a b = Tuple a b".module()

        val ast = parseString(code)
        val des = Desugar(ast, Typechecker(NovahClassLoader(null)))
        shouldNotThrowAny {
            des.desugar()
        }
    }
    
    "Indentation is correctly parsed" {
        val code = """
            module indentationTest
            
            import novah.computation
            
            (**) : a -> a
            (**) x = x
            
            rec = { fun: \x -> x, notfun: 0 }
            
            foo =
              \x ->
                x + 1
            
            fun () =
              let x =
                1
                2
              x
            
            fun x =
              while true do
                println "hello"
                x
            
            fun x =
              case x of
                Some _ -> 1
                None -> 0
        """.trimIndent()

        val ast = parseString(code)
        val des = Desugar(ast, Typechecker(NovahClassLoader(null)))
        shouldNotThrowAny {
            des.desugar()
        }
    }
})
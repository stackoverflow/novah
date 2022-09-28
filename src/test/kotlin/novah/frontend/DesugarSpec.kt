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
import novah.ast.Desugar
import novah.ast.canonical.DataConstructor
import novah.ast.canonical.Decl
import novah.ast.source.Visibility
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.parseString
import novah.frontend.TestUtil.simpleName
import novah.frontend.typechecker.Typechecker
import novah.main.NovahClassLoader

class DesugarSpec : StringSpec({

    fun List<Decl.TypeDecl>.byName(n: String) = find { it.name.value == n }!!
    fun List<Decl.ValDecl>.byName(n: String) = find { it.name.value == n }!!
    fun List<DataConstructor>.byName(n: String) = find { it.name.value == n }!!

    "visibility is correctly set" {
        val code = """
            module test
            
            pub+
            type AllVis = AllVis1 | AllVis2
            
            pub
            type NoVis = NoVis1 | NoVis2
            
            type Hidden = Hidden1
            
            pub
            x = 3
            
            y = true
        """.trimIndent()

        val ast = parseString(code)
        val dast = Desugar(ast, Typechecker(NovahClassLoader(null))).desugar().unwrap()

        val tds = dast.decls.filterIsInstance<Decl.TypeDecl>()

        val allVis = tds.byName("AllVis")
        allVis.visibility shouldBe Visibility.PUBLIC
        allVis.dataCtors.byName("AllVis1").visibility shouldBe Visibility.PUBLIC
        allVis.dataCtors.byName("AllVis2").visibility shouldBe Visibility.PUBLIC

        val noVis = tds.byName("NoVis")
        noVis.visibility shouldBe Visibility.PUBLIC
        noVis.dataCtors.byName("NoVis1").visibility shouldBe Visibility.PRIVATE
        noVis.dataCtors.byName("NoVis2").visibility shouldBe Visibility.PRIVATE

        val hidden = tds.byName("Hidden")
        hidden.visibility shouldBe Visibility.PRIVATE
        hidden.dataCtors.byName("Hidden1").visibility shouldBe Visibility.PRIVATE

        val dds = dast.decls.filterIsInstance<Decl.ValDecl>()

        dds.byName("x").visibility shouldBe Visibility.PUBLIC
        dds.byName("y").visibility shouldBe Visibility.PRIVATE
    }
    
    "anonymous function arguments" {
        val code = """
            foreign import java.lang.Math
            
            f1 = if _ then 1 else -1
            
            f2 cond = if cond == 0 then _ else _
            
            f3 = case _ of
              [] -> 0
              [_ :: xs] -> 1 + (f3 xs)
            
            f4 = case _, _ of
              Some x, Some y -> x + y
              Some x, None -> x
              None, Some y -> y
              None, None -> -1
            
            f5 = _.name
            
            f6 = _.address.street
            
            f7 = { name: _, age: _, id: _ | _ }
            
            f8 : {{ Plus a }} -> a -> a -> a
            f8 {{p}} x = (_ + x)
            
            f9 : Int -> Int -> Int
            f9 y = (y + _)
            
            f10 : Int -> Int -> Int
            f10 = (_ + _)
            
            f11 = { - name, age, id | _ }
            
            f12 = { .user.name = _ | _ }
            
            f13 = { + _, { age: 10 } }
            
            f14 = List.map (_ : String)#indexOf("a") ["ba", "be", "ab"]
            
            f15 = List.map "fox"#charAt(_) [0, 1, 2]
            
            f16 = List.map Math#exp(_) [3.0, 5.0, 9.0]
            
            f17 = _.[0]
        """.module()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Boolean -> Int32"
        ds["f2"]?.type?.simpleName() shouldBe "Int32 -> t1 -> t1 -> t1"
        ds["f3"]?.type?.simpleName() shouldBe "List t1 -> Int32"
        ds["f4"]?.type?.simpleName() shouldBe "Option Int32 -> Option Int32 -> Int32"
        ds["f5"]?.type?.simpleName() shouldBe "{ name : t2 | t1 } -> t2"
        ds["f6"]?.type?.simpleName() shouldBe "{ address : { street : t3 | t2 } | t1 } -> t3"
        ds["f7"]?.type?.simpleName() shouldBe "t1 -> t2 -> t3 -> { | t4 } -> { age : t2, id : t3, name : t1 | t4 }"
        ds["f8"]?.type?.simpleName() shouldBe "{{ Plus t1 }} -> t1 -> t1 -> t1"
        ds["f9"]?.type?.simpleName() shouldBe "Int32 -> Int32 -> Int32"
        ds["f10"]?.type?.simpleName() shouldBe "Int32 -> Int32 -> Int32"
        ds["f11"]?.type?.simpleName() shouldBe "{ name : t4, age : t3, id : t2 | t1 } -> { | t1 }"
        ds["f12"]?.type?.simpleName() shouldBe "{ user : { name : t3 | t2 } | t1 } -> t3 -> { user : { name : t3 | t2 } | t1 }"
        ds["f13"]?.type?.simpleName() shouldBe "{ | t1 } -> { age : Int32 | t1 }"
        ds["f14"]?.type?.simpleName() shouldBe "List Int32"
        ds["f15"]?.type?.simpleName() shouldBe "List Char"
        ds["f16"]?.type?.simpleName() shouldBe "List Float64"
        ds["f17"]?.type?.simpleName() shouldBe "List t1 -> t1"
    }

    "statements can have multiple expressions" {
        val code = """
            foreign import java.lang.Exception
            
            fun () =
              while true do
                let x = 1
                x
            
            fun2 () =
              try
                let x = 1
                x
              catch
                :? Exception -> 2
        """.module()

        TestUtil.compileCode(code)
    }

    "shadowing of qualified vars work" {
        val code = """
            bla : Int32
            bla =
              let count = 2
              List.count identity [true]
        """.module()

        TestUtil.compileCode(code)
    }

    "variables and fully qualified variables do not collide" {
        val code = """
            fun =
              let size = List.size []
              size
        """.module()

        TestUtil.compileCode(code)
    }

    "auto derive for equals" {
        val code = """
            #[derive: {equalsPoint: "Equals"}]
            type Point = Point Int Int
            
            #[derive: {equalsEnum: "Equals"}]
            type Enum = A | B | C
            
            #[derive: {equalsComplex: "Equals"}]
            type Complex a = Ctor1 Int a | Ctor2 String | Ctor3
        """.module()

        TestUtil.compileCode(code)
    }
})
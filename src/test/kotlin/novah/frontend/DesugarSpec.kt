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

class DesugarSpec : StringSpec({

    fun List<Decl.TypeDecl>.byName(n: String) = find { it.name == n }!!
    fun List<Decl.ValDecl>.byName(n: String) = find { it.name == n }!!
    fun List<DataConstructor>.byName(n: String) = find { it.name == n }!!

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
        val dast = Desugar(ast).desugar().unwrap()

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
            foreign import novah.Core:sum(Int, Int)
            
            f1 = if _ then 1 else -1
            
            f2 cond = if cond == 0 then _ else _
            
            type Option a = Some a | None
            
            f3 = case _ of
              [] -> 0
              [_ :: xs] -> sum 1 (f3 xs)
            
            f4 = case _, _ of
              Some x, Some y -> sum x y
              Some x, None -> x
              None, Some y -> y
              None, None -> -1
        """.module()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Boolean -> Int"
        ds["f2"]?.type?.simpleName() shouldBe "forall t1. Int -> t1 -> t1 -> t1"
        ds["f3"]?.type?.simpleName() shouldBe "forall t1. Vector t1 -> Int"
        ds["f4"]?.type?.simpleName() shouldBe "Option Int -> Option Int -> Int"
        ds.forEach { (t, u) -> 
            println("$t: ${u.type.simpleName()}")
        }
    }
})
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
import novah.frontend.TestUtil.simpleName

class ForeignSpec : StringSpec({
    
    "test interop" {
        val code = """
            module test
            
            foreign import java.lang.Math
            
            foo = Math#-"PI"
            
            bar = String#valueOf(2)
            
            baz =
              let str = "the lazy fox"
              str#indexOf("z")
            
            zoo = String#new("str")
            
            boo x = Math#addExact(x, 1L)
            
            foo2 =
              let rec = { name: "Name" }
              rec.name#repeat(3)#split("a")
        """.trimIndent()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["foo"]?.type?.simpleName() shouldBe "Float64"
        ds["bar"]?.type?.simpleName() shouldBe "String"
        ds["baz"]?.type?.simpleName() shouldBe "Int32"
        ds["zoo"]?.type?.simpleName() shouldBe "String"
        ds["boo"]?.type?.simpleName() shouldBe "Int64 -> Int64"
        ds["foo2"]?.type?.simpleName() shouldBe "Array String"
    }
})
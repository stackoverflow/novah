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
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class CollectionsSpec : StringSpec({

    "basic lists" {
        val code = """
            list = []
            
            list2 = [1, 2, 3, 4, 5]
            
            list3 = ["a", "b", "c"]
            
            tlist : List Int64
            tlist = []
            
            tlist2 : List String
            tlist2 = ["a", "b", "c"]
            
            tlist3 : List Byte
            tlist3 = [1, 2, 3, 4]
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["list"]?.type?.simpleName() shouldBe "List t1"
        ds["list2"]?.type?.simpleName() shouldBe "List Int32"
        ds["list3"]?.type?.simpleName() shouldBe "List String"
        ds["tlist"]?.type?.simpleName() shouldBe "List Int64"
        ds["tlist2"]?.type?.simpleName() shouldBe "List String"
        ds["tlist3"]?.type?.simpleName() shouldBe "List Byte"
    }

    "basic sets" {
        val code = """
            set = #{}
            
            set2 = #{1, 2, 3, 4, 5}
            
            set3 = #{"a", "b", "c"}
            
            tset : Set Int64
            tset = #{}
            
            tset2 : Set String
            tset2 = #{"a", "b", "c"}
            
            tset3 : Set Int16
            tset3 = #{1, 2, 3}
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["set"]?.type?.simpleName() shouldBe "Set t1"
        ds["set2"]?.type?.simpleName() shouldBe "Set Int32"
        ds["set3"]?.type?.simpleName() shouldBe "Set String"
        ds["tset"]?.type?.simpleName() shouldBe "Set Int64"
        ds["tset2"]?.type?.simpleName() shouldBe "Set String"
        ds["tset3"]?.type?.simpleName() shouldBe "Set Int16"
    }
})
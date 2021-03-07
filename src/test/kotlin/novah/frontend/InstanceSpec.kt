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

class InstanceSpec : StringSpec({

    "constrained types" {
        val code = """
            typealias Show a =
              { show : a -> String }
            
            show : forall a. {{ Show a }} -> a -> String
            show {{s}} x = s.show x
            
            instance
            //showInt : Show Int
            showInt = { show: (\x -> toString x) : Int -> String }
            
            instance
            //showBool : Show Boolean
            showBool = { show: (\x -> toString x) : Boolean -> String }
            
            type Option a = Some a | None
            
            showOptionImpl : forall a. {{ Show a }} -> Option a -> String
            showOptionImpl {{s}} o = case o of
              Some x -> s.show x
              None -> "None"
            
            showOption : forall a. {{ Show a }} -> Show (Option a)
            showOption {{s}} = { show: showOptionImpl {{s}} }
            
            print : Option Int -> String
            print o = do
              let instance showOptInt = showOption {{showInt}}
              println (show o)
              show 3
              show true
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["show"]?.type?.simpleName() shouldBe "forall t1. {{ { show : t1 -> String } }} -> t1 -> String"
        ds["showInt"]?.type?.simpleName() shouldBe "{ show : Int -> String }"
        ds["showBool"]?.type?.simpleName() shouldBe "{ show : Boolean -> String }"
        ds["showOption"]?.type?.simpleName() shouldBe "forall t1. {{ { show : t1 -> String } }} -> { show : Option t1 -> String }"
    }
})
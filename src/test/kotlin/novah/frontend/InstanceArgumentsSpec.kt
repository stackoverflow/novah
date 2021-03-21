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

class InstanceArgumentsSpec : StringSpec({

    "constrained types" {
        val code = """
            opaque type Show a = { show : a -> String }
            
            show : forall a. {{ Show a }} -> a -> String
            show {{s}} x = case s of Show ss -> ss.show x
            
            instance
            //showInt : Show Int
            showInt = Show { show: (\x -> toString x) : Int -> String }
            
            instance
            //showBool : Show Boolean
            showBool = Show { show: (\x -> toString x) : Boolean -> String }
            
            type Option a = Some a | None
            
            showOptionImpl : forall a. {{ Show a }} -> Option a -> String
            showOptionImpl {{s}} o = case o of
              Some x -> show x
              None -> "None"
            
            showOption : forall a. {{ Show a }} -> Show (Option a)
            showOption {{s}} = Show { show: showOptionImpl {{s}} }
            
            print : Option Int -> String
            print o = do
              let instance showOptInt = showOption {{showInt}}
              println (show o)
              show 3
              show true
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["show"]?.type?.simpleName() shouldBe "forall t1. {{ Show t1 }} -> t1 -> String"
        ds["showInt"]?.type?.simpleName() shouldBe "Show Int"
        ds["showBool"]?.type?.simpleName() shouldBe "Show Boolean"
        ds["showOption"]?.type?.simpleName() shouldBe "forall t1. {{ Show t1 }} -> Show (Option t1)"
    }
    
    "recursive search" {
        val code = """
            opaque type Show a = { show : a -> String }
            
            show : forall a. {{ Show a }} -> a -> String
            show {{s}} x = case s of Show m -> m.show x
            
            instance
            showInt : Show Int
            showInt = Show { show: (\x -> toString x) : Int -> String }
            
            instance
            showBool : Show Boolean
            showBool = Show { show: (\x -> toString x) : Boolean -> String }
            
            type Option a = Some a | None
            
            showOptionImpl : forall a. {{ Show a }} -> Option a -> String
            showOptionImpl {{s}} o = case o of
              Some x -> show x
              None -> "None"
            
            instance
            showOption : forall a. {{ Show a }} -> Show (Option a)
            showOption {{s}} = Show { show: showOptionImpl {{s}} }
            
            type Result a b = Ok a | Err b
            
            showResultImpl : forall a b. {{ Show a }} -> {{ Show b }} -> Result a b -> String
            showResultImpl {{a}} {{b}} r = case r of
              Ok o -> show o
              Err e -> show e
            
            instance
            showResult : forall a b. {{ Show a }} -> {{ Show b }} -> Show (Result a b)
            showResult {{a}} {{b}} = Show { show: showResultImpl {{a}} {{b}} }
            
            main () = show (Some None)
            
            main2 () = show (Some (Ok true))
        """.module()

        TestUtil.compileCode(code).env.decls
    }
})
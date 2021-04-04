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
            opaque type View a = { view : a -> String }
            
            view : {{ View a }} -> a -> String
            view {{View s}} x = s.view x
            
            instance
            viewInt : View Int
            viewInt = View { view: \x -> toString x }
            
            instance
            viewBool : View Boolean
            viewBool = View { view: \x -> toString x }
            
            viewOptionImpl : {{ View a }} -> Option a -> String
            viewOptionImpl {{s}} o = case o of
              Some x -> view x
              None -> "None"
            
            viewOption : {{ View a }} -> View (Option a)
            viewOption {{s}} = View { view: viewOptionImpl {{s}} }
            
            printx : Option Int -> String
            printx o = do
              let instance viewOptInt = viewOption {{viewInt}}
              println (view o)
              view 3
              view true
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["view"]?.type?.simpleName() shouldBe "{{ View t1 }} -> t1 -> String"
        ds["viewInt"]?.type?.simpleName() shouldBe "View Int32"
        ds["viewBool"]?.type?.simpleName() shouldBe "View Boolean"
        ds["viewOption"]?.type?.simpleName() shouldBe "{{ View t1 }} -> View (Option t1)"
    }
    
    "recursive search" {
        val code = """
            import novah.vector as V
            
            opaque type View a = { view : a -> String }
            
            view : {{ View a }} -> a -> String
            view {{View s}} x = s.view x
            
            instance
            viewInt : View Int
            viewInt = View { view: \x -> toString x }
            
            instance
            viewBool : View Boolean
            viewBool = View { view: \x -> toString x }
            
            instance
            viewString : View String
            viewString = View { view: \x -> x }
            
            viewOptionImpl : {{ View a }} -> Option a -> String
            viewOptionImpl {{s}} o = case o of
              Some x -> view x
              None -> "None"
            
            instance
            viewOption : {{ View a }} -> View (Option a)
            viewOption {{s}} = View { view: viewOptionImpl {{s}} }
            
            viewResultImpl : {{ View a }} -> {{ View b }} -> Result a b -> String
            viewResultImpl {{a}} {{b}} r = case r of
              Ok o -> view o
              Err e -> view e
            
            instance
            viewResult : {{ View a }} -> {{ View b }} -> View (Result a b)
            viewResult {{a}} {{b}} = View { view: viewResultImpl {{a}} {{b}} }
            
            main () = view (Some (None : Option String))
            
            main2 () =
              let xx : Option (Result Boolean String)
                  xx = Some (Ok true)
              in view xx
            
            main3 () = V.map view [3, 4, 5]
            
            printx : {{ View a }} -> Option a -> String
            printx {{s}} o = view o
        """.module()

        TestUtil.compileCode(code).env.decls
    }
})
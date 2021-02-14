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
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class PatternMatchingSpec : StringSpec({

    "pattern matching typechecks" {
        val code = """
            f x = case x of
              0 -> "zero"
              1 -> "one"
              v -> toString v
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type

        ty.simpleName() shouldBe "Int -> String"
    }

    "pattern matching with polymorphic types typechecks" {
        val code = """
            id x = x
            
            f x = case id x of
              0 -> "zero"
              1 -> "one"
              v -> toString v
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty.simpleName() shouldBe "Int -> String"
    }

    "pattern matching constructor typechecks" {
        val code = """
            type Maybe a = Just a | Nothing
            
            f : Maybe Int -> String
            f x = case x of
              Just v -> toString v
              Nothing -> "nope"
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty.simpleName() shouldBe "Maybe Int -> String"
    }

    "pattern matching literals succeed" {
        val code = """
            f x = case x of
              true -> "a"
              false -> "b"
        """.module()

        shouldNotThrowAny {
            TestUtil.compileAndOptimizeCode(code)
        }
    }
    
    "pattern match lambdas - unit pattern" {
        val code = """
            f () = 12
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty.simpleName() shouldBe "Unit -> Int"
    }

    "pattern match let lambdas - unit pattern" {
        val code = """
            f () = let fun () = 12
                   in fun ()
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty.simpleName() shouldBe "Unit -> Int"
    }

    "pattern match lambdas - ignore pattern" {
        val code = """
            const _ = 10
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["const"]!!.type
        ty.simpleName() shouldBe "forall t1. t1 -> Int"
    }
    
    "failing test" {
        val code = """
            type Maybe a = Some a | None
            
            main _ = Some None
        """.module()

        TestUtil.compileAndOptimizeCode(code)
    }
})
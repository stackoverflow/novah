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
            
            f : Maybe Int -> Int
            f x = case x of
              Just v -> v
              Nothing -> 0
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type
        ty.simpleName() shouldBe "Maybe Int -> Int"
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
    
    "pattern match unit" {
        val code = """
            fun u =
              case u of
                () -> 10
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["fun"]!!.type
        ty.simpleName() shouldBe "Unit -> Int"
    }
    
    "pattern match records" {
        val code = """
            foreign import novah.Core:sum(Int, Int)
            
            rec = { x: 1, y: 0 }
            
            f1 = case rec of
              { x } -> sum x 1
              { y } -> sum y 2
            
            f2 r = case r of
              { x, z: true } -> sum x 1
              { x } -> x
              { y } -> sum y 2
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Int"
        ds["f2"]?.type?.simpleName() shouldBe "forall t1. { x : Int, z : Boolean, y : Int | t1 } -> Int"
    }
    
    "pattern match vectors" {
        val code = """
            foreign import novah.Core:sum(Int, Int)
            
            vec = [1, 2, 3, 4]
            
            f1 = case vec of
              [] -> 0
              [x] -> sum x 1
              [x, y] -> sum x y
              _ -> -1
            
            f2 v =
              case v of
                [] -> 0
                [x :: _] -> x
            
            len v =
              case v of
                [] -> 0
                [_ :: xs] -> sum 1 (len xs)
        """.module()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Int"
        ds["f2"]?.type?.simpleName() shouldBe "Vector Int -> Int"
        ds["len"]?.type?.simpleName() shouldBe "forall t1. Vector t1 -> Int"
    }
    
    "pattern match named" {
        val code = """
            f1 = case 3 of
              1 -> 1
              3 as x -> x
              _ -> -1
        """.module()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Int"
    }
    
    "pattern match guards" {
        val code = """
            f1 = case 3 of
              x if x == 1 -> 1
              x if x == 3 -> x
              _ -> -1
        """.module()
        
        val ds = TestUtil.compileCode(code).env.decls
        ds["f1"]?.type?.simpleName() shouldBe "Int"
    }
    
    "pattern match type test" {
        val code = """
            fun () =
              case ['a', 'b'] of
                :? Int -> 0
                :? Vector as v -> 1
                _ -> -1
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["fun"]?.type?.simpleName() shouldBe "Unit -> Int"
    }
})
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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName
import novah.frontend.typechecker.*
import novah.main.CompilationError

class TypecheckerSpec : StringSpec({

    fun exprX(expr: String) = "x = $expr".module()

    fun inferX(expr: String): Type {
        return TestUtil.compileCode(exprX(expr)).env.decls["x"]!!.type
    }

    fun inferFX(expr: String): Type {
        return TestUtil.compileCode("f _ = $expr".module()).env.decls["f"]!!.type
    }

    "typecheck primitive expressions" {
        val tint = inferX("34")
        val tlong = inferX("34L")
        val tfloat = inferX("34.0F")
        val tdouble = inferX("34.0")
        val tstring = inferX("\"asd\"")
        val tchar = inferX("'A'")
        val tboolean = inferX("false")

        tint shouldBe tInt32
        tlong shouldBe tInt64
        tfloat shouldBe tFloat32
        tdouble shouldBe tFloat64
        tstring shouldBe tString
        tchar shouldBe tChar
        tboolean shouldBe tBoolean
    }

    "typecheck number conversions" {
        val code = """
            i = 12
            
            b : Byte
            b = 12
            
            b2 = 12 : Byte
            
            s : Int16
            s = 12
            
            l = 9999999999
            
            l2 = 12L
            
            d = 12.0
            
            f = 12.0F
            
            bi = 0b1101011
            
            bi2 = 0b1101011L
            
            bi3 : Byte
            bi3 = 0b1101011
            
            hex = 0xFF
            
            hex2 = 0xFFL
            
            hex3 : Int16
            hex3 = 0xFF
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["i"]?.type shouldBe tInt32
        tys["b"]?.type shouldBe tByte
        tys["b2"]?.type shouldBe tByte
        tys["s"]?.type shouldBe tInt16
        tys["l"]?.type shouldBe tInt64
        tys["l2"]?.type shouldBe tInt64
        tys["d"]?.type shouldBe tFloat64
        tys["f"]?.type shouldBe tFloat32
        tys["bi"]?.type shouldBe tInt32
        tys["bi2"]?.type shouldBe tInt64
        tys["bi3"]?.type shouldBe tByte
        tys["hex"]?.type shouldBe tInt32
        tys["hex2"]?.type shouldBe tInt64
        tys["hex3"]?.type shouldBe tInt16
    }

    "typecheck if" {
        val ty = inferFX("if false then 0 else 1")

        ty.simpleName() shouldBe "t1 -> Int32"
    }

    "typecheck subsumed if" {
        val code = """
            id x = x
            
            f _ = if true then 10 else id 0
            f2 a = if true then 10 else id a
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["f"]?.type?.simpleName() shouldBe "t1 -> Int32"
        tys["f2"]?.type?.simpleName() shouldBe "Int32 -> Int32"
    }

    "typecheck generics" {
        val ty = inferX("\\y -> y")

        ty.simpleName() shouldBe "t1 -> t1"

        val ty2 = inferFX("(\\y -> y) false")

        ty2.simpleName() shouldBe "t1 -> Boolean"
    }

    "typecheck pre-added context vars" {
        val ty = inferFX("toString 10")

        ty.simpleName() shouldBe "t1 -> String"
    }

    "typecheck let" {
        val code = """
            f () = let a = "bla"
                       y = a
                   in y
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]

        ty?.type?.simpleName() shouldBe "Unit -> String"
    }

    "typecheck polymorphic let bindings" {
        val code = """
            f _ = let identity a = a
                  in identity 10
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]

        ty?.type?.simpleName() shouldBe "t1 -> Int32"
    }

    "typecheck do statements" {
        val code = """
            f _ = do
              println "hello world"
              toString 10
              println (toString 100)
              true
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type

        ty.simpleName() shouldBe "t1 -> Boolean"
    }

    "typecheck do statements with let" {
        val code = """
            f () = do
              let v = 10
              let hundred = 100
              toString v
              println (toString hundred)
              let ret = true
              ret
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["f"]!!.type

        ty.simpleName() shouldBe "Unit -> Boolean"
    }

    "typecheck a recursive function" {
        // TODO: write a factorial when we have stdlib
        val code = """
            pseudofact x =
              if x == 1
              then x
              else pseudofact x
        """.module()

        val ty = TestUtil.compileCode(code).env.decls["pseudofact"]

        ty?.type?.simpleName() shouldBe "Int32 -> Int32"
    }

    "typecheck mutually recursive functions" {
        val code = """
            (>) : Int -> Int -> Boolean
            (>) _ _ = true
            
            (<=) : Int -> Int -> Boolean
            (<=) _ _ = true
            
            (+) : Int -> Int -> Int
            (+) x _ = x
            
            (-) : Int -> Int -> Int
            (-) x _ = x
            
            f1 : Int -> Int
            f1 x =
              if x > 0
              then f2 x
              else x - 1
            
            f2 : Int -> Int
            f2 x =
              if x <= 0
              then f1 x
              else x + 1
        """.module()

        shouldNotThrowAny {
            TestUtil.compileCode(code)
        }
    }

    "high ranked types don't compile" {
        val code = """
            type Tuple a b = Tuple a b
            
            //fun : (forall a. a -> a) -> Tuple Int String
            fun f = Tuple (f 1) (f "a")
        """.module()

        shouldThrow<CompilationError> {
            TestUtil.compileCode(code).env.decls["fun"]
        }
    }

    "typecheck recursive lets" {
        val code = """
            fun n s =
              let rec x y = if x == 1 then y else rec 1 y
              in rec n s
        """.module()

        val res = TestUtil.compileCode(code).env.decls

        res["fun"]?.type?.simpleName() shouldBe "Int32 -> t1 -> t1"
    }
})
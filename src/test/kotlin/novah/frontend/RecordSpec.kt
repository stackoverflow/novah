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

class RecordSpec : StringSpec({

    "basic records" {
        val code = """
            rec : { a : Int, b : Boolean, c : Int64 }
            rec = { a: 1, b: true, c: 3L }
            
            a = rec.a
            
            minus = { - a | rec }
            
            rem : { bar : Int32 | r } -> { | r }
            rem = { - bar | _ }
            
            plus = { d: (5 : Byte) | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds["rec"]?.type?.simpleName() shouldBe "{ a : Int32, b : Boolean, c : Int64 }"
        ds["a"]?.type?.simpleName() shouldBe "Int32"
        ds["minus"]?.type?.simpleName() shouldBe "{ b : Boolean, c : Int64 }"
        ds["rem"]?.type?.simpleName() shouldBe "{ bar : Int32 | t1 } -> { | t1 }"
        ds["plus"]?.type?.simpleName() shouldBe "{ d : Byte, a : Int32, b : Boolean, c : Int64 }"
    }

    "duplicated labels" {
        val code = """
            rec : { a : Int, a : Boolean, a : Int64 }
            rec = { a: 3, a: true, a: 3L }
            
            a = rec.a
            
            minus = { - a | rec }
            
            minusA = minus.a
            
            plus = { a: (5 : Byte) | rec }
            
            plusA = plus.a
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds["rec"]?.type?.simpleName() shouldBe "{ a : Int64, a : Boolean, a : Int32 }"
        ds["a"]?.type?.simpleName() shouldBe "Int64"
        ds["minus"]?.type?.simpleName() shouldBe "{ a : Boolean, a : Int32 }"
        ds["minusA"]?.type?.simpleName() shouldBe "Boolean"
        ds["plus"]?.type?.simpleName() shouldBe "{ a : Byte, a : Int64, a : Boolean, a : Int32 }"
        ds["plusA"]?.type?.simpleName() shouldBe "Byte"
    }

    "polymorphic labels" {
        val code = """
            //isXZero : forall r. { x : Int | r } -> Boolean
            isXZero p = p.x == 0
            
            //minus : forall a r. { x : a | r } -> { | r }
            minus rec = { - x | rec }
            
            //plus : forall a r. { | r } -> a -> { x : a | r } 
            plus x rec = { x: x | rec }
            
            ap1 () = minus { y: 2, x: true }
            
            ap2 () = plus "x" { y: 2, z: true }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["isXZero"]?.type?.simpleName() shouldBe "{ x : Int32 | t1 } -> Boolean"
        ds["minus"]?.type?.simpleName() shouldBe "{ x : t2 | t1 } -> { | t1 }"
        ds["plus"]?.type?.simpleName() shouldBe "t1 -> { | t2 } -> { x : t1 | t2 }"
        ds["ap1"]?.type?.simpleName() shouldBe "Unit -> { y : Int32 }"
        ds["ap2"]?.type?.simpleName() shouldBe "Unit -> { x : String, y : Int32, z : Boolean }"
    }

    "string and UTF-8 labels" {
        val code = """
            rec = { "Uppercase": 1, "some 'label'": true, "😃 emoticons": 3L }
            
            minus = { - "some 'label'", "😃 emoticons" | rec }
            
            plus = { "😑": (5 : Byte) | rec }
            
            sel = rec."😃 emoticons"
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["rec"]?.type?.simpleName() shouldBe
                """{ "Uppercase" : Int32, "some 'label'" : Boolean, "😃 emoticons" : Int64 }"""
        ds["minus"]?.type?.simpleName() shouldBe """{ "Uppercase" : Int32 }"""
        ds["plus"]?.type?.simpleName() shouldBe
                """{ "😑" : Byte, "Uppercase" : Int32, "some 'label'" : Boolean, "😃 emoticons" : Int64 }"""
        ds["sel"]?.type?.simpleName() shouldBe "Int64"
    }

    "polymorphic rows" {
        val code = """
            typealias Named r = [ name : String | r ]
            typealias Aged a r = [ age : Option a | r ]
            
            typealias User = { ssn : String | Named (Aged Int []) }
            
            user : User
            user = { ssn: "123", name: "Namer", age: Some 30 }
        """.module()

        val user = TestUtil.compileCode(code).env.decls["user"]

        user?.type?.simpleName() shouldBe "{ ssn : String, name : String, age : Option Int32 }"
    }

    "record set" {
        val code = """
            rec = { name: "Snuffles", age: 20 }
            
            nested = { address: { country: { number: 10 } } }
            
            update r name = { .name = name | r }
            
            update2 r code = { .address.country.number = code + 10 | r }
            
            update3 name age = { .name = name, .age = age | rec }
            
            x = update rec "Mr. Snuffles"
            y = update2 nested 25
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["update"]?.type?.simpleName() shouldBe "{ name : t2 | t1 } -> t2 -> { name : t2 | t1 }"
        ds["update2"]?.type?.simpleName() shouldBe
                "{ address : { country : { number : Int32 | t3 } | t2 } | t1 } -> Int32 -> { address : { country : { number : Int32 | t3 } | t2 } | t1 }"
        ds["update3"]?.type?.simpleName() shouldBe "String -> Int32 -> { name : String, age : Int32 }"
    }

    "record update" {
        val code = """
            rec = { name: "Snuffles", age: 20 }
            
            nested = { address: { country: { number: 10 } } }
            
            update r namer = { .name -> namer | r }
            
            update2 r = { .address.country.number -> _ + 10 | r }
            
            update3 namer plus = { .name -> namer, .age -> _ + plus | rec }
            
            x = update rec String.upperCase
            y = update2 nested
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["update"]?.type?.simpleName() shouldBe "{ name : t2 | t1 } -> (t2 -> t2) -> { name : t2 | t1 }"
        ds["update2"]?.type?.simpleName() shouldBe
                "{ address : { country : { number : Int32 | t3 } | t2 } | t1 } -> { address : { country : { number : Int32 | t3 } | t2 } | t1 }"
        ds["update3"]?.type?.simpleName() shouldBe "(String -> String) -> Int32 -> { name : String, age : Int32 }"
    }

    "record merge" {
        val code = """
            rec = { name: "Bill", age: 10 }
            
            rec2 = { lastName: "Snuffles", weight: 3 }
            
            merge = { + rec, rec2 }
            
            merge2 : Int -> { | r } -> { age : Int | r }
            merge2 age r = { + r, { age: age } }
            
            merged2 = merge2 12 { name: "Snuffles", weight: 3 }
            
            merge3 =
              let r1 = { a: 1, a: 4.0, b: 1 }
              let r2 = { a: 'a', a: false, c: true }
              { + r1, r2 }
            
            build : { price : Int } -> { taxes: String } -> { price : Int, taxes : String }
            build li vats = { + li, vats }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["merge"]?.type?.simpleName() shouldBe "{ age : Int32, lastName : String, name : String, weight : Int32 }"
        ds["merged2"]?.type?.simpleName() shouldBe "{ age : Int32, name : String, weight : Int32 }"
        ds["merge3"]?.type?.simpleName() shouldBe
                "{ a : Boolean, a : Char, a : Float64, a : Int32, b : Int32, c : Boolean }"
        ds["build"]?.type?.simpleName() shouldBe
                "{ price : Int32 } -> { taxes : String } -> { price : Int32, taxes : String }"
    }

    "1st class modules" {
        val code = """
            typealias Prelude a b =
              { id : a -> a
              , const : a -> b -> a
              }
            
            id : a -> a
            id x = x
            
            prelude : Prelude a b
            prelude =
              { id: id
              , const: \x _ -> x
              }
        """.module()

        val pre = TestUtil.compileCode(code).env.decls["prelude"]?.type
        pre?.simpleName() shouldBe "{ const : t1 -> t2 -> t1, id : t1 -> t1 }"
    }

    "proper type checking for records" {
        val code = """
            
            select : { fun : String -> String } -> String
            select m = m.fun ""
            
            id x = x
            
            rec : { fun : String -> String }
            rec = { fun: \x -> x }
            
            rec2 : Char -> { fun : Char }
            rec2 c = { fun: id c }
            
            call = select { fun: id }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["select"]?.type?.simpleName() shouldBe "{ fun : String -> String } -> String"
        ds["rec"]?.type?.simpleName() shouldBe "{ fun : String -> String }"
        ds["rec2"]?.type?.simpleName() shouldBe "Char -> { fun : Char }"
        ds["call"]?.type?.simpleName() shouldBe "String"
    }
})
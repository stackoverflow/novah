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
            rec : { a : Int, b : Boolean, c : Long }
            rec = { a: 1, b: true, c: 3L }
            
            a = rec.a
            
            minus = { - a | rec }
            
            plus = { d: (5 : Byte) | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds["rec"]?.type?.simpleName() shouldBe "{ a : Int, b : Boolean, c : Long }"
        ds["a"]?.type?.simpleName() shouldBe "Int"
        ds["minus"]?.type?.simpleName() shouldBe "{ b : Boolean, c : Long }"
        ds["plus"]?.type?.simpleName() shouldBe "{ d : Byte, a : Int, b : Boolean, c : Long }"
    }

    "duplicated labels" {
        val code = """
            rec : { a : Int, a : Boolean, a : Long }
            rec = { a: 3, a: true, a: 3L }
            
            a = rec.a
            
            minus = { - a | rec }
            
            plus = { a: (5 : Byte) | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds["rec"]?.type?.simpleName() shouldBe "{ a : Long, a : Boolean, a : Int }"
        ds["a"]?.type?.simpleName() shouldBe "Long"
        ds["minus"]?.type?.simpleName() shouldBe "{ a : Boolean, a : Int }"
        ds["plus"]?.type?.simpleName() shouldBe "{ a : Byte, a : Long, a : Boolean, a : Int }"
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
        ds["isXZero"]?.type?.simpleName() shouldBe "forall t1. { x : Int | t1 } -> Boolean"
        ds["minus"]?.type?.simpleName() shouldBe "forall t1 t2. { x : t1 | t2 } -> { | t2 }"
        ds["plus"]?.type?.simpleName() shouldBe "forall t1 t2. t1 -> { | t2 } -> { x : t1 | t2 }"
        ds["ap1"]?.type?.simpleName() shouldBe "Unit -> { y : Int }"
        ds["ap2"]?.type?.simpleName() shouldBe "Unit -> { x : String, y : Int, z : Boolean }"
    }

    "string and UTF-8 labels" {
        val code = """
            rec = { "Uppercase": 1, "some 'label'": true, "😃 emoticons": 3L }
            
            minus = { - "some 'label'" | rec }
            
            plus = { "😑": (5 : Byte) | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["rec"]?.type?.simpleName() shouldBe """{ "Uppercase" : Int, "some 'label'" : Boolean, "😃 emoticons" : Long }"""
        ds["minus"]?.type?.simpleName() shouldBe """{ "Uppercase" : Int, "😃 emoticons" : Long }"""
        ds["plus"]?.type?.simpleName() shouldBe """{ "😑" : Byte, "Uppercase" : Int, "some 'label'" : Boolean, "😃 emoticons" : Long }"""
    }

    "polymorphic rows" {
        val code = """
            type Option a = Some a | None
            typealias Named r = [ name : String | r ]
            typealias Aged a r = [ age : Option a | r ]
            
            typealias User = { ssn : String | Named (Aged Int []) }
            
            user : User
            user = { ssn: "123", name: "Namer", age: Some 30 }
        """.module()

        val user = TestUtil.compileCode(code).env.decls["user"]

        user?.type?.simpleName() shouldBe "{ ssn : String, name : String, age : Option Int }"
    }

    "record update" {
        // this is a hack, would be better to have type level strings or direct syntax
        // so we could generically update a record
        val code = """
            same : forall a. a -> a -> a
            same x y = y
            
            update newName named = { name: same named.name newName | { - name | named } }
        """.module()

        val user = TestUtil.compileCode(code).env.decls["update"]
        user?.type?.simpleName() shouldBe "forall t1 t2. t1 -> { name : t1 | t2 } -> { name : t1 | t2 }"
    }
})
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

class TypecheckerADTSpec : StringSpec({

    "typecheck no parameter ADTs" {
        val code = """
            type Day = Week | Weekend
            
            x : Int -> Day
            x _ = Week
            
            y = Weekend
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int32 -> Day"
        tys["y"]?.type?.simpleName() shouldBe "Day"
    }
    
    "test rigid type variables" {
        val code = """
            type Listt a = Empty | Conj a (Listt a)
            
            cons x l = Conj x l
            
            id x = x
            
            single x = cons x Empty
            
            //ids : Listt (forall a. a -> a)
            ids () = single id
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["ids"]?.type?.simpleName() shouldBe "Unit -> Listt (t1 -> t1)"
    }

    "typecheck one parameter ADTs" {
        val code = """
            x : Int -> Option String
            x _ = None
            
            y : a -> Option a
            y _ = None
            
            w : Int -> Option Int
            w _ = Some 5
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int32 -> Option String"
        tys["y"]?.type?.simpleName() shouldBe "t1 -> Option t1"
        tys["w"]?.type?.simpleName() shouldBe "Int32 -> Option Int32"
    }

    "typecheck 2 parameter ADTs" {
        val code = """
            type Res k e
              = Okk k
              | Errr e
            
            x : Int -> Res Int String
            x _ = Okk 1
            
            //y : Int -> Res Int String
            y _ = Errr "oops"
        """.module()

        val tys = TestUtil.compileCode(code).env.decls
        tys["x"]?.type?.simpleName() shouldBe "Int32 -> Res Int32 String"
        val y = tys["y"]!!.type
        y.simpleName() shouldBe "t1 -> Res t2 String"
    }

    "typecheck recursive ADT" {
        val code = """
            type LinkedList a = Empty | Conj a (LinkedList a)
            
            x : Int -> LinkedList String
            x _ = Empty : LinkedList String
            
            y = Conj 1 (Conj 2 (Conj 3 Empty))
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int32 -> LinkedList String"
        tys["y"]?.type?.simpleName() shouldBe "LinkedList Int32"
    }

    "typecheck complex type" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor b
            
            str : a -> String
            str _ = "1"
            
            id a = a
            
            x : Unit -> Comp a (b -> b)
            x () = Cfor id
            
            y _ = Cfun str
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        val x = tys["x"]!!.type
        x.simpleName() shouldBe "Unit -> Comp t1 (t2 -> t2)"
        val y = tys["y"]!!.type
        y.simpleName() shouldBe "t1 -> Comp t2 String"
    }

    "test type aliases" {
        val code = """
            type Toople a b = Toople a b
            
            typealias Ta = Int
            
            typealias Ty a = Toople Ta a
            
            f : Unit -> Ty Ta
            f () = Toople 1 2
        """.module()

        val f = TestUtil.compileCode(code).env.decls["f"]?.type

        f?.simpleName() shouldBe "Unit -> Toople Int32 Int32"
    }

    "test type aliases with type applications" {
        val code = """
            type May a = May a

            typealias Pri a = May a

            typealias Foo b = Pri (May b)
            
            f : Unit -> Foo Int
            f () = May (May 0)
        """.module()

        val f = TestUtil.compileCode(code).env.decls["f"]?.type

        f?.simpleName() shouldBe "Unit -> May (May Int32)"
    }
})
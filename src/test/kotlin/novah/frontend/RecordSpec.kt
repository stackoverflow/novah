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
            
            // plus : forall a r. { | r } -> a -> { x : a | r } 
            plus rec i = { x: i | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["isXZero"]?.type?.simpleName() shouldBe "forall t1. { x : Int | t1 } -> Boolean"
        ds["minus"]?.type?.simpleName() shouldBe "forall t1 t2. { x : t1 | t2 } -> { | t2 }"
        ds["plus"]?.type?.simpleName() shouldBe "forall t1 t2. { | t1 } -> t2 -> { x : t2 | t1 }"
    }
})
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
            x a = Week
            
            y a = Weekend
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int -> Day"
        tys["y"]?.type?.simpleName() shouldBe "forall t1. t1 -> Day"
    }
    
    "test rigid type variables" {
        val code = """
            type List a = Nil | Cons a (List a)
            
            cons x l = Cons x l
            
            id x = x
            
            single x = cons x Nil
            
            //ids : List (forall a. a -> a)
            ids () = single id
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["ids"]?.type?.simpleName() shouldBe "forall t1. Unit -> List (t1 -> t1)"
    }

    "test rigid type variables (with annotations)" {
        val code = """
            type List a = Nil | Cons a (List a)
            
            cons x l = Cons x l
            
            id x = x
            
            single x = cons x Nil
            
            ids : Unit -> List (forall a. a -> a)
            ids () = single id
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["ids"]?.type?.simpleName() shouldBe "Unit -> List (forall t1. t1 -> t1)"
    }

    "typecheck one parameter ADTs" {
        val code = """
            type Maybe a
              = Some a
              | None
            
            x : Int -> Maybe String
            x a = None
            
            y : forall a. a -> Maybe a
            y a = None
            
            w : Int -> Maybe Int
            w a = Some 5
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int -> Maybe String"
        tys["y"]?.type?.simpleName() shouldBe "forall t1. t1 -> Maybe t1"
        tys["w"]?.type?.simpleName() shouldBe "Int -> Maybe Int"
    }

    "typecheck 2 parameter ADTs" {
        val code = """
            type Result k e
              = Ok k
              | Err e
            
            x : Int -> Result Int String
            x a = Ok 1
            
            //y : Int -> Result Int String
            y a = Err "oops"
        """.module()

        val tys = TestUtil.compileCode(code).env.decls
        tys["x"]?.type?.simpleName() shouldBe "Int -> Result Int String"
        val y = tys["y"]!!.type
        y.simpleName() shouldBe "forall t1 t2. t1 -> Result t2 String"
    }

    "typecheck recursive ADT" {
        val code = """
            type List a = Nil | Cons a (List a)
            
            x : Int -> List String
            x a = Nil : List String
            
            y a = Cons 1 (Cons 2 (Cons 3 Nil))
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        tys["x"]?.type?.simpleName() shouldBe "Int -> List String"
        tys["y"]?.type?.simpleName() shouldBe "forall t1. t1 -> List Int"
    }

    "typecheck complex type" {
        val code = """
            type Comp a b = Cfun (a -> b) | Cfor (forall c. c -> c)
            
            str : forall a. a -> String
            str _ = "1"
            
            id a = a
            
            x : forall a. Unit -> Comp a (forall b. b -> b)
            x () = Cfor id
            
            y a = Cfun str
        """.module()

        val tys = TestUtil.compileCode(code).env.decls

        val x = tys["x"]!!.type
        x.simpleName() shouldBe "forall t1. Unit -> Comp t1 (forall t2. t2 -> t2)"
        val y = tys["y"]!!.type
        y.simpleName() shouldBe "forall t1 t2. t1 -> Comp t2 String"
    }
})
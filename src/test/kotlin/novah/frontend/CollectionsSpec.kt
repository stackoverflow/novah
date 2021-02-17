package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class CollectionsSpec : StringSpec({

    "basic vectors" {
        val code = """
            vec = []
            
            vec2 = [1, 2, 3, 4, 5]
            
            vec3 = ["a", "b", "c"]
            
            tvec : Vector Long
            tvec = []
            
            tvec2 : Vector String
            tvec2 = ["a", "b", "c"]
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["vec"]?.type?.simpleName() shouldBe "forall t1. Vector t1"
        ds["vec2"]?.type?.simpleName() shouldBe "Vector Int"
        ds["vec3"]?.type?.simpleName() shouldBe "Vector String"
        ds["tvec"]?.type?.simpleName() shouldBe "Vector Long"
        ds["tvec2"]?.type?.simpleName() shouldBe "Vector String"
    }

    "basic sets" {
        val code = """
            set = #{}
            
            set2 = #{1, 2, 3, 4, 5}
            
            set3 = #{"a", "b", "c"}
            
            tset : Set Long
            tset = #{}
            
            tset2 : Set String
            tset2 = #{"a", "b", "c"}
        """.module()

        val ds = TestUtil.compileCode(code).env.decls
        ds["set"]?.type?.simpleName() shouldBe "forall t1. Set t1"
        ds["set2"]?.type?.simpleName() shouldBe "Set Int"
        ds["set3"]?.type?.simpleName() shouldBe "Set String"
        ds["tset"]?.type?.simpleName() shouldBe "Set Long"
        ds["tset2"]?.type?.simpleName() shouldBe "Set String"
    }
})
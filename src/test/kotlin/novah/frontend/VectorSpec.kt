package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class VectorSpec : StringSpec({

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
})
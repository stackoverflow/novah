package novah.frontend

import io.kotest.core.spec.style.StringSpec
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class RecordSpec : StringSpec({

    "basic records" {
        val code = """
            
            id x = x
            
            hkrec : { foo : forall a. a }
            hkrec = { foo: id }
            
            rec : { a : Int, b : Int, a : String }
            rec = { a: 1, b: 2, a: "foo" }
            
            a = rec.a
            
            minus = { - a | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds.forEach { (k, d) ->
            println(k + ": " + d.type.simpleName())
        }
    }
})
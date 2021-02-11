package novah.frontend

import io.kotest.core.spec.style.StringSpec
import novah.frontend.TestUtil.module
import novah.frontend.TestUtil.simpleName

class RecordSpec : StringSpec({

    "basic records" {
        val code = """
            
            id x = x
            
            hkrec : Unit -> { foo : forall a. a -> a }
            hkrec () = { foo: id }
            
            rec : { a : Int, b : Int, a: Boolean }
            rec = { a: 1, a: true, b: 2 }
            
            a = rec.a
            
            minus = { - a | rec }
            
            plus = { b: 5 | rec }
        """.module()

        val ds = TestUtil.compileCode(code).env.decls

        ds.forEach { (k, d) ->
            println(k + ": " + d.type.simpleName())
        }
    }
})
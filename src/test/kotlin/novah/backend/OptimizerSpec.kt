package novah.backend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.frontend.TestUtil
import novah.frontend.TestUtil.module

class OptimizerSpec : StringSpec({

    "fully applied constructors are optimized" {
        val code = """
            type Multi a b = M a b Int String | Not
            
            f () = M 'a' 2.3F 5 "d"
        """.module()

        val res = TestUtil.compileAndOptimizeCode(code)
        val f = res.decls.find { it is Decl.ValDecl && it.name == "f" }!! as Decl.ValDecl
        val app = (f.exp as Expr.Lambda).body
        app.shouldBeInstanceOf<Expr.CtorApp>()
        app.args.size shouldBe 4

        val c = app.args[0]
        c.shouldBeInstanceOf<Expr.CharE>()
        c.v shouldBe 'a'

        val fl = app.args[1]
        fl.shouldBeInstanceOf<Expr.FloatE>()
        fl.v shouldBe 2.3f

        val i = app.args[2]
        i.shouldBeInstanceOf<Expr.IntE>()
        i.v shouldBe 5

        val s = app.args[3]
        s.shouldBeInstanceOf<Expr.StringE>()
        s.v shouldBe "d"
    }
})
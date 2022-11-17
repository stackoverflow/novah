package novah.ide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import novah.frontend.TestUtil
import novah.ide.features.HoverFeature

class HoverSpec : StringSpec({

    "hover over statements test" {
        val code = """
            module test
            
            import novah.computation

            input : List Int64
            input = [1L]
            
            simulate : Int -> Int64
            simulate days =
              let run _ _ = [1L]
              println "asd"
              let data = do.list
                for x in [0L .. 9L] do
                  yield List.count (_ == x) input |> int64
            
              run days data |> List.sum
        """.trimIndent()

        val env = TestUtil.compileCodeAllModules(code)
        val mod = env["test"]!!

        val hover = HoverFeature(NovahServer(true))
        val ctx = hover.findContext(12, 9, mod.ast, env)
        ctx.shouldBeInstanceOf<HoverFeature.LetCtx>()
    }
})
package novah.main

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import novah.frontend.TestUtil
import novah.frontend.TestUtil.cleanAndGetOutDir

class CompilationSpec : StringSpec({

    "fully compile all files in a directory structure" {
        val compiler = TestUtil.compilerFor("fullCompilation")
        compiler.run(cleanAndGetOutDir())
    }

    "empty modules succeed" {
        val compiler = TestUtil.compilerFor("fullCompilation")

        shouldNotThrowAny {
            compiler.run(cleanAndGetOutDir())
        }
    }
})
package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec

class ForeignSpec : StringSpec({

    "test" {
        val code = """
            module java

            foreign import type java.util.ArrayList
            foreign import type java.io.File
            foreign import ArrayList.add(String)
            foreign import new ArrayList() as newArrayList
            foreign import new File(String) as newFile
            foreign import Int:parseInt(String)
            foreign import get Int:MIN_VALUE as minValue
            foreign import String.compareTo(Object)
            //foreign import set File:separator as setSeparator
            
            f :: String -> File
            f x = newFile x
            
            main args = do
              println "running novah"
              let arr = newArrayList ()
              in do
                add arr "asd"
                add arr "oops"
                println (toString arr)
            
            h x = parseInt x
        """.trimIndent()

        shouldNotThrowAny {
            TestUtil.compileCode(code)
        }
    }
})
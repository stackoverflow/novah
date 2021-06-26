/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec

class ForeignSpec : StringSpec({

    "test foreign imports" {
        val code = """
            module test

            foreign import type java.util.ArrayList
            foreign import type java.io.File
            foreign import ArrayList.add(String)
            foreign import new ArrayList() as newArrayList
            foreign import new File(String) as newFile
            foreign import Int:parseInt(String)
            //foreign import set File:separator as setSeparator
            
            f : String -> File
            f x = newFile x
            
            main _ =
              println "running novah"
              let arr = newArrayList ()
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
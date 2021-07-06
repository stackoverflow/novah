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
package novah.main

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import novah.frontend.TestUtil
import novah.frontend.TestUtil.cleanAndGetOutDir

class CompilationSpec : StringSpec({

    "empty modules succeed" {
        val compiler = TestUtil.compilerFor("fullCompilation/empty")

        shouldNotThrowAny {
            compiler.run(cleanAndGetOutDir())
        }
    }

    "test" {
        val compiler = TestUtil.compilerFor("test")
        val warns = compiler.run(cleanAndGetOutDir())
        warns.forEach { println(it.formatToConsole()) }
    }
})
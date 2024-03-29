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

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.lexResource
import novah.frontend.TestUtil.lexString
import io.kotest.matchers.types.shouldBeInstanceOf

class LexerSpec : StringSpec({

    fun span(start: Pair<Int, Int>, end: Pair<Int, Int>) =
        Span(start.first, start.second, end.first, end.second)

    "Lex spans correctly" {
        val tokens = lexResource("Spans.novah")

        tokens[0].span shouldBe span(1 to 1, 1 to 5)
        tokens[1].span shouldBe span(1 to 6, 1 to 19)
        tokens[2].span shouldBe span(1 to 20, 1 to 21)
        tokens[3].span shouldBe span(3 to 1, 3 to 21)
        tokens[4].value should beInstanceOf(Token.EOF::class)
    }

    "Lex comments correctly" {
        val tokens = lexResource("comments.novah")

        val typ = tokens.find { it.value is Token.TypeT }!!
        val myFun = tokens.find {
            val tk = it.value
            tk is Token.Ident && tk.v == "myFun"
        }!!
        val foo = tokens.find {
            it.value is Token.Ident && (it.value as Token.Ident).v == "foo"
        }!!
        val other = tokens.find {
            it.value is Token.Ident && (it.value as Token.Ident).v == "other"
        }!!
        val bar = tokens.find {
            it.value is Token.Ident && (it.value as Token.Ident).v == "bar"
        }!!

        typ.span shouldBe span(4 to 1, 4 to 5)
        typ.comment shouldBe Comment(" comments on type definitions work", Span(3, 1, 3, 37))

        myFun.span shouldBe span(10 to 1, 10 to 6)
        myFun.comment shouldBe Comment("\n comments on var\n types work\n", Span(6, 1, 9, 3), true)

        other.comment shouldBe Comment(" comments on var declaration work\n and are concatenated", Span(13, 1, 14, 24))

        foo.comment shouldBe Comment(" comments with a empty line are not added", Span(17, 1, 17, 44))

        bar.comment shouldBe null
    }

    "Lex UTF escapes correctly" {
        val tk = lexString(""" "bla bla \u0062 a" """)[0]

        tk.value.shouldBeInstanceOf<Token.StringT>()
        val str = tk.value as Token.StringT
        str.s shouldBe "bla bla b a"
        str.raw shouldBe "bla bla \\u0062 a"
    }
})
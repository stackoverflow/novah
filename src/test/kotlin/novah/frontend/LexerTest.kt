package novah.frontend

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import novah.frontend.TestUtil.lexResource

class LexerSpec : StringSpec() {
    init {

        "Lex spans correctly" {
            val tokens = lexResource("Spans.novah")

            tokens[0].span shouldBe span(1 to 1, 1 to 5)
            tokens[1].span shouldBe span(1 to 6, 1 to 19)
            tokens[2].span shouldBe span(1 to 20, 1 to 21)
            tokens[3].span shouldBe span(1 to 21, 3 to 1)
            tokens[4].span shouldBe span(3 to 1, 3 to 21)
        }

        "Lex indentation correctly" {
            val tokens = lexResource("Indent.novah")

            tokens.filter { it.value == Token.Indent } shouldHaveSize 3
            tokens.filter { it.value == Token.Dedent } shouldHaveSize 3
            tokens.filter { it.value == Token.Newline } shouldHaveSize 5
        }

        "!Lex whole file" {
            val tokens = lexResource("Example.novah")

            tokens.forEach { println(it) }
        }
    }

    companion object {
        private fun span(start: Pair<Int, Int>, end: Pair<Int, Int>) =
            Span(Position(start.first, start.second), Position(end.first, end.second))
    }
}
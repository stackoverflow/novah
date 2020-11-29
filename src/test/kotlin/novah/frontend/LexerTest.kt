package novah.frontend

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import novah.frontend.TestUtil.lexResource

class LexerSpec : StringSpec({

    fun span(start: Pair<Int, Int>, end: Pair<Int, Int>) =
        Span(Position(start.first, start.second), Position(end.first, end.second))

    "Lex spans correctly" {
        val tokens = lexResource("Spans.novah")

        tokens.forEach(::println)

        tokens[0].span shouldBe span(1 to 1, 1 to 5)
        tokens[1].span shouldBe span(1 to 6, 1 to 19)
        tokens[2].span shouldBe span(1 to 20, 1 to 21)
        tokens[3].span shouldBe span(1 to 21, 3 to 1)
        tokens[4].span shouldBe span(3 to 1, 3 to 21)
        tokens[5].span shouldBe span(3 to 21, 4 to 1)
        tokens[6].value should beInstanceOf(Token.EOF::class)
    }

    "Lex whole file" {
        val tokens = lexResource("Example.novah")

        tokens.forEach { println(it) }
    }
})
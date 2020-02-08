package novah.frontend

import novah.Module

object TestUtil {

    private fun lexString(input: String): MutableList<Spanned<Token>> {
        val lex = Lexer(input)
        val tks = mutableListOf<Spanned<Token>>()
        while(lex.hasNext()) {
            tks += lex.next()
        }
        return tks
    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun getResourceAsString(name: String): String {
        return LexerSpec::class.java.classLoader.getResource(name).readText(Charsets.UTF_8)
    }

    fun lexResource(res: String): MutableList<Spanned<Token>> {
        return lexString(getResourceAsString(res))
    }

    fun parseResource(res: String): Module {
        val resStr = getResourceAsString(res)
        val lexer = Lexer(resStr)
        val parser = Parser(lexer)
        return parser.parseFullModule()
    }
}
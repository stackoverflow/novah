package novah.frontend

import novah.ast.Desugar
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.frontend.typechecker.Inference.infer
import novah.frontend.typechecker.Type

object TestUtil {

    private fun lexString(input: String): MutableList<Spanned<Token>> {
        val lex = Lexer(input)
        val tks = mutableListOf<Spanned<Token>>()
        while(lex.hasNext()) {
            tks += lex.next()
        }
        tks += lex.next() // EOF
        return tks
    }

    @Suppress("RECEIVER_NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun getResourceAsString(name: String): String {
        return TestUtil::class.java.classLoader.getResource(name).readText(Charsets.UTF_8)
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

    fun inferString(code: String): Map<String, Type> {
        val lexer = Lexer(code)
        val parser = Parser(lexer)
        val desugar = Desugar(parser.parseFullModule())
        val canonical = desugar.desugar()
        return infer(canonical)
    }

    fun tvar(n: String) = Type.TVar(n)
    fun tfun(l: Type, r: Type) = Type.TFun(l, r)
    fun forall(x: String, t: Type) = Type.TForall(x, t)

    fun _i(i: Long) = Expr.IntE(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(n), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"
}
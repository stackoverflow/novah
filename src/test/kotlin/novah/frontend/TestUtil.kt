package novah.frontend

import novah.ast.Desugar
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.ast.optimized.Module as OModule
import novah.frontend.typechecker.Elem
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.Inference.infer
import novah.frontend.typechecker.Prim
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Type
import novah.optimize.Optimizer

object TestUtil {

    private fun lexString(input: String): MutableList<Spanned<Token>> {
        val lex = Lexer(input)
        val tks = mutableListOf<Spanned<Token>>()
        while (lex.hasNext()) {
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
        return parseString(resStr)
    }

    fun parseString(code: String): Module {
        val lexer = Lexer(code)
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

    fun preCompile(code: String, sourceName: String = "<test>"): OModule {
        val lexer = Lexer(code)
        val parser = Parser(lexer, sourceName)
        val desugar = Desugar(parser.parseFullModule())
        val canonical = desugar.desugar()
        infer(canonical)
        val opt = Optimizer(canonical)
        return opt.convert()
    }

    fun setupContext() {
        context.reset()
        Prim.addAll(context)
        context.add(Elem.CVar("*", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("+", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("-", tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("<=", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">=", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("<", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">", tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("==", forall("a", tfun(tvar("a"), tfun(tvar("a"), tBoolean)))))
        context.add(Elem.CVar("&&", tfun(tBoolean, tfun(tBoolean, tBoolean))))
        context.add(Elem.CVar("||", tfun(tBoolean, tfun(tBoolean, tBoolean))))
        context.add(Elem.CVar("println", tfun(tString, tvar("Unit"))))
        context.add(Elem.CVar("id", forall("a", tfun(tvar("a"), tvar("a")))))
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
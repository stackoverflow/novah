package novah.frontend

import novah.ast.Desugar
import novah.ast.source.Binder
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.frontend.typechecker.*
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.Inference.infer
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tInt
import novah.optimize.Converter
import novah.optimize.Optimizer
import novah.ast.optimized.Module as OModule

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
        val cvt = Converter(canonical)
        val oast = cvt.convert()
        return Optimizer.run(oast)
    }

    fun setupContext() {
        context.reset()
        Prim.addAll(context)
        context.add(Elem.CVar("*".raw(), tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("+".raw(), tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("-".raw(), tfun(tInt, tfun(tInt, tInt))))
        context.add(Elem.CVar("<=".raw(), tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">=".raw(), tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("<".raw(), tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar(">".raw(), tfun(tInt, tfun(tInt, tBoolean))))
        context.add(Elem.CVar("==".raw(), forall("a", tfun(tvar("a"), tfun(tvar("a"), tBoolean)))))
        context.add(Elem.CVar("&&".raw(), tfun(tBoolean, tfun(tBoolean, tBoolean))))
        context.add(Elem.CVar("||".raw(), tfun(tBoolean, tfun(tBoolean, tBoolean))))
        context.add(Elem.CVar("id".raw(), forall("a", tfun(tvar("a"), tvar("a")))))
    }

    fun tvar(n: String) = Type.TVar(n.raw())
    fun tfun(l: Type, r: Type) = Type.TFun(l, r)
    fun forall(x: String, t: Type) = Type.TForall(x.raw(), t)
    fun tcon(name: String, vararg ts: Type) = Type.TConstructor(name.raw(), ts.toList())

    fun _i(i: Int) = Expr.IntE(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns.map { Binder(it, Span.empty()) }, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(Binder(n, Span.empty())), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"
}
package novah.frontend

import com.github.michaelbull.result.unwrap
import novah.ast.source.Binder
import novah.ast.source.Expr
import novah.ast.source.Module
import novah.frontend.typechecker.FullModuleEnv
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.raw
import novah.main.Compiler
import novah.optimize.Optimization
import novah.optimize.Optimizer
import java.io.File
import java.nio.file.Path
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
        return parser.parseFullModule().unwrap()
    }

    fun cleanAndGetOutDir(output: String = "output"): File {
        val out = File(output)
        out.deleteRecursively()
        out.mkdir()
        return out
    }

    fun compilerFor(path: String, verbose: Boolean = true): Compiler {
        val sources = File("src/test/resources/$path").walkBottomUp().filter { it.extension == "novah" }
            .map { it.toPath() }
        return Compiler.new(sources, verbose)
    }

    fun compilerForCode(code: String, verbose: Boolean = true): Compiler {
        val sources = listOf(Compiler.Entry(Path.of("namespace"), code)).asSequence()
        return Compiler(sources, verbose)
    }

    fun compileCode(code: String, verbose: Boolean = true): FullModuleEnv {
        val compiler = compilerForCode(code, verbose)
        return compiler.compile().values.first()
    }

    fun compileAndOptimizeCode(code: String, verbose: Boolean = true): OModule {
        val ast = compileCode(code, verbose).ast
        val opt = Optimizer(ast)
        return Optimization.run(opt.convert())
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
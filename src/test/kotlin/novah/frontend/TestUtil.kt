package novah.frontend

import novah.ast.source.Binder
import novah.ast.source.Expr
import novah.ast.source.FunparPattern
import novah.ast.source.Module
import novah.data.map
import novah.data.unwrapOrElse
import novah.frontend.typechecker.Type
import novah.frontend.typechecker.raw
import novah.main.CompilationError
import novah.main.Compiler
import novah.main.FullModuleEnv
import novah.main.Source
import novah.optimize.Optimization
import novah.optimize.Optimizer
import java.io.File
import java.nio.file.Path
import novah.ast.optimized.Module as OModule

object TestUtil {

    private fun lexString(input: String): MutableList<Spanned<Token>> {
        val lex = Lexer(input.iterator())
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
        val lexer = Lexer(code.iterator())
        val parser = Parser(lexer)
        return parser.parseFullModule().unwrapOrElse {
            println(it.formatToConsole())
            throw CompilationError(listOf(it))
        }
    }

    fun cleanAndGetOutDir(output: String = "output"): File {
        val out = File(output)
        out.deleteRecursively()
        out.mkdir()
        return out
    }

    fun compilerFor(path: String, verbose: Boolean = false): Compiler {
        val sources = File("src/test/resources/$path").walkBottomUp().filter { it.extension == "novah" }
            .map { it.toPath() }
        return Compiler.new(sources, verbose)
    }

    fun compilerForCode(code: String, verbose: Boolean = false): Compiler {
        val sources = listOf(Source.SString(Path.of("namespace"), code)).asSequence()
        return Compiler(sources, verbose)
    }

    fun compileCode(code: String, verbose: Boolean = true): FullModuleEnv {
        val compiler = compilerForCode(code, verbose)
        return compiler.compile().values.first()
    }

    fun compileAndOptimizeCode(code: String, verbose: Boolean = true): OModule {
        val ast = compileCode(code, verbose).ast
        val opt = Optimizer(ast)
        return opt.convert().map { Optimization.run(it) }.unwrapOrElse { throw CompilationError(listOf(it)) }
    }

    fun tvar(n: String) = Type.TVar(n.raw())
    fun tfun(l: Type, r: Type) = Type.TFun(l, r)
    fun forall(x: String, t: Type) = Type.TForall(x.raw(), t)
    fun tcon(name: String, vararg ts: Type) = Type.TConstructor(name.raw(), ts.toList())

    fun _i(i: Int) = Expr.IntE(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns.map { FunparPattern.Bind(Binder(it, Span.empty())) }, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(FunparPattern.Bind(Binder(n, Span.empty()))), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"
}
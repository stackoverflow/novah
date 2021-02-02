package novah.frontend

import novah.Util.joinToStr
import novah.ast.source.Binder
import novah.ast.source.Expr
import novah.ast.source.FunparPattern
import novah.ast.source.Module
import novah.data.map
import novah.data.unwrapOrElse
import novah.frontend.hmftypechecker.Type
import novah.frontend.hmftypechecker.TypeVar
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

    fun _i(i: Int) = Expr.IntE(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns.map { FunparPattern.Bind(Binder(it, Span.empty())) }, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(FunparPattern.Bind(Binder(n, Span.empty()))), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"

    fun Type.findUnbound(): List<Type> = when (this) {
        is Type.TArrow -> args.flatMap { it.findUnbound() } + ret.findUnbound()
        is Type.TApp -> type.findUnbound() + types.flatMap { it.findUnbound() }
        is Type.TForall -> type.findUnbound()
        is Type.TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.findUnbound()
                is TypeVar.Unbound -> listOf(this)
                else -> emptyList()
            }
        }
        is Type.TConst -> emptyList()
    }

    class Ctx(val map: MutableMap<Int, Int> = mutableMapOf(), var counter: Int = 1)

    /**
     * Like [Type.show] but reset the numbers of vars.
     */
    fun Type.simpleName(nested: Boolean = false, ctx: Ctx = Ctx()): String =
        when (this) {
            is Type.TConst -> name.split('.').last()
            is Type.TApp -> {
                val sname = type.simpleName(nested, ctx)
                if (types.isEmpty()) sname else sname + " " + types.joinToString(" ") { it.simpleName(true, ctx) }
            }
            is Type.TArrow -> {
                val args =
                    if (args.size == 1) args[0].simpleName(!nested, ctx) else args.joinToString(
                        " ",
                        prefix = "(",
                        postfix = ")"
                    )
                if (nested) "($args -> ${ret.simpleName(false, ctx)})"
                else
                    "$args -> ${ret.simpleName(nested, ctx)}"
            }
            is Type.TForall -> {
                ids.forEach { ctx.map[it] = ctx.counter++ }
                val str = "forall ${ids.joinToStr(" ") { "t${ctx.map[it]}" }}. ${type.simpleName(false, ctx)}"
                if (nested) "($str)" else str
            }
            is Type.TVar -> {
                when (val tv = tvar) {
                    is TypeVar.Link -> tv.type.simpleName(nested, ctx)
                    is TypeVar.Unbound -> "t${tv.id}"
                    is TypeVar.Generic -> "t${tv.id}"
                    is TypeVar.Bound -> "t${ctx.map[tv.id]}"
                }
            }
        }
}
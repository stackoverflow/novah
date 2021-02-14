package novah.frontend

import novah.Util.joinToStr
import novah.ast.flatMapList
import novah.ast.show
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

    private fun compilerForCode(code: String, verbose: Boolean = false): Compiler {
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
        is Type.TRowEmpty -> emptyList()
        is Type.TRecord -> row.findUnbound()
        is Type.TRowExtend -> row.findUnbound() + labels.flatMapList { it.findUnbound() }
    }

    private class Ctx(val map: MutableMap<Int, Int> = mutableMapOf(), var counter: Int = 1)

    /**
     * Like [Type.show] but reset the ids of vars.
     */
    fun Type.simpleName(): String {
        fun go(t: Type, ctx: Ctx = Ctx(), nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is Type.TConst -> t.name.split('.').last()
            is Type.TApp -> {
                val sname = go(t.type, ctx, nested)
                val str = if (t.types.isEmpty()) sname
                else sname + " " + t.types.joinToString(" ") { go(it, ctx, true) }
                if (nested) "($str)" else str
            }
            is Type.TArrow -> {
                val args = if (t.args.size == 1) {
                    go(t.args[0], ctx, false)
                } else {
                    t.args.joinToString(" ", prefix = "(", postfix = ")") { go(it, ctx, false) }
                }
                if (nested) "($args -> ${go(t.ret, ctx, false)})"
                else "$args -> ${go(t.ret, ctx, nested)}"
            }
            is Type.TForall -> {
                t.ids.forEach { ctx.map[it] = ctx.counter++ }
                val str = "forall ${t.ids.joinToStr(" ") { "t${ctx.map[it]}" }}. ${go(t.type, ctx, false)}"
                if (nested || !topLevel) "($str)" else str
            }
            is Type.TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> go(tv.type, ctx, nested, topLevel)
                    is TypeVar.Unbound -> "t${tv.id}"
                    is TypeVar.Generic -> "t${tv.id}"
                    is TypeVar.Bound -> "t${ctx.map[tv.id]}"
                }
            }
            is Type.TRowEmpty -> "{}"
            is Type.TRecord -> {
                when (val ty = t.row.unlink()) {
                    is Type.TRowEmpty -> "{}"
                    !is Type.TRowExtend -> "{ | ${go(ty, ctx, topLevel = true)} }"
                    else -> {
                        val rows = go(ty, ctx, topLevel = true)
                        "{" + rows.substring(1, rows.lastIndex) + "}"
                    }
                }
            }
            is Type.TRowExtend -> {
                val labels = t.labels.show { k, v -> "$k : ${go(v, ctx, topLevel = true)}" }
                val str = when (val ty = t.row.unlink()) {
                    is Type.TRowEmpty -> labels
                    !is Type.TRowExtend -> "$labels | ${go(ty, ctx, topLevel = true)}"
                    else -> {
                        val rows = go(ty, ctx, topLevel = true)
                        "$labels, ${rows.substring(2, rows.lastIndex - 1)}"
                    }
                }
                "[ $str ]"
            }
        }
        return go(this, Ctx(), false, topLevel = true)
    }
}
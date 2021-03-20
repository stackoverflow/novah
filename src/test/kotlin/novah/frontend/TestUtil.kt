/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend

import novah.Util.joinToStr
import novah.ast.source.Binder
import novah.ast.source.Expr
import novah.ast.source.FunparPattern
import novah.ast.source.Module
import novah.data.flatMapList
import novah.data.map
import novah.data.show
import novah.data.unwrapOrElse
import novah.frontend.hmftypechecker.*
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
        val conv = opt.convert()
        if (opt.getWarnings().isNotEmpty()) {
            opt.getWarnings().forEach { println(it.formatToConsole()) }
        }
        return conv.map { Optimization.run(it) }.unwrapOrElse { throw CompilationError(listOf(it)) }
    }

    fun _i(i: Int) = Expr.IntE(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns.map { FunparPattern.Bind(Binder(it, Span.empty())) }, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(FunparPattern.Bind(Binder(n, Span.empty()))), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"

    fun Type.findUnbound(): List<Type> = when (this) {
        is TArrow -> args.flatMap { it.findUnbound() } + ret.findUnbound()
        is TApp -> type.findUnbound() + types.flatMap { it.findUnbound() }
        is TForall -> type.findUnbound()
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.findUnbound()
                is TypeVar.Unbound -> listOf(this)
                else -> emptyList()
            }
        }
        is TConst -> emptyList()
        is TRowEmpty -> emptyList()
        is TRecord -> row.findUnbound()
        is TRowExtend -> row.findUnbound() + labels.flatMapList { it.findUnbound() }
        is TImplicit -> type.findUnbound()
    }

    private val pat = Regex("""t\d+""")

    /**
     * Like [Type.show] but reset the ids of vars.
     */
    fun Type.simpleName(): String {
        val set = mutableSetOf<String>()
        var id = 1
        var str = show(false)
        val matches = pat.findAll(str)
        matches.forEach { m ->
            if (!set.contains(m.value)) {
                str = str.replace(m.value, "t${id++}")
                set += m.value
            }
        }
        return str
    }
}
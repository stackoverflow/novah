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

import novah.ast.source.*
import novah.data.mapList
import novah.data.unwrapOrElse
import novah.frontend.typechecker.*
import novah.frontend.typechecker.Type
import novah.main.*
import novah.optimize.Optimization
import novah.optimize.Optimizer
import java.io.File
import java.nio.file.Path
import novah.ast.optimized.Module as OModule

object TestUtil {

    fun lexString(input: String): MutableList<Spanned<Token>> {
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
        val parser = Parser(lexer, false)
        return parser.parseFullModule().unwrapOrElse {
            println(it.formatToConsole())
            throw CompilationError(setOf(it))
        }
    }

    fun cleanAndGetOutDir(output: String = "output"): File {
        val out = File(output)
        out.deleteRecursively()
        out.mkdir()
        return out
    }

    fun compilerFor(path: String, verbose: Boolean = false, devMode: Boolean = true, stdlib: Boolean = true): Compiler {
        val sources = File("src/test/resources/$path").walkBottomUp().filter { it.extension == "novah" }
            .map { it.toPath() }
        return Compiler.new(sources, null, null, Options(verbose, devMode, stdlib))
    }

    private fun compilerForCode(code: String, verbose: Boolean = false, devMode: Boolean = true): Compiler {
        val sources = listOf(Source.SString(Path.of("namespace"), code)).asSequence()
        return Compiler(sources, null, null, Options(verbose, devMode))
    }

    fun compileCode(code: String, verbose: Boolean = false, moduleName: String = "test"): FullModuleEnv {
        val compiler = compilerForCode(code, verbose)
        compiler.run(File("."), dryRun = true)
        if (compiler.errors().isNotEmpty()) {
            compiler.errors().forEach { println(it.formatToConsole()) }
        }
        return compiler.getModules()[moduleName]!!
    }

    fun compileAndOptimizeCode(code: String, verbose: Boolean = false): OModule {
        val compiler = compilerForCode(code, verbose)
        val ast = compiler.compile().values.last().ast
        val opt = Optimizer(ast, mutableMapOf())
        val conv = opt.convert()
        if (opt.errors().isNotEmpty()) {
            opt.errors().forEach { println(it.formatToConsole()) }
        }
        return Optimization.run(conv)
    }

    fun _i(i: Int) = Expr.Int32(i, "$i")
    fun _v(n: String) = Expr.Var(n)
    fun abs(ns: List<String>, e: Expr) = Expr.Lambda(ns.map { Pattern.Var(Expr.Var(it)) }, e)
    fun abs(n: String, e: Expr) = Expr.Lambda(listOf(Pattern.Var(Expr.Var(n))), e)

    fun String.module() = "module test\n\n${this.trimIndent()}"

    private fun Type.everywhere(f: (Type) -> Type): Type {
        fun go(t: Type): Type = when (t) {
            is TConst -> f(t)
            is TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> f(TVar(TypeVar.Link(go(tv.type))))
                    else -> f(t)
                }
            }
            is TApp -> f(t.copy(type = go(t.type), types = t.types.map(::go)))
            is TArrow -> f(t.copy(t.args.map { go(it) }, go(t.ret)))
            is TRecord -> f(t.copy(go(t.row)))
            is TRowEmpty -> f(t)
            is TRowExtend -> f(t.copy(row = go(t.row), labels = t.labels.mapList { go(it) }))
            is TImplicit -> f(t.copy(go(t.type)))
        }
        return go(this)
    }

    /**
     * Like [Type.show] but reset the ids of vars.
     */
    fun Type.simpleName(): String {
        var id = 0
        val map = mutableMapOf<Int, Int>()
        fun get(i: Id): Int {
            return if (map[i] != null) map[i]!!
            else {
                id++
                map[i] = id
                id
            }
        }

        val ty = everywhere { t ->
            when (t) {
                is TVar -> {
                    when (val tv = t.tvar) {
                        is TypeVar.Generic -> TVar(TypeVar.Generic(get(tv.id)))
                        is TypeVar.Unbound -> TVar(TypeVar.Unbound(get(tv.id), 0))
                        else -> t
                    }
                }
                else -> t
            }
        }
        return ty.show(false)
    }
}
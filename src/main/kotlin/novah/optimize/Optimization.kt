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
package novah.optimize

import novah.ast.optimized.*
import novah.data.mapList
import novah.optimize.Optimizer.Companion.ARRAY_TYPE

object Optimization {

    fun run(ast: Module): Module {
        return optimize(ast, comp(::optimizeCtorApplication, ::optimizeFunctionAndOperatorApplication))
    }

    private fun optimize(ast: Module, f: (Expr) -> Expr): Module {
        val decls = ArrayList<Decl>(ast.decls.size)
        for (d in ast.decls) {
            decls += when (d) {
                is Decl.TypeDecl -> d
                is Decl.ValDecl -> Decl.ValDecl(d.name, f(d.exp), d.visibility, d.span)
            }
        }
        return Module(ast.name, ast.sourceName, ast.hasLambda, decls)
    }

    /**
     * Make a fully applied constructor into a single new call
     * Ex.: ((Tuple 1) 2) -> new Tuple(1, 2)
     */
    private fun optimizeCtorApplication(expr: Expr): Expr {
        return everywhere(expr) { e ->
            if (e !is App) e
            else {
                var level = 0
                var exp = e
                val args = mutableListOf<Expr>()
                while (exp is App) {
                    args += exp.arg
                    level++
                    exp = exp.fn
                }
                if (exp is Expr.Constructor && level == exp.arity) {
                    args.reverse()
                    Expr.CtorApp(exp, args, e.type)
                } else e
            }
        }
    }

    private const val and = "\$and\$and"
    private const val or = "\$pipe\$pipe"
    private const val eq = "\$equals\$equals"
    private const val rail = "\$pipe\$greater"

    private const val primMod = "prim/Module"
    private const val coreMod = "novah/core/Module"

    /**
     * Unnest operators like &&, ||, ==, |>, etc
     * Ex.: ((&& ((&& true) a)) y) -> (&& true a y)
     */
    private fun optimizeFunctionAndOperatorApplication(expr: Expr): Expr {
        return everywhere(expr) { e ->
            if (e !is App) e
            else {
                val fn = e.fn
                val arg = e.arg
                when {
                    // optimize && and ||
                    fn is App && fn.fn is Var && (fn.fn.name == and || fn.fn.name == or) && fn.fn.className == primMod -> {
                        val name = fn.fn.name
                        val op = if (name == and) "&&" else "||"
                        if (arg is Expr.OperatorApp && fn.fn.name == arg.name) {
                            Expr.OperatorApp(op, arg.operands + listOf(fn.arg), e.type)
                        } else if (fn.arg is Expr.OperatorApp && fn.fn.name == fn.arg.name) {
                            Expr.OperatorApp(op, fn.arg.operands + listOf(arg), e.type)
                        } else {
                            Expr.OperatorApp(op, listOf(fn.arg, arg), e.type)
                        }
                    }
                    // optimize ==
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$primMod.$eq" -> {
                        Expr.OperatorApp("==", listOf(fn.arg, arg), e.type)
                    }
                    // optimize |>
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.$rail" -> {
                        Expr.App(arg, fn.arg, e.type)
                    }
                    // optimize `arrayOf [...]` to a literal array
                    fn is Var && fn.fullname() == "$coreMod.arrayOf" && arg is Expr.VectorLiteral -> {
                        Expr.ArrayLiteral(arg.exps, Clazz(ARRAY_TYPE, arg.type.pars))
                    }
                    // optimize `format "..." [...]` to `String.format "..." <literal-array>` 
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.format" && arg is Expr.VectorLiteral -> {
                        val arr = Expr.ArrayLiteral(arg.exps, Clazz(ARRAY_TYPE, arg.type.pars))
                        Expr.NativeStaticMethod(stringFormat, listOf(fn.arg, arr), e.type)
                    }
                    else -> e
                }
            }
        }
    }

    /**
     * Visit every expression in this AST (bottom->up)
     */
    private fun everywhere(expr: Expr, f: (Expr) -> Expr): Expr {
        fun go(e: Expr): Expr = when (e) {
            is Expr.Lambda -> f(e.copy(body = go(e.body)))
            is Expr.App -> f(e.copy(fn = go(e.fn), arg = go(e.arg)))
            is Expr.CtorApp -> f(e.copy(ctor = go(e.ctor) as Expr.Constructor, args = e.args.map(::go)))
            is Expr.If -> f(e.copy(conds = e.conds.map { (c, t) -> go(c) to go(t) }, elseCase = go(e.elseCase)))
            is Expr.Let -> f(e.copy(bindExpr = go(e.bindExpr), body = go(e.body)))
            is Expr.Do -> f(e.copy(exps = e.exps.map(::go)))
            is Expr.OperatorApp -> f(e.copy(operands = e.operands.map(::go)))
            is Expr.InstanceOf -> f(e.copy(exp = go(e.exp)))
            is Expr.NativeFieldGet -> f(e.copy(thisPar = go(e.thisPar)))
            is Expr.NativeFieldSet -> f(e.copy(thisPar = go(e.thisPar), par = go(e.par)))
            is Expr.NativeStaticFieldSet -> f(e.copy(par = go(e.par)))
            is Expr.NativeMethod -> f(e.copy(thisPar = go(e.thisPar), pars = e.pars.map(::go)))
            is Expr.NativeStaticMethod -> f(e.copy(pars = e.pars.map(::go)))
            is Expr.NativeCtor -> f(e.copy(pars = e.pars.map(::go)))
            is Expr.Throw -> f(e.copy(expr = go(e.expr)))
            is Expr.Cast -> f(e.copy(expr = go(e.expr)))
            is Expr.RecordExtend -> f(e.copy(labels = e.labels.mapList(::go), expr = go(e.expr)))
            is Expr.RecordSelect -> f(e.copy(expr = go(e.expr)))
            is Expr.RecordRestrict -> f(e.copy(expr = go(e.expr)))
            is Expr.VectorLiteral -> f(e.copy(exps = e.exps.map(::go)))
            is Expr.SetLiteral -> f(e.copy(exps = e.exps.map(::go)))
            is Expr.ArrayLiteral -> f(e.copy(exps = e.exps.map(::go)))
            else -> f(e)
        }
        return go(expr)
    }

    private fun comp(vararg fs: (Expr) -> Expr): (Expr) -> Expr = { e ->
        fs.fold(e) { ex, f -> f(ex) }
    }

    private val stringFormat = String::class.java.methods.find { 
        it.name == "format" && it.parameterTypes[0] == String::class.java 
    }!!
}

private typealias App = Expr.App
private typealias Var = Expr.Var
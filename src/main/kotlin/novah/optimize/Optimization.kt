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

import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.optimized.Module
import novah.data.mapList
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.Errors
import novah.frontend.error.ProblemContext
import novah.main.CompilationError

object Optimization {

    fun run(ast: Module): Module {
        return optimize(ast, comp(::optimizeCtorApplication, ::optimizeOperatorApplication))
    }

    private fun optimize(ast: Module, f: (Expr) -> Expr): Module {
        val decls = ArrayList<Decl>(ast.decls.size)
        for (d in ast.decls) {
            decls += when (d) {
                is Decl.TypeDecl -> d
                is Decl.ValDecl -> {
                    reportUnusedVars(d.exp, d.span, ast)
                    Decl.ValDecl(d.name, f(d.exp), d.visibility, d.span)
                }
            }
        }
        return Module(ast.name, ast.sourceName, ast.hasLambda, decls)
    }

    // TODO: report the span of the specific lambda/let with
    //       unused variables instead of the whole declaration
    private fun reportUnusedVars(expr: Expr, span: Span, ast: Module) {
        val unused = everywhereAccumulating(expr, emptyList<String>()) { e, l ->
            when (e) {
                is Expr.Lambda -> if (e.binder != null) l + e.binder else l
                is Expr.Let -> l + e.binder
                is Expr.LocalVar -> if (e.name in l) l - e.name else l
                else -> l
            }
        }
        if (unused.isNotEmpty()) {
            val err = CompilerProblem(
                Errors.unusedVariables(unused),
                ProblemContext.OPTIMIZATION,
                span,
                ast.sourceName,
                ast.name
            )
            throw CompilationError(listOf(err))
        }
    }

    /**
     * Make a fully applied constructor into a single new call
     * Ex.: ((Tuple 1) 2) -> new Tuple(1, 2)
     */
    private fun optimizeCtorApplication(expr: Expr): Expr {
        return everywhere(expr) { e ->
            if (e !is Expr.App) e
            else {
                var level = 0
                var exp = e
                val args = mutableListOf<Expr>()
                while (exp is Expr.App) {
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

    /**
     * Unnest applications of && and || and ==
     * Ex.: ((&& ((&& true) a)) y) -> (&& true a y)
     */
    private fun optimizeOperatorApplication(expr: Expr): Expr {
        return everywhere(expr) { e ->
            if (e !is Expr.App) e
            else {
                val fn = e.fn
                val arg = e.arg
                when {
                    fn is Expr.App && fn.fn is Expr.Var && (fn.fn.name == "&&" || fn.fn.name == "||") && fn.fn.className == "prim/Module" -> {
                        val name = fn.fn.name
                        if (arg is Expr.OperatorApp && fn.fn.name == arg.name) {
                            Expr.OperatorApp(name, arg.operands + listOf(fn.arg), e.type)
                        } else if (fn.arg is Expr.OperatorApp && fn.fn.name == fn.arg.name) {
                            Expr.OperatorApp(name, fn.arg.operands + listOf(arg), e.type)
                        } else {
                            Expr.OperatorApp(name, listOf(fn.arg, arg), e.type)
                        }
                    }
                    fn is Expr.App && fn.fn is Expr.Var && fn.fn.name == "==" && fn.fn.className == "prim/Module" -> {
                        Expr.OperatorApp("==", listOf(fn.arg, arg), e.type)
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
            else -> f(e)
        }
        return go(expr)
    }

    /**
     * Visit every expression top->bottom accumulating a value.
     */
    private fun <T> everywhereAccumulating(expr: Expr, init: List<T>, f: (Expr, List<T>) -> List<T>): List<T> {
        fun go(e: Expr, acc: List<T>): List<T> = when (e) {
            is Expr.Lambda -> go(e.body, f(e, acc))
            is Expr.App -> {
                go(e.arg, go(e.fn, f(e, acc)))
            }
            is Expr.CtorApp -> {
                val ac = go(e.ctor, f(e, acc))
                e.args.fold(ac) { accu, arg -> go(arg, accu) }
            }
            is Expr.If -> {
                val ac = e.conds.fold(f(e, acc)) { accu, (c, t) -> go(c, go(t, accu)) }
                go(e.elseCase, ac)
            }
            is Expr.Let -> go(e.body, go(e.bindExpr, f(e, acc)))
            is Expr.Do -> e.exps.fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            is Expr.OperatorApp -> e.operands.fold(f(e, acc)) { accu, op -> go(op, accu) }
            is Expr.InstanceOf -> go(e.exp, f(e, acc))
            is Expr.NativeFieldGet -> go(e.thisPar, f(e, acc))
            is Expr.NativeFieldSet -> go(e.thisPar, go(e.par, f(e, acc)))
            is Expr.NativeStaticFieldSet -> go(e.par, f(e, acc))
            is Expr.NativeMethod -> e.pars.fold(go(e.thisPar, f(e, acc))) { accu, exp -> go(exp, accu) }
            is Expr.NativeStaticMethod -> e.pars.fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            is Expr.NativeCtor -> e.pars.fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            is Expr.Throw -> go(e.expr, f(e, acc))
            is Expr.Cast -> go(e.expr, f(e, acc))
            is Expr.RecordExtend -> {
                val vals = e.labels.values().flatten()
                (vals + e.expr).fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            }
            is Expr.RecordSelect -> go(e.expr, f(e, acc))
            is Expr.RecordRestrict -> go(e.expr, f(e, acc))
            is Expr.VectorLiteral -> e.exps.fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            is Expr.SetLiteral -> e.exps.fold(f(e, acc)) { accu, exp -> go(exp, accu) }
            else -> f(e, acc)
        }
        return go(expr, init)
    }

    private fun comp(vararg fs: (Expr) -> Expr): (Expr) -> Expr = { e ->
        fs.fold(e) { ex, f -> f(ex) }
    }
}
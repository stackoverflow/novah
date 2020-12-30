package novah.optimize

import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.optimized.Module

object Optimization {

    fun run(ast: Module): Module {
        return optimize(ast, comp(::optimizeCtorApplication, ::shortCircuitOperators))
    }

    private fun optimize(ast: Module, f: (Expr) -> Expr): Module {
        val decls = ArrayList<Decl>(ast.decls.size)
        for (d in ast.decls) {
            decls += when (d) {
                is Decl.DataDecl -> d
                is Decl.ValDecl -> Decl.ValDecl(d.name, f(d.exp), d.visibility, d.lineNumber)
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
     * Unnest applications of && and ||
     * Ex.: ((&& ((&& true) a)) y) -> (&& true a y)
     */
    private fun shortCircuitOperators(expr: Expr): Expr {
        return everywhere(expr) { e ->
            if (e !is Expr.App) e
            else {
                val fn = e.fn
                val arg = e.arg
                when {
                    fn is Expr.App && fn.fn is Expr.Var && (fn.fn.name == "&&" || fn.fn.name == "||") && fn.fn.className == "prim/Module" -> {
                        val name = fn.fn.name
                        if (arg is Expr.ShortCircuitOp && fn.fn.name == arg.name) {
                            Expr.ShortCircuitOp(name, arg.operands + listOf(fn.arg), e.type)
                        } else if (fn.arg is Expr.ShortCircuitOp && fn.fn.name == fn.arg.name) {
                            Expr.ShortCircuitOp(name, fn.arg.operands + listOf(arg), e.type)
                        } else {
                            Expr.ShortCircuitOp(name, listOf(fn.arg, arg), e.type)
                        }
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
            else -> f(e)
        }
        return go(expr)
    }

    private fun comp(vararg fs: (Expr) -> Expr): (Expr) -> Expr = { e ->
        fs.fold(e) { ex, f -> f(ex) }
    }
}
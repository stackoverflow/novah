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

import io.lacuna.bifurcan.List
import io.lacuna.bifurcan.Set
import novah.Core
import novah.ast.optimized.*
import novah.collections.Record
import novah.data.mapList
import novah.optimize.Optimizer.Companion.ARRAY_TYPE
import novah.optimize.Optimizer.Companion.eqDouble
import novah.optimize.Optimizer.Companion.eqFloat
import novah.optimize.Optimizer.Companion.eqInt
import novah.optimize.Optimizer.Companion.eqLong
import novah.optimize.Optimizer.Companion.eqString
import novah.optimize.Optimizer.Companion.vecSize

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
                    Expr.CtorApp(exp, args, e.type, e.span)
                } else e
            }
        }
    }

    private const val and = "\$and\$and"
    private const val or = "\$pipe\$pipe"
    private const val eq = "\$equals\$equals"
    private const val rail = "\$pipe\$greater"
    private const val gt = "\$greater"
    private const val gtEq = "\$greater\$equals"
    private const val lt = "\$smaller"
    private const val ltEq = "\$smaller\$equals"

    private val binOps = mapOf(
        "\$plus" to "+",
        "\$minus" to "-",
        "\$times" to "*",
        "\$slash" to "/"
    )

    private val numericClasses = setOf(
        "java.lang.Integer",
        "java.lang.Long",
        "java.lang.Float",
        "java.lang.Double",
    )

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
                            Expr.OperatorApp(op, arg.operands + listOf(fn.arg), e.type, e.span)
                        } else if (fn.arg is Expr.OperatorApp && fn.fn.name == fn.arg.name) {
                            Expr.OperatorApp(op, fn.arg.operands + listOf(arg), e.type, e.span)
                        } else {
                            Expr.OperatorApp(op, listOf(fn.arg, arg), e.type, e.span)
                        }
                    }
                    // optimize |>
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.$rail" -> {
                        Expr.App(arg, fn.arg, e.type, e.span)
                    }
                    // optimize `arrayOf [...]` to a literal array
                    fn is Var && fn.fullname() == "$coreMod.arrayOf" && arg is Expr.VectorLiteral -> {
                        Expr.ArrayLiteral(arg.exps, Clazz(ARRAY_TYPE, arg.type.pars), e.span)
                    }
                    // optimize `format "..." [...]` to `String.format "..." <literal-array>` 
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.format" && arg is Expr.VectorLiteral -> {
                        val arr = Expr.ArrayLiteral(arg.exps, Clazz(ARRAY_TYPE, arg.type.pars), e.span)
                        Expr.NativeStaticMethod(stringFormat, listOf(fn.arg, arr), e.type, e.span)
                    }
                    // optimize fully applied numeric operators like +, -, etc
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.className == coreMod
                            && fn.fn.fn.name in binOps.keys && arg.type.type.className in numericClasses -> {
                        Expr.OperatorApp(binOps[fn.fn.fn.name]!!, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize vector access
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.\$bang" -> {
                        Expr.NativeMethod(vecNth, fn.arg, listOf(arg), e.type, e.span)
                    }
                    // optimize count function for vectors, sets and records
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.count" && arg is Expr.VectorLiteral -> {
                        Expr.NativeMethod(vecSize, arg, emptyList(), e.type, e.span)
                    }
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.count" && arg is Expr.SetLiteral -> {
                        Expr.NativeMethod(setSize, arg, emptyList(), e.type, e.span)
                    }
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.count" && arg is Expr.RecordExtend -> {
                        Expr.NativeMethod(recSize, arg, emptyList(), e.type, e.span)
                    }
                    // optimize == for some types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(eqInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(eqLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.isFloat32() -> {
                        Expr.NativeStaticMethod(eqFloat, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.isFloat64() -> {
                        Expr.NativeStaticMethod(eqDouble, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.isString() -> {
                        Expr.NativeStaticMethod(eqString, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize > for some types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gt"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(gtInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gt"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(gtLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gt"
                            && arg.type.isFloat64() -> {
                        Expr.NativeStaticMethod(gtDouble, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize < for some types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$lt"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(ltInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$lt"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(ltLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$lt"
                            && arg.type.isFloat64() -> {
                        Expr.NativeStaticMethod(ltDouble, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize >= for some types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gtEq"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(gtEqInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gtEq"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(gtEqLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gtEq"
                            && arg.type.isFloat64() -> {
                        Expr.NativeStaticMethod(gtEqDouble, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize <= for some types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$ltEq"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(ltEqInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$ltEq"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(ltEqLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$ltEq"
                            && arg.type.isFloat64() -> {
                        Expr.NativeStaticMethod(ltEqDouble, listOf(fn.arg, arg), e.type, e.span)
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
            is Expr.TryCatch -> {
                val tryy = go(e.tryExpr)
                val cs = e.catches.map { it.copy(expr = go(it.expr)) }
                f(e.copy(tryExpr = tryy, catches = cs, finallyExp = e.finallyExp?.let(::go)))
            }
            is Expr.While -> f(e.copy(cond = go(e.cond), exps = e.exps.map(::go)))
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
    private val vecNth = List::class.java.methods.find { it.name == "nth" }!!
    private val setSize = Set::class.java.methods.find { it.name == "size" }!!
    private val recSize = Record::class.java.methods.find { it.name == "size" }!!
    private val gtInt = Core::class.java.methods.find { it.name == "greaterInt" }!!
    private val ltInt = Core::class.java.methods.find { it.name == "smallerInt" }!!
    private val gtEqInt = Core::class.java.methods.find { it.name == "greaterOrEqualsInt" }!!
    private val ltEqInt = Core::class.java.methods.find { it.name == "smallerOrEqualsInt" }!!
    private val gtLong = Core::class.java.methods.find { it.name == "greaterLong" }!!
    private val ltLong = Core::class.java.methods.find { it.name == "smallerLong" }!!
    private val gtEqLong = Core::class.java.methods.find { it.name == "greaterOrEqualsLong" }!!
    private val ltEqLong = Core::class.java.methods.find { it.name == "smallerOrEqualsLong" }!!
    private val gtDouble = Core::class.java.methods.find { it.name == "greaterDouble" }!!
    private val ltDouble = Core::class.java.methods.find { it.name == "smallerDouble" }!!
    private val gtEqDouble = Core::class.java.methods.find { it.name == "greaterOrEqualsDouble" }!!
    private val ltEqDouble = Core::class.java.methods.find { it.name == "smallerOrEqualsDouble" }!!
}

private typealias App = Expr.App
private typealias Var = Expr.Var
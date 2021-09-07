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
import novah.Core
import novah.ast.optimized.*
import novah.optimize.Optimizer.Companion.ARRAY_TYPE
import novah.optimize.Optimizer.Companion.OBJECT_TYPE
import novah.optimize.Optimizer.Companion.eqDouble
import novah.optimize.Optimizer.Companion.eqFloat
import novah.optimize.Optimizer.Companion.eqInt
import novah.optimize.Optimizer.Companion.eqLong
import novah.optimize.Optimizer.Companion.eqString

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
        return expr.everywhere { e ->
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

    private const val coreMod = "novah/core/\$Module"

    /**
     * Unnest operators like &&, ||, ==, |>, etc
     * Ex.: ((&& ((&& true) a)) y) -> (&& true a y)
     */
    private fun optimizeFunctionAndOperatorApplication(expr: Expr): Expr {
        return expr.everywhere { e ->
            if (e !is App) e
            else {
                val fn = e.fn
                val arg = e.arg
                when {
                    // optimize && and ||
                    fn is App && fn.fn is Var && (fn.fn.name == and || fn.fn.name == or) && fn.fn.className == coreMod -> {
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
                    // optimize unsafeCast
                    fn is Var && fn.fullname() == "$coreMod.unsafeCast" -> {
                        val (from, to) = fn.type.pars
                        // cast if types are different and result type is not Object
                        if (from.type == to.type || to.type == OBJECT_TYPE) arg
                        else Expr.Cast(arg, to, e.span)
                    }
                    // optimize `format "..." [...]` to `String.format "..." <literal-array>` 
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.format" && arg is Expr.ListLiteral -> {
                        val arr = Expr.ArrayLiteral(arg.exps, Clazz(ARRAY_TYPE, arg.type.pars), e.span)
                        Expr.NativeStaticMethod(stringFormat, listOf(fn.arg, arr), e.type, e.span)
                    }
                    // optimize fully applied numeric operators like +, -, etc
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.className == coreMod
                            && fn.fn.fn.name in binOps.keys && arg.type.type.className in numericClasses -> {
                        Expr.OperatorApp(binOps[fn.fn.fn.name]!!, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize list access
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.\$bang" -> {
                        Expr.NativeMethod(vecNth, fn.arg, listOf(arg), e.type, e.span)
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
                    // optimize bitwise operations for Int and Long
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.bitNot" && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitNotInt, listOf(arg), e.type, e.span)
                    }
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.bitNot" && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitNotLong, listOf(arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitAnd"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitAndInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitAnd"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitAndLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitOr"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitOrInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitOr"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitOrLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitXor"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitXorInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitXor"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitXorLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftLeft"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitShiftLeftInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftLeft"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitShiftLeftLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftRight"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(bitShiftRightInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftRight"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(bitShiftRightLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitUnsignedShiftRight"
                            && arg.type.isInt32() -> {
                        Expr.NativeStaticMethod(unsignedBitShiftRightInt, listOf(fn.arg, arg), e.type, e.span)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitUnsignedShiftRight"
                            && arg.type.isInt64() -> {
                        Expr.NativeStaticMethod(unsignedBitShiftRightLong, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize `Array.size array` to `array.length`
                    fn is Var && fn.fullname() == "novah/array/\$Module.size" -> {
                        Expr.ArrayLength(arg, e.type, e.span)
                    }
                    // optimize `not`
                    fn is Var && fn.fullname() == "$coreMod.not" -> {
                        Expr.NativeStaticMethod(negate, listOf(arg), e.type, e.span)
                    }
                    else -> e
                }
            }
        }
    }

    private fun comp(vararg fs: (Expr) -> Expr): (Expr) -> Expr = { e ->
        fs.fold(e) { ex, f -> f(ex) }
    }

    private val stringFormat = String::class.java.methods.find {
        it.name == "format" && it.parameterTypes[0] == String::class.java
    }!!
    private val vecNth = List::class.java.methods.find { it.name == "nth" }!!
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
    private val bitAndInt = Core::class.java.methods.find { it.name == "bitAndInt" }!!
    private val bitOrInt = Core::class.java.methods.find { it.name == "bitOrInt" }!!
    private val bitXorInt = Core::class.java.methods.find { it.name == "bitXorInt" }!!
    private val bitNotInt = Core::class.java.methods.find { it.name == "bitNotInt" }!!
    private val bitShiftLeftInt = Core::class.java.methods.find { it.name == "bitShiftLeftInt" }!!
    private val bitShiftRightInt = Core::class.java.methods.find { it.name == "bitShiftRightInt" }!!
    private val unsignedBitShiftRightInt = Core::class.java.methods.find { it.name == "unsignedBitShiftRightInt" }!!
    private val bitAndLong = Core::class.java.methods.find { it.name == "bitAndLong" }!!
    private val bitOrLong = Core::class.java.methods.find { it.name == "bitOrLong" }!!
    private val bitXorLong = Core::class.java.methods.find { it.name == "bitXorLong" }!!
    private val bitNotLong = Core::class.java.methods.find { it.name == "bitNotLong" }!!
    private val bitShiftLeftLong = Core::class.java.methods.find { it.name == "bitShiftLeftLong" }!!
    private val bitShiftRightLong = Core::class.java.methods.find { it.name == "bitShiftRightLong" }!!
    private val unsignedBitShiftRightLong = Core::class.java.methods.find { it.name == "unsignedBitShiftRightLong" }!!
    private val negate = Core::class.java.methods.find { it.name == "not" }!!
}

private typealias App = Expr.App
private typealias Var = Expr.Var
/**
 * Copyright 2022 Islon Scherer
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
import novah.backend.TypeUtil.isPrimitive
import novah.optimize.Optimizer.Companion.ARRAY_TYPE
import novah.optimize.Optimizer.Companion.OBJECT_TYPE
import novah.range.*
import novah.range.CharRange
import novah.range.IntRange
import novah.range.LongRange
import org.objectweb.asm.Type
import java.lang.reflect.Method

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
    private const val neq = "\$bang\$equals"
    private const val rail = "\$pipe\$greater"
    private const val backRail = "\$smaller\$pipe"
    private const val gt = "\$greater"
    private const val gtEq = "\$greater\$equals"
    private const val lt = "\$smaller"
    private const val ltEq = "\$smaller\$equals"
    private const val dotDot = "\$dot\$dot"
    private const val dotDotDot = "\$dot\$dot\$dot"

    private val binOps = mapOf(
        "\$plus" to "+",
        "\$minus" to "-",
        "\$times" to "*",
        "\$slash" to "/"
    )

    private val numericClasses = 3..8

    private val longClass = Clazz(Type.LONG_TYPE)

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
                    // optimize <|
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.$backRail" -> {
                        Expr.App(fn.arg, arg, e.type, e.span)
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
                            && fn.fn.fn.name in binOps.keys && arg.type.type.sort in numericClasses -> {
                        Expr.OperatorApp(binOps[fn.fn.fn.name]!!, listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize list access
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.\$bang" -> {
                        val toLongFn = Expr.NativeMethod(toLong, arg, listOf(), longClass, arg.span)
                        Expr.NativeMethod(vecNth, fn.arg, listOf(toLongFn), e.type, e.span)
                    }
                    // optimize == for primitives types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$eq"
                            && arg.type.type.isPrimitive() -> {
                        Expr.OperatorApp("==", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize != for primitives types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$neq"
                            && arg.type.type.isPrimitive() -> {
                        Expr.OperatorApp("!=", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize > for numeric types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gt"
                            && arg.type.type.sort in numericClasses -> {
                        Expr.OperatorApp(">", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize < for numeric types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$lt"
                            && arg.type.type.sort in numericClasses -> {
                        Expr.OperatorApp("<", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize >= for numeric types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$gtEq"
                            && arg.type.type.sort in numericClasses -> {
                        Expr.OperatorApp(">=", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize <= for numeric types
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$ltEq"
                            && arg.type.type.sort in numericClasses -> {
                        Expr.OperatorApp("<=", listOf(fn.arg, arg), e.type, e.span)
                    }
                    // optimize bitwise operations for Int and Long
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.bitNot" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitNotInt, listOf(arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitNotLong, listOf(arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitAnd" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitAndInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitAndLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitOr" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitOrInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitOrLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitXor" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitXorInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitXorLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftLeft" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitShiftLeftInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitShiftLeftLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitShiftRight" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(bitShiftRightInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(bitShiftRightLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.bitUnsignedShiftRight" -> {
                        if (arg.type.isInt32())
                            Expr.NativeStaticMethod(unsignedBitShiftRightInt, listOf(fn.arg, arg), e.type, e.span)
                        else if (arg.type.isInt64())
                            Expr.NativeStaticMethod(unsignedBitShiftRightLong, listOf(fn.arg, arg), e.type, e.span)
                        else e
                    }
                    // optimize `forEachRange`
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.forEachRange" -> {
                        Expr.NativeMethod(forEachRange, fn.arg, listOf(arg), e.type, e.span)
                    }
                    // optimize `..` and '...'
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$dotDot" -> {
                        makeRangeCtor(e, fn.arg, arg, open = false)
                    }
                    fn is App && fn.fn is App && fn.fn.fn is Var && fn.fn.fn.fullname() == "$coreMod.$dotDotDot" -> {
                        makeRangeCtor(e, fn.arg, arg, open = true)
                    }
                    // optimize rangeToList and rangeToSet
                    fn is Var && fn.fullname() == "$coreMod.rangeToList" -> {
                        Expr.NativeMethod(rangeToList, arg, emptyList(), e.type, e.span)
                    }
                    fn is Var && fn.fullname() == "$coreMod.rangeToSet" -> {
                        Expr.NativeMethod(rangeToSet, arg, emptyList(), e.type, e.span)
                    }
                    // optimize `len` function
                    fn is App && fn.fn is Var && fn.fn.fullname() == "$coreMod.len" -> {
                        when {
                            arg.type.isString() -> Expr.NativeMethod(stringLength, arg, emptyList(), e.type, e.span)
                            arg.type.isList() -> Expr.NativeStaticMethod(listSize, listOf(arg), e.type, e.span)
                            arg.type.isSet() -> Expr.NativeStaticMethod(setSize, listOf(arg), e.type, e.span)
                            arg.type.isMap() -> Expr.NativeStaticMethod(mapSize, listOf(arg), e.type, e.span)
                            arg.type.isArray() -> Expr.ArrayLength(arg, arg.type, e.span)
                            else -> e
                        }
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

    private fun makeRangeCtor(e: Expr, arg1: Expr, arg2: Expr, open: Boolean): Expr =
        when (arg2.type.type.sort) {
            5 -> {
                val ctor = if (open) newIntOpenRange else newIntRange
                Expr.NativeCtor(ctor, listOf(arg1, arg2), e.type, e.span)
            }
            7 -> {
                val ctor = if (open) newLongOpenRange else newLongRange
                Expr.NativeCtor(ctor, listOf(arg1, arg2), e.type, e.span)
            }
            6 -> {
                Expr.NativeCtor(newFloatRange, listOf(arg1, arg2), e.type, e.span)
            }
            8 -> {
                Expr.NativeCtor(newDoubleRange, listOf(arg1, arg2), e.type, e.span)
            }
            2 -> {
                val ctor = if (open) newCharOpenRange else newCharRange
                Expr.NativeCtor(ctor, listOf(arg1, arg2), e.type, e.span)
            }
            else -> e
        }

    private fun comp(vararg fs: (Expr) -> Expr): (Expr) -> Expr = { e ->
        fs.fold(e) { ex, f -> f(ex) }
    }

    private val stringFormat = String::class.java.methods.find {
        it.name == "format" && it.parameterTypes[0] == String::class.java
    }!!
    private val vecNth = List::class.java.methods.find { it.name == "nth" }!!
    private lateinit var bitAndInt: Method
    private lateinit var bitOrInt: Method
    private lateinit var bitXorInt: Method
    private lateinit var bitNotInt: Method
    private lateinit var bitShiftLeftInt: Method
    private lateinit var bitShiftRightInt: Method
    private lateinit var unsignedBitShiftRightInt: Method
    private lateinit var bitAndLong: Method
    private lateinit var bitOrLong: Method
    private lateinit var bitXorLong: Method
    private lateinit var bitNotLong: Method
    private lateinit var bitShiftLeftLong: Method
    private lateinit var bitShiftRightLong: Method
    private lateinit var unsignedBitShiftRightLong: Method
    private lateinit var negate: Method
    private lateinit var listSize: Method
    private lateinit var setSize: Method
    private lateinit var mapSize: Method

    init {
        Core::class.java.methods.forEach {
            when (it.name) {
                "bitAndInt" -> bitAndInt = it
                "bitOrInt" -> bitOrInt = it
                "bitXorInt" -> bitXorInt = it
                "bitNotInt" -> bitNotInt = it
                "bitShiftLeftInt" -> bitShiftLeftInt = it
                "bitShiftRightInt" -> bitShiftRightInt = it
                "unsignedBitShiftRightInt" -> unsignedBitShiftRightInt = it
                "bitAndLong" -> bitAndLong = it
                "bitOrLong" -> bitOrLong = it
                "bitXorLong" -> bitXorLong = it
                "bitNotLong" -> bitNotLong = it
                "bitShiftLeftLong" -> bitShiftLeftLong = it
                "bitShiftRightLong" -> bitShiftRightLong = it
                "unsignedBitShiftRightLong" -> unsignedBitShiftRightLong = it
                "not" -> negate = it
                "listSize" -> listSize = it
                "setSize" -> setSize = it
                "mapSize" -> mapSize = it
            }
        }
    }

    private val toLong = java.lang.Integer::class.java.methods.find { it.name == "longValue" }!!
    private val newIntRange = IntRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newIntOpenRange = IntOpenRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newLongRange = LongRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newLongOpenRange = LongOpenRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newFloatRange = FloatRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newDoubleRange = DoubleRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newCharRange = CharRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val newCharOpenRange = CharOpenRange::class.java.constructors.find { it.parameterCount == 2 }!!
    private val forEachRange = Range::class.java.methods.find { it.name == "foreach" }!!
    private val rangeToList = Range::class.java.methods.find { it.name == "toList" }!!
    private val rangeToSet = Range::class.java.methods.find { it.name == "toSet" }!!
    private val stringLength = String::class.java.methods.find { it.name == "length" }!!
}

private typealias App = Expr.App
private typealias Var = Expr.Var
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
package novah.ast.optimized

import novah.ast.source.Visibility
import novah.data.LabelMap
import novah.data.isEmpty
import novah.data.mapList
import novah.data.show
import novah.frontend.Span
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor as JConstructor

/**
 * The optmized AST used for code generation
 */

data class Module(val name: String, val sourceName: String, val hasLambda: Boolean, val decls: List<Decl>)

sealed class Decl(open val span: Span) {
    data class TypeDecl(
        val name: String,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        val visibility: Visibility,
        override val span: Span
    ) : Decl(span)

    data class ValDecl(val name: String, val exp: Expr, val visibility: Visibility, override val span: Span) :
        Decl(span)
}

data class DataConstructor(val name: String, val args: List<Clazz>, val visibility: Visibility)

sealed class Expr(open val type: Clazz, open val span: Span) {
    data class ByteE(val v: Byte, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Int16(val v: Short, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Int32(val v: Int, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Int64(val v: Long, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Float32(val v: Float, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Float64(val v: Double, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class StringE(val v: String, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class CharE(val v: Char, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Bool(val v: Boolean, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Var(val name: String, val className: String, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class Constructor(val fullName: String, val arity: Int, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class LocalVar(val name: String, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class SetLocalVar(val name: String, val exp: Expr, override val type: Clazz) : Expr(type, exp.span)
    data class Lambda(
        val binder: String,
        val body: Expr,
        var internalName: String = "",
        var locals: List<LocalVar> = listOf(),
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class App(val fn: Expr, val arg: Expr, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class CtorApp(val ctor: Constructor, val args: List<Expr>, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class If(
        val conds: List<Pair<Expr, Expr>>,
        val elseCase: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class Let(
        val binder: String,
        val bindExpr: Expr,
        val body: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class Do(val exps: List<Expr>, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class ConstructorAccess(
        val fullName: String,
        val field: Int,
        val ctor: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class OperatorApp(
        val name: String,
        val operands: List<Expr>,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class InstanceOf(val exp: Expr, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class NativeFieldGet(val field: Field, val thisPar: Expr, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class NativeStaticFieldGet(val field: Field, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class NativeFieldSet(
        val field: Field,
        val thisPar: Expr,
        val par: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class NativeStaticFieldSet(
        val field: Field,
        val par: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class NativeMethod(
        val method: Method,
        val thisPar: Expr,
        val pars: List<Expr>,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class NativeStaticMethod(
        val method: Method,
        val pars: List<Expr>,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class NativeCtor(
        val ctor: JConstructor<*>,
        val pars: List<Expr>,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class Unit(override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Throw(val expr: Expr, override val span: Span) : Expr(expr.type, span)
    data class Cast(val expr: Expr, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class RecordEmpty(override val type: Clazz, override val span: Span) : Expr(type, span)
    data class RecordExtend(
        val labels: LabelMap<Expr>,
        val expr: Expr,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class RecordSelect(val expr: Expr, val label: String, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class RecordRestrict(val expr: Expr, val label: String, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class RecordUpdate(
        val expr: Expr,
        val label: String,
        val value: Expr,
        val isSet: Boolean,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class RecordMerge(val exp1: Expr, val exp2: Expr, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class ListLiteral(val exps: List<Expr>, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class SetLiteral(val exps: List<Expr>, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class ArrayLiteral(val exps: List<Expr>, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class TryCatch(
        val tryExpr: Expr,
        val catches: List<Catch>,
        val finallyExp: Expr?,
        override val type: Clazz,
        override val span: Span
    ) : Expr(type, span)

    data class While(val cond: Expr, val exps: List<Expr>, override val type: Clazz, override val span: Span) :
        Expr(type, span)

    data class Null(override val type: Clazz, override val span: Span) : Expr(type, span)
    data class ArrayLength(val expr: Expr, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class ClassConstant(val clazz: String, override val type: Clazz, override val span: Span) : Expr(type, span)
    data class Return(val exp: Expr) : Expr(exp.type, exp.span)
    data class Unbox(val exp: Expr, override val type: Clazz) : Expr(type, exp.span)

    fun isPrimitive(): Boolean =
        this is Int32 || this is Bool || this is CharE || this is Int64 || this is Float64
                || this is Float32 || this is ByteE || this is Int16
}

data class Catch(val exception: Clazz, val binder: String?, val expr: Expr, val span: Span) {
    var labels: Pair<Label, Label>? = null
}

/**
 * Visit every expression in this AST (bottom->up)
 */
fun Expr.everywhere(f: (Expr) -> Expr): Expr {
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
        is Expr.RecordUpdate -> f(e.copy(expr = go(e.expr), value = go(e.value)))
        is Expr.RecordMerge -> f(e.copy(exp1 = go(e.exp1), exp2 = go(e.exp2)))
        is Expr.ListLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.SetLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.ArrayLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.TryCatch -> {
            val tryy = go(e.tryExpr)
            val cs = e.catches.map { it.copy(expr = go(it.expr)) }
            f(e.copy(tryExpr = tryy, catches = cs, finallyExp = e.finallyExp?.let(::go)))
        }
        is Expr.While -> f(e.copy(cond = go(e.cond), exps = e.exps.map(::go)))
        is Expr.ArrayLength -> f(e.copy(expr = go(e.expr)))
        is Expr.Return -> f(e.copy(exp = go(e.exp)))
        is Expr.Unbox -> f(e.copy(exp = go(e.exp)))
        is Expr.SetLocalVar -> f(e.copy(exp = go(e.exp)))
        is Expr.ConstructorAccess -> f(e.copy(ctor = go(e.ctor)))
        is Expr.ByteE, is Expr.Int16, is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64, is Expr.LocalVar,
        is Expr.StringE, is Expr.CharE, is Expr.Bool, is Expr.Constructor, is Expr.Null, is Expr.Var,
        is Expr.RecordEmpty, is Expr.Unit, is Expr.NativeStaticFieldGet, is Expr.ClassConstant -> f(e)
    }
    return go(this)
}

fun Expr.everywherUnit(f: (Expr) -> Unit) {
    fun go(e: Expr) {
        when (e) {
            is Expr.Lambda -> {
                f(e)
                go(e.body)
            }
            is Expr.App -> {
                f(e)
                go(e.fn)
                go(e.arg)
            }
            is Expr.CtorApp -> {
                f(e)
                go(e.ctor)
                e.args.forEach(::go)
            }
            is Expr.If -> {
                f(e)
                e.conds.forEach {
                    go(it.first)
                    go(it.second)
                }
                go(e.elseCase)
            }
            is Expr.Let -> {
                f(e)
                go(e.bindExpr)
                go(e.body)
            }
            is Expr.Do -> {
                f(e)
                e.exps.forEach(::go)
            }
            is Expr.OperatorApp -> {
                f(e)
                e.operands.forEach(::go)
            }
            is Expr.InstanceOf -> {
                f(e)
                go(e.exp)
            }
            is Expr.NativeFieldGet -> {
                f(e)
                go(e.thisPar)
            }
            is Expr.NativeFieldSet -> {
                f(e)
                go(e.thisPar)
                go(e.par)
            }
            is Expr.NativeStaticFieldSet -> {
                f(e)
                go(e.par)
            }
            is Expr.NativeCtor -> {
                f(e)
                e.pars.forEach(::go)
            }
            is Expr.NativeMethod -> {
                f(e)
                go(e.thisPar)
                e.pars.forEach(::go)
            }
            is Expr.NativeStaticMethod -> {
                f(e)
                e.pars.forEach(::go)
            }
            is Expr.Throw -> {
                f(e)
                go(e.expr)
            }
            is Expr.Cast -> {
                f(e)
                go(e.expr)
            }
            is Expr.RecordExtend -> {
                f(e)
                e.labels.forEach { it.value().forEach(::go) }
                go(e.expr)
            }
            is Expr.RecordSelect -> {
                f(e)
                go(e.expr)
            }
            is Expr.RecordRestrict -> {
                f(e)
                go(e.expr)
            }
            is Expr.RecordUpdate -> {
                f(e)
                go(e.expr)
                go(e.value)
            }
            is Expr.RecordMerge -> {
                f(e)
                go(e.exp1)
                go(e.exp2)
            }
            is Expr.ListLiteral -> {
                f(e)
                e.exps.forEach(::go)
            }
            is Expr.SetLiteral -> {
                f(e)
                e.exps.forEach(::go)
            }
            is Expr.ArrayLiteral -> {
                f(e)
                e.exps.forEach(::go)
            }
            is Expr.TryCatch -> {
                f(e)
                go(e.tryExpr)
                e.catches.forEach { go(it.expr) }
                if (e.finallyExp != null) go(e.finallyExp)
            }
            is Expr.While -> {
                f(e)
                go(e.cond)
                e.exps.forEach(::go)
            }
            is Expr.ArrayLength -> {
                f(e)
                go(e.expr)
            }
            is Expr.Return -> {
                f(e)
                go(e.exp)
            }
            is Expr.Unbox -> {
                f(e)
                go(e.exp)
            }
            is Expr.SetLocalVar -> {
                f(e)
                go(e.exp)
            }
            is Expr.Bool, is Expr.ByteE, is Expr.Int16, is Expr.Int32, is Expr.Int64, is Expr.Float32 -> f(e)
            is Expr.Float64, is Expr.CharE, is Expr.StringE, is Expr.Var, is Expr.LocalVar -> f(e)
            is Expr.ClassConstant, is Expr.Constructor, is Expr.ConstructorAccess, is Expr.Null -> f(e)
            is Expr.NativeStaticFieldGet, is Expr.RecordEmpty, is Expr.Unit -> f(e)
        }
    }
    go(this)
}

fun nestLets(binds: List<Pair<String, Expr>>, body: Expr, type: Clazz): Expr = when {
    binds.isEmpty() -> body
    else -> {
        val (name, exp) = binds[0]
        Expr.Let(name, exp, nestLets(binds.drop(1), body, type), type, body.span)
    }
}

data class Clazz(val type: Type, val pars: List<Clazz> = emptyList(), val labels: LabelMap<Clazz>? = null) {
    fun isInt32() = type.sort == 5
    fun isInt64() = type.sort == 7
    fun isFunction() = type.className == "novah.function.Function"
    fun isString() = type.className == "java.lang.String"
    fun isArray() = type.descriptor.startsWith("[")
    fun isList() = type.className == "io.lacuna.bifurcan.List"
    fun isSet() = type.className == "io.lacuna.bifurcan.Set"
    fun isMap() = type.className == "io.lacuna.bifurcan.Map"

    override fun toString(): String {
        var ty = type.className
        if (pars.isNotEmpty()) ty += pars.joinToString(prefix = "<", postfix = ">")
        if (labels != null && !labels.isEmpty())
            ty += "{" + labels.show { l, clazz -> "$l: ${clazz.type.className}" } + "}"
        return ty
    }
}

fun Expr.Var.fullname() = "$className.$name"
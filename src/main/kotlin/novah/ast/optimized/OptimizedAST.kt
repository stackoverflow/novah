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
import novah.frontend.Span
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

sealed class Expr(open val type: Clazz) {
    data class ByteE(val v: Byte, override val type: Clazz) : Expr(type)
    data class ShortE(val v: Short, override val type: Clazz) : Expr(type)
    data class IntE(val v: Int, override val type: Clazz) : Expr(type)
    data class LongE(val v: Long, override val type: Clazz) : Expr(type)
    data class FloatE(val v: Float, override val type: Clazz) : Expr(type)
    data class DoubleE(val v: Double, override val type: Clazz) : Expr(type)
    data class StringE(val v: String, override val type: Clazz) : Expr(type)
    data class CharE(val v: Char, override val type: Clazz) : Expr(type)
    data class Bool(val v: Boolean, override val type: Clazz) : Expr(type)
    data class Var(val name: String, val className: String, override val type: Clazz) : Expr(type)
    data class Constructor(val fullName: String, val arity: Int, override val type: Clazz) : Expr(type)
    data class LocalVar(val name: String, override val type: Clazz) : Expr(type)
    data class Lambda(
        val binder: String,
        val body: Expr,
        var internalName: String = "",
        var locals: List<LocalVar> = listOf(),
        override val type: Clazz
    ) : Expr(type)

    data class App(val fn: Expr, val arg: Expr, override val type: Clazz) : Expr(type)
    data class CtorApp(val ctor: Constructor, val args: List<Expr>, override val type: Clazz) : Expr(type)
    data class If(val conds: List<Pair<Expr, Expr>>, val elseCase: Expr, override val type: Clazz) : Expr(type)
    data class Let(val binder: String, val bindExpr: Expr, val body: Expr, override val type: Clazz) : Expr(type)
    data class Do(val exps: List<Expr>, override val type: Clazz) : Expr(type)
    data class ConstructorAccess(val fullName: String, val field: Int, val ctor: Expr, override val type: Clazz) :
        Expr(type)

    data class OperatorApp(val name: String, val operands: List<Expr>, override val type: Clazz) : Expr(type)
    data class InstanceOf(val exp: Expr, override val type: Clazz) : Expr(type)
    data class NativeFieldGet(val field: Field, val thisPar: Expr, override val type: Clazz) : Expr(type)
    data class NativeStaticFieldGet(val field: Field, override val type: Clazz) : Expr(type)
    data class NativeFieldSet(val field: Field, val thisPar: Expr, val par: Expr, override val type: Clazz) : Expr(type)
    data class NativeStaticFieldSet(val field: Field, val par: Expr, override val type: Clazz) : Expr(type)
    data class NativeMethod(val method: Method, val thisPar: Expr, val pars: List<Expr>, override val type: Clazz) :
        Expr(type)

    data class NativeStaticMethod(val method: Method, val pars: List<Expr>, override val type: Clazz) : Expr(type)
    data class NativeCtor(val ctor: JConstructor<*>, val pars: List<Expr>, override val type: Clazz) : Expr(type)
    data class Unit(override val type: Clazz) : Expr(type)
    data class Throw(val expr: Expr) : Expr(expr.type)
    data class Cast(val expr: Expr, override val type: Clazz) : Expr(type)
    data class RecordEmpty(override val type: Clazz) : Expr(type)
    data class RecordExtend(val labels: LabelMap<Expr>, val expr: Expr, override val type: Clazz) : Expr(type)
    data class RecordSelect(val expr: Expr, val label: String, override val type: Clazz) : Expr(type)
    data class RecordRestrict(val expr: Expr, val label: String, override val type: Clazz) : Expr(type)
    data class VectorLiteral(val exps: List<Expr>, override val type: Clazz) : Expr(type)
    data class SetLiteral(val exps: List<Expr>, override val type: Clazz) : Expr(type)
}

fun nestLets(binds: List<Pair<String, Expr>>, body: Expr, type: Clazz): Expr = when {
    binds.isEmpty() -> body
    else -> {
        val (name, exp) = binds[0]
        Expr.Let(name, exp, nestLets(binds.drop(1), body, type), type)
    }
}

data class Clazz(val type: Type, val pars: List<Clazz> = emptyList(), val labels: LabelMap<Clazz>? = null)

fun Expr.Var.fullname() = "$className.$name"
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
package novah.ast.canonical

import novah.ast.source.Visibility
import novah.data.LabelMap
import novah.data.mapList
import novah.frontend.Span
import novah.frontend.hmftypechecker.Env
import novah.frontend.hmftypechecker.Id
import novah.frontend.hmftypechecker.Type
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor as JConstructor

/**
 * The canonical AST used after desugaring for type checking.
 */

data class Module(
    val name: String,
    val sourceName: String,
    val decls: List<Decl>
)

sealed class Decl {
    data class DataDecl(
        val name: String,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        val span: Span,
        val visibility: Visibility
    ) : Decl()

    data class ValDecl(
        val name: String,
        val exp: Expr,
        val recursive: Boolean,
        val span: Span,
        val type: Type?,
        val visibility: Visibility
    ) : Decl()
}

data class DataConstructor(val name: String, val args: List<Type>, val visibility: Visibility, val span: Span) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr(open val span: Span) {
    data class IntE(val v: Int, override val span: Span) : Expr(span)
    data class LongE(val v: Long, override val span: Span) : Expr(span)
    data class FloatE(val v: Float, override val span: Span) : Expr(span)
    data class DoubleE(val v: Double, override val span: Span) : Expr(span)
    data class StringE(val v: String, override val span: Span) : Expr(span)
    data class CharE(val v: Char, override val span: Span) : Expr(span)
    data class Bool(val v: Boolean, override val span: Span) : Expr(span)
    data class Var(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Constructor(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class ImplicitVar(val name: String, override val span: Span) : Expr(span)
    data class Lambda(
        val pattern: FunparPattern,
        val ann: Pair<List<Id>, Type>?,
        val body: Expr,
        override val span: Span
    ) : Expr(span)

    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span) {
        val implicitContexts = mutableListOf<ImplicitContext>()
    }

    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exp: Expr, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val annType: Pair<List<Id>, Type>, override val span: Span) : Expr(span)
    data class Do(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class NativeFieldGet(val name: String, val field: Field, val isStatic: Boolean, override val span: Span) :
        Expr(span)

    data class NativeFieldSet(val name: String, val field: Field, val isStatic: Boolean, override val span: Span) :
        Expr(span)

    data class NativeMethod(val name: String, val method: Method, val isStatic: Boolean, override val span: Span) :
        Expr(span)

    data class NativeConstructor(val name: String, val ctor: JConstructor<*>, override val span: Span) : Expr(span)
    data class Unit(override val span: Span) : Expr(span)
    class RecordEmpty(span: Span) : Expr(span)
    data class RecordSelect(val exp: Expr, val label: String, override val span: Span) : Expr(span)
    data class RecordExtend(val labels: LabelMap<Expr>, val exp: Expr, override val span: Span) : Expr(span)
    data class RecordRestrict(val exp: Expr, val label: String, override val span: Span) : Expr(span)
    data class VectorLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class SetLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)

    var type: Type? = null

    fun withType(t: Type): Type {
        type = t
        return t
    }
}

class ImplicitContext(val type: Type, val env: Env, var resolved: Expr? = null)

fun Expr.Var.fullname() = if (moduleName != null) "$moduleName.$name" else name
fun Expr.Constructor.fullname() = if (moduleName != null) "$moduleName.$name" else name
fun Expr.Constructor.fullname(module: String): String = "$module.$name"

data class Binder(val name: String, val span: Span, val isImplicit: Boolean = false) {
    override fun toString(): String = if (isImplicit) "{{$name}}" else name
}

sealed class FunparPattern(val span: Span) {
    class Ignored(span: Span) : FunparPattern(span)
    class Unit(span: Span) : FunparPattern(span)
    class Bind(val binder: Binder) : FunparPattern(binder.span)
}

data class LetDef(val binder: Binder, val expr: Expr, val recursive: Boolean, val type: Type? = null)

data class Case(val pattern: Pattern, val exp: Expr)

sealed class Pattern(open val span: Span) {
    data class Wildcard(override val span: Span) : Pattern(span)
    data class LiteralP(val lit: LiteralPattern, override val span: Span) : Pattern(span)
    data class Var(val name: String, override val span: Span) : Pattern(span)
    data class Ctor(val ctor: Expr.Constructor, val fields: List<Pattern>, override val span: Span) : Pattern(span)
}

sealed class LiteralPattern(open val e: Expr) {
    data class BoolLiteral(override val e: Expr.Bool) : LiteralPattern(e)
    data class CharLiteral(override val e: Expr.CharE) : LiteralPattern(e)
    data class StringLiteral(override val e: Expr.StringE) : LiteralPattern(e)
    data class IntLiteral(override val e: Expr.IntE) : LiteralPattern(e)
    data class LongLiteral(override val e: Expr.LongE) : LiteralPattern(e)
    data class FloatLiteral(override val e: Expr.FloatE) : LiteralPattern(e)
    data class DoubleLiteral(override val e: Expr.DoubleE) : LiteralPattern(e)
}

fun Pattern.show(): String = when (this) {
    is Pattern.Wildcard -> "_"
    is Pattern.Var -> name
    is Pattern.Ctor -> if (fields.isEmpty()) ctor.name else "${ctor.name} " + fields.joinToString(" ") { it.show() }
    is Pattern.LiteralP -> lit.show()
}

fun LiteralPattern.show(): String = when (this) {
    is LiteralPattern.BoolLiteral -> e.v.toString()
    is LiteralPattern.CharLiteral -> e.v.toString()
    is LiteralPattern.StringLiteral -> e.v
    is LiteralPattern.IntLiteral -> e.v.toString()
    is LiteralPattern.LongLiteral -> e.v.toString()
    is LiteralPattern.FloatLiteral -> e.v.toString()
    is LiteralPattern.DoubleLiteral -> e.v.toString()
}

fun lambdaBinder(name: String, span: Span) = FunparPattern.Bind(Binder(name, span))

/**
 * Walks this expression bottom->up
 */
fun Expr.everywhere(f: (Expr) -> Expr): Expr {
    fun go(e: Expr): Expr = when (e) {
        is Expr.Lambda -> f(e.copy(body = go(e.body)))
        is Expr.App -> f(e.copy(fn = go(e.fn), arg = go(e.arg)))
        is Expr.If -> f(e.copy(cond = go(e.cond), thenCase = go(e.thenCase), elseCase = go(e.elseCase)))
        is Expr.Let -> f(e.copy(letDef = e.letDef.copy(expr = go(e.letDef.expr)), body = go(e.body)))
        is Expr.Match -> f(e.copy(exp = go(e.exp), cases = e.cases.map { it.copy(exp = go(it.exp)) }))
        is Expr.Ann -> f(e.copy(exp = go(e.exp)))
        is Expr.Do -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.RecordSelect -> f(e.copy(exp = go(e.exp)))
        is Expr.RecordRestrict -> f(e.copy(exp = go(e.exp)))
        is Expr.RecordExtend -> f(e.copy(exp = go(e.exp), labels = e.labels.mapList(::go)))
        else -> f(e)
    }
    return go(this)
}

fun Expr.substVar(from: String, to: String): Expr = everywhere { e ->
    if (e is Expr.Var && e.name == from) e.copy(name = to) else e
}

fun <T> Expr.everywhereAccumulating(f: (Expr) -> List<T>): List<T> {
    val acc = mutableListOf<T>()
    everywhere { exp ->
        acc.addAll(f(exp))
        exp
    }
    return acc
}
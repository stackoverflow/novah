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
import novah.data.show
import novah.frontend.Span
import novah.frontend.typechecker.Env
import novah.frontend.typechecker.Type
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor as JConstructor

/**
 * The canonical AST used after desugaring for type checking.
 */

data class Module(
    val name: String,
    val sourceName: String,
    val decls: List<Decl>,
    val unusedImports: Map<String, Span>
)

sealed class Decl {
    data class TypeDecl(
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
        val visibility: Visibility,
        val isInstance: Boolean,
        val isOperator: Boolean
    ) : Decl()
}

data class DataConstructor(val name: String, val args: List<Type>, val visibility: Visibility, val span: Span) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr(open val span: Span) {
    data class Int32(val v: Int, override val span: Span) : Expr(span)
    data class Int64(val v: Long, override val span: Span) : Expr(span)
    data class Float32(val v: Float, override val span: Span) : Expr(span)
    data class Float64(val v: Double, override val span: Span) : Expr(span)
    data class StringE(val v: String, override val span: Span) : Expr(span)
    data class CharE(val v: Char, override val span: Span) : Expr(span)
    data class Bool(val v: Boolean, override val span: Span) : Expr(span)
    data class Var(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Constructor(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class ImplicitVar(val name: String, override val span: Span, val moduleName: String?) : Expr(span)
    data class Lambda(val binder: Binder, val body: Expr, override val span: Span) : Expr(span)
    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exps: List<Expr>, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val annType: Type, override val span: Span) : Expr(span)
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
    data class RecordUpdate(val exp: Expr, val label: String, val value: Expr, override val span: Span) : Expr(span)
    data class ListLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class SetLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class Throw(val exp: Expr, override val span: Span) : Expr(span)
    data class TryCatch(val tryExp: Expr, val cases: List<Case>, val finallyExp: Expr?, override val span: Span) :
        Expr(span)

    data class While(val cond: Expr, val exps: List<Expr>, override val span: Span) : Expr(span)

    // end of subclasses

    var implicitContext: ImplicitContext? = null

    var type: Type? = null

    fun withType(t: Type): Type {
        type = t
        return t
    }
}

class ImplicitContext(val types: List<Type>, val env: Env, val resolveds: MutableList<Expr> = mutableListOf())

fun Expr.Var.fullname() = if (moduleName != null) "$moduleName.$name" else name
fun Expr.ImplicitVar.fullname() = if (moduleName != null) "$moduleName.$name" else name
fun Expr.Constructor.fullname() = if (moduleName != null) "$moduleName.$name" else name
fun Expr.Constructor.fullname(module: String): String = "$module.$name"

data class Binder(val name: String, val span: Span, val isImplicit: Boolean = false) {
    override fun toString(): String = if (isImplicit) "{{$name}}" else name
}

data class LetDef(
    val binder: Binder,
    val expr: Expr,
    val recursive: Boolean,
    val isInstance: Boolean
)

data class Case(val patterns: List<Pattern>, val exp: Expr, val guard: Expr? = null) {
    fun patternSpan() = Span.new(patterns[0].span, patterns.last().span)
}

sealed class Pattern(open val span: Span) {
    data class Wildcard(override val span: Span) : Pattern(span)
    data class LiteralP(val lit: LiteralPattern, override val span: Span) : Pattern(span)
    data class Var(val name: String, override val span: Span) : Pattern(span)
    data class Ctor(val ctor: Expr.Constructor, val fields: List<Pattern>, override val span: Span) : Pattern(span)
    data class Record(val labels: LabelMap<Pattern>, override val span: Span) : Pattern(span)
    data class ListP(val elems: List<Pattern>, override val span: Span) : Pattern(span)
    data class ListHeadTail(val head: Pattern, val tail: Pattern, override val span: Span) : Pattern(span)
    data class Named(val pat: Pattern, val name: String, override val span: Span) : Pattern(span)
    data class Unit(override val span: Span) : Pattern(span)
    data class TypeTest(val type: Type, val alias: String?, override val span: Span) : Pattern(span)
}

sealed class LiteralPattern(open val e: Expr) {
    data class BoolLiteral(override val e: Expr.Bool) : LiteralPattern(e)
    data class CharLiteral(override val e: Expr.CharE) : LiteralPattern(e)
    data class StringLiteral(override val e: Expr.StringE) : LiteralPattern(e)
    data class Int32Literal(override val e: Expr.Int32) : LiteralPattern(e)
    data class Int64Literal(override val e: Expr.Int64) : LiteralPattern(e)
    data class Float32Literal(override val e: Expr.Float32) : LiteralPattern(e)
    data class Float64Literal(override val e: Expr.Float64) : LiteralPattern(e)
}

fun Pattern.show(): String = when (this) {
    is Pattern.Wildcard -> "_"
    is Pattern.Var -> name
    is Pattern.Ctor -> if (fields.isEmpty()) ctor.name else "${ctor.name} " + fields.joinToString(" ") { it.show() }
    is Pattern.LiteralP -> lit.show()
    is Pattern.Record -> "{ " + labels.show { l, e -> "$l: ${e.show()}" } + " }"
    is Pattern.ListP -> "[${elems.joinToString { it.show() }}]"
    is Pattern.ListHeadTail -> "[${head.show()} :: ${tail.show()}]"
    is Pattern.Named -> "${pat.show()} as $name"
    is Pattern.Unit -> "()"
    is Pattern.TypeTest -> ":? ${type.show()}" + if (alias != null) " $alias" else ""
}

fun LiteralPattern.show(): String = when (this) {
    is LiteralPattern.BoolLiteral -> e.v.toString()
    is LiteralPattern.CharLiteral -> e.v.toString()
    is LiteralPattern.StringLiteral -> e.v
    is LiteralPattern.Int32Literal -> e.v.toString()
    is LiteralPattern.Int64Literal -> e.v.toString()
    is LiteralPattern.Float32Literal -> e.v.toString()
    is LiteralPattern.Float64Literal -> e.v.toString()
}

/**
 * Walks this expression bottom->up
 */
fun Expr.everywhere(f: (Expr) -> Expr): Expr {
    fun go(e: Expr): Expr = when (e) {
        is Expr.Lambda -> f(e.copy(body = go(e.body)))
        is Expr.App -> f(e.copy(fn = go(e.fn), arg = go(e.arg)))
        is Expr.If -> f(e.copy(cond = go(e.cond), thenCase = go(e.thenCase), elseCase = go(e.elseCase)))
        is Expr.Let -> f(e.copy(letDef = e.letDef.copy(expr = go(e.letDef.expr)), body = go(e.body)))
        is Expr.Match -> f(e.copy(exps = e.exps.map(::go), cases = e.cases.map {
            it.copy(exp = go(it.exp), guard = it.guard?.let { g -> go(g) })
        }))
        is Expr.Ann -> f(e.copy(exp = go(e.exp)))
        is Expr.Do -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.RecordSelect -> f(e.copy(exp = go(e.exp)))
        is Expr.RecordRestrict -> f(e.copy(exp = go(e.exp)))
        is Expr.RecordUpdate -> f(e.copy(exp = go(e.exp), value = go(e.value)))
        is Expr.RecordExtend -> f(e.copy(exp = go(e.exp), labels = e.labels.mapList(::go)))
        is Expr.ListLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.SetLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.Throw -> f(e.copy(exp = go(e.exp)))
        is Expr.TryCatch -> {
            val cases = e.cases.map {
                it.copy(exp = go(it.exp), guard = it.guard?.let { g -> go(g) })
            }
            f(e.copy(tryExp = go(e.tryExp), finallyExp = e.finallyExp?.let { go(it) }, cases = cases))
        }
        is Expr.While -> f(e.copy(cond = go(e.cond), exps = e.exps.map(::go)))
        else -> f(e)
    }
    return go(this)
}

fun <T> Expr.everywhereAccumulating(f: (Expr) -> List<T>): List<T> {
    val acc = mutableListOf<T>()
    everywhere { exp ->
        acc.addAll(f(exp))
        exp
    }
    return acc
}

fun Expr.resolvedImplicits(): List<Expr> = implicitContext?.resolveds ?: emptyList()

fun Expr.show(): String = when (this) {
    is Expr.Int32 -> "$v"
    is Expr.Int64 -> "$v"
    is Expr.Float32 -> "$v"
    is Expr.Float64 -> "$v"
    is Expr.StringE -> "\"$v\""
    is Expr.CharE -> "'$v'"
    is Expr.Bool -> "$v"
    is Expr.Var -> fullname()
    is Expr.Constructor -> fullname()
    is Expr.ImplicitVar -> fullname()
    is Expr.Lambda -> "\\${binder} -> ${body.show()}"
    is Expr.App -> "(${fn.show()} ${arg.show()})"
    is Expr.If -> "if ${cond.show()} then ${thenCase.show()} else ${elseCase.show()}"
    is Expr.Let -> "let ${letDef.binder} = ${letDef.expr.show()} in ${body.show()}"
    is Expr.Match -> {
        val cs = cases.joinToString("\n  ") { cs ->
            val guard = if (cs.guard != null) " if " + cs.guard.show() else ""
            cs.patterns.joinToString { it.show() } + guard + " -> " + cs.exp.show()
        }
        "case ${exps.joinToString { it.show() }} of\n  $cs"
    }
    is Expr.Ann -> "${exp.show()} : ${annType.show()}"
    is Expr.Do -> "\n  " + exps.joinToString("\n  ") { it.show() }
    is Expr.NativeFieldGet -> name
    is Expr.NativeFieldSet -> name
    is Expr.NativeMethod -> name
    is Expr.NativeConstructor -> name
    is Expr.Unit -> "()"
    is Expr.RecordEmpty -> "{}"
    is Expr.RecordSelect -> "${exp.show()}.$label"
    is Expr.RecordRestrict -> "{ - $label | ${exp.show()} }"
    is Expr.RecordUpdate -> "{ .$label = ${value.show()} | ${exp.show()} }"
    is Expr.RecordExtend -> {
        val rest = if (exp is Expr.RecordEmpty) "" else " | ${exp.show()} "
        "{ ${labels.show { s, expr -> "$s: ${expr.show()}" }} $rest}"
    }
    is Expr.ListLiteral -> "[${exps.joinToString { it.show() }}]"
    is Expr.SetLiteral -> "#{${exps.joinToString { it.show() }}}"
    is Expr.Throw -> "throw ${exp.show()}"
    is Expr.TryCatch -> {
        val cs = cases.joinToString("\n  ") { cs ->
            val guard = if (cs.guard != null) " if " + cs.guard.show() else ""
            cs.patterns.joinToString { it.show() } + guard + " -> " + cs.exp.show()
        }
        val fin = if (finallyExp != null) "\nfinally ${finallyExp.show()}" else ""
        "try ${tryExp.show()}\ncatch\n  $cs$fin"
    }
    is Expr.While -> "while ${cond.show()} do\n  " + exps.joinToString("\n  ") { it.show() }
}
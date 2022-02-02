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

import novah.ast.source.ForeignImport
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.data.*
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.typechecker.Env
import novah.frontend.typechecker.Type
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor as JConstructor

/**
 * The canonical AST used after desugaring for type checking.
 */

data class Module(
    val name: Spanned<String>,
    val sourceName: String,
    val decls: List<Decl>,
    val unusedImports: Map<String, Span>,
    val imports: List<Import>,
    val foreigns: List<ForeignImport>,
    val metadata: Metadata?,
    val comment: Comment?
)

sealed class Decl(open val span: Span, open val comment: Comment?) {
    data class TypeDecl(
        val name: Spanned<String>,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        override val span: Span,
        val visibility: Visibility,
        override val comment: Comment? = null
    ) : Decl(span, comment)

    data class ValDecl(
        val name: Spanned<String>,
        val exp: Expr,
        val recursive: Boolean,
        override val span: Span,
        val signature: Signature?,
        val visibility: Visibility,
        val isInstance: Boolean,
        val isOperator: Boolean,
        override val comment: Comment? = null
    ) : Decl(span, comment) {
        var typeError = false
    }

    var metadata: Metadata? = null

    fun isPublic() = when (this) {
        is TypeDecl -> visibility == Visibility.PUBLIC
        is ValDecl -> visibility == Visibility.PUBLIC
    }

    fun rawName() = when (this) {
        is TypeDecl -> name.value
        is ValDecl -> name.value
    }

    fun withMeta(meta: Metadata?) = apply { metadata = meta }
}

data class Signature(val type: Type, val span: Span)

fun Decl.TypeDecl.show(): String {
    return if (tyVars.isEmpty()) name.value else name.value + tyVars.joinToString(" ", prefix = " ")
}

data class DataConstructor(
    val name: Spanned<String>,
    val args: List<Type>,
    val visibility: Visibility,
    val span: Span
) {
    override fun toString(): String {
        return name.value + args.joinToString(" ", prefix = " ")
    }

    fun isPublic() = visibility == Visibility.PUBLIC
}

sealed class Expr(open val span: Span) {
    data class Int32(val v: Int, override val span: Span) : Expr(span)
    data class Int64(val v: Long, override val span: Span) : Expr(span)
    data class Float32(val v: Float, override val span: Span) : Expr(span)
    data class Float64(val v: Double, override val span: Span) : Expr(span)
    data class StringE(val v: String, override val span: Span) : Expr(span)
    data class CharE(val v: Char, override val span: Span) : Expr(span)
    data class Bool(val v: Boolean, override val span: Span) : Expr(span)
    data class Var(
        val name: String,
        override val span: Span,
        val moduleName: String? = null,
        val isOp: Boolean = false
    ) : Expr(span)

    data class Constructor(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class ImplicitVar(val name: String, override val span: Span, val moduleName: String?) : Expr(span)
    data class Lambda(val binder: Binder, val body: Expr, override val span: Span) : Expr(span)
    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exps: List<Expr>, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val annType: Type, override val span: Span) : Expr(span)
    data class Do(val exps: List<Expr>, override val span: Span) : Expr(span)
    class Unit(span: Span) : Expr(span)
    class RecordEmpty(span: Span) : Expr(span)
    data class RecordSelect(val exp: Expr, val label: Spanned<String>, override val span: Span) : Expr(span)
    data class RecordExtend(val labels: LabelMap<Expr>, val exp: Expr, override val span: Span) : Expr(span)
    data class RecordRestrict(val exp: Expr, val label: String, override val span: Span) : Expr(span)
    data class RecordUpdate(
        val exp: Expr,
        val label: Spanned<String>,
        val value: Expr,
        val isSet: Boolean,
        override val span: Span
    ) : Expr(span)

    data class RecordMerge(val exp1: Expr, val exp2: Expr, override val span: Span) : Expr(span)
    data class ListLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class SetLiteral(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class Index(val exp: Expr, val index: Expr, override val span: Span) : Expr(span) {
        var method: Method? = null
    }

    data class Throw(val exp: Expr, override val span: Span) : Expr(span)
    data class TryCatch(val tryExp: Expr, val cases: List<Case>, val finallyExp: Expr?, override val span: Span) :
        Expr(span)

    data class While(val cond: Expr, val exps: List<Expr>, override val span: Span) : Expr(span)
    class Null(span: Span) : Expr(span)
    data class TypeCast(val exp: Expr, val cast: Type, override val span: Span) : Expr(span)
    data class ClassConstant(val clazz: Spanned<String>, override val span: Span) : Expr(span)
    data class ForeignField(val exp: Expr, val fieldName: Spanned<String>, override val span: Span) : Expr(span) {
        var field: Field? = null
    }

    data class ForeignStaticField(val clazz: Spanned<String>, val fieldName: Spanned<String>, override val span: Span) :
        Expr(span) {
        var field: Field? = null
    }

    data class ForeignFieldSetter(val field: ForeignField, val value: Expr, override val span: Span) : Expr(span)
    data class ForeignStaticFieldSetter(val field: ForeignStaticField, val value: Expr, override val span: Span) :
        Expr(span)

    data class ForeignStaticMethod(
        val clazz: Spanned<String>,
        val methodName: Spanned<String>,
        val args: List<Expr>,
        override val span: Span
    ) : Expr(span) {
        var method: Method? = null
        var ctor: JConstructor<*>? = null
    }

    data class ForeignMethod(
        val exp: Expr,
        val methodName: Spanned<String>,
        val args: List<Expr>,
        override val span: Span
    ) : Expr(span) {
        var method: Method? = null
    }

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

    var type: Type? = null
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
    data class Var(val v: Expr.Var) : Pattern(v.span)
    data class Ctor(val ctor: Expr.Constructor, val fields: List<Pattern>, override val span: Span) : Pattern(span)
    data class Record(val labels: LabelMap<Pattern>, override val span: Span) : Pattern(span)
    data class ListP(val elems: List<Pattern>, val tail: Pattern?, override val span: Span) : Pattern(span)
    data class Named(val pat: Pattern, val name: Spanned<String>, override val span: Span) : Pattern(span)
    data class Unit(override val span: Span) : Pattern(span)
    data class TypeTest(val test: Type, val alias: String?, override val span: Span) : Pattern(span)
    data class Regex(val regex: String, override val span: Span) : Pattern(span)

    var type: Type? = null
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
    is Pattern.Var -> v.name
    is Pattern.Ctor -> if (fields.isEmpty()) ctor.name else "${ctor.name} " + fields.joinToString(" ") { it.show() }
    is Pattern.LiteralP -> lit.show()
    is Pattern.Record -> "{ " + labels.show { l, e -> "$l: ${e.show()}" } + " }"
    is Pattern.ListP -> {
        val tail = if (tail != null) " :: ${tail.show()}" else ""
        "[${elems.joinToString { it.show() }}$tail]"
    }
    is Pattern.Named -> "${pat.show()} as $name"
    is Pattern.Unit -> "()"
    is Pattern.TypeTest -> ":? ${test.show()}" + if (alias != null) " $alias" else ""
    is Pattern.Regex -> "#\"$regex\""
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

data class Metadata(val data: Expr.RecordExtend) {
    var type: Type? = null

    fun merge(other: Metadata?): Metadata {
        if (other == null) return this

        val newMeta = data.copy(labels = other.data.labels.mergeReplace(data.labels), span = data.span)
        return Metadata(newMeta)
    }

    fun getMeta(attr: String): Expr? {
        val v = data.labels.get(attr)
        return if (v.isPresent) {
            val list = v.get()
            if (!list.isEmpty()) list.nth(0) else null
        } else null
    }

    companion object {
        const val NO_WARN = "noWarn"
        const val DERIVE = "derive"
    }
}

fun Metadata?.isTrue(attr: String): Boolean {
    if (this == null) return false
    val v = getMeta(attr)
    return v != null && v is Expr.Bool && v.v
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
        is Expr.RecordMerge -> f(e.copy(exp1 = go(e.exp1), exp2 = go(e.exp2)))
        is Expr.ListLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.SetLiteral -> f(e.copy(exps = e.exps.map(::go)))
        is Expr.Index -> f(e.copy(exp = go(e.exp), index = go(e.index)))
        is Expr.Throw -> f(e.copy(exp = go(e.exp)))
        is Expr.TryCatch -> {
            val cases = e.cases.map {
                it.copy(exp = go(it.exp), guard = it.guard?.let { g -> go(g) })
            }
            f(e.copy(tryExp = go(e.tryExp), finallyExp = e.finallyExp?.let { go(it) }, cases = cases))
        }
        is Expr.While -> f(e.copy(cond = go(e.cond), exps = e.exps.map(::go)))
        is Expr.TypeCast -> f(e.copy(exp = go(e.exp)))
        is Expr.ForeignStaticField -> f(e)
        is Expr.ForeignField -> f(e.copy(exp = go(e.exp)))
        is Expr.ForeignStaticMethod -> f(e.copy(args = e.args.map(::go)))
        is Expr.ForeignMethod -> f(e.copy(exp = go(e.exp), args = e.args.map(::go)))
        is Expr.ForeignFieldSetter -> f(e.copy(field = go(e.field) as Expr.ForeignField, value = go(e.value)))
        is Expr.ForeignStaticFieldSetter -> {
            f(e.copy(field = go(e.field) as Expr.ForeignStaticField, value = go(e.value)))
        }
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

// goes in the same order as the actual text
// so top -> bottom, left -> right
fun Expr.everywhereUnit(f: (Expr) -> Unit) {
    fun go(e: Expr) {
        when (e) {
            is Expr.Lambda -> {
                f(e)
                go(e.body)
            }
            is Expr.App -> {
                f(e)
                if (e.fn is Expr.Var && e.fn.isOp) {
                    go(e.arg)
                    go(e.fn)
                } else {
                    go(e.fn)
                    go(e.arg)
                }
            }
            is Expr.If -> {
                f(e)
                go(e.cond)
                go(e.thenCase)
                go(e.elseCase)
            }
            is Expr.Let -> {
                f(e)
                go(e.letDef.expr)
                go(e.body)
            }
            is Expr.Match -> {
                f(e)
                e.exps.forEach { go(it) }
                e.cases.forEach { case ->
                    case.patterns.forEach { p ->
                        p.everywhereUnit {
                            when (it) {
                                is Pattern.Ctor -> go(it.ctor)
                                is Pattern.LiteralP -> go(it.lit.e)
                                is Pattern.Var -> go(it.v)
                                else -> {}
                            }
                        }
                    }
                    if (case.guard != null) go(case.guard)
                    go(case.exp)
                }
            }
            is Expr.Ann -> {
                go(e.exp)
                f(e)
            }
            is Expr.Do -> {
                f(e)
                e.exps.forEach { go(it) }
            }
            is Expr.RecordSelect -> {
                f(e)
                go(e.exp)
            }
            is Expr.RecordRestrict -> {
                f(e)
                go(e.exp)
            }
            is Expr.RecordUpdate -> {
                f(e)
                go(e.value)
                go(e.exp)
            }
            is Expr.RecordExtend -> {
                f(e)
                e.labels.forEachList { go(it) }
                go(e.exp)
            }
            is Expr.RecordMerge -> {
                f(e)
                go(e.exp1)
                go(e.exp2)
            }
            is Expr.ListLiteral -> {
                f(e)
                e.exps.forEach { go(it) }
            }
            is Expr.SetLiteral -> {
                f(e)
                e.exps.forEach { go(it) }
            }
            is Expr.Index -> {
                f(e)
                go(e.exp)
                go(e.index)
            }
            is Expr.Throw -> {
                f(e)
                go(e.exp)
            }
            is Expr.TryCatch -> {
                f(e)
                go(e.tryExp)
                e.cases.forEach {
                    if (it.guard != null) go(it.guard)
                    go(it.exp)
                }
                if (e.finallyExp != null) go(e.finallyExp)
            }
            is Expr.While -> {
                f(e)
                go(e.cond)
                e.exps.forEach(::go)
            }
            is Expr.TypeCast -> {
                go(e.exp)
                f(e)
            }
            is Expr.ForeignField -> {
                go(e.exp)
                f(e)
            }
            is Expr.ForeignStaticMethod -> {
                f(e)
                e.args.forEach(::go)
            }
            is Expr.ForeignMethod -> {
                go(e.exp)
                f(e)
                e.args.forEach(::go)
            }
            is Expr.ForeignFieldSetter -> {
                go(e.field)
                f(e)
                go(e.value)
            }
            is Expr.ForeignStaticFieldSetter -> {
                go(e.field)
                f(e)
                go(e.value)
            }
            is Expr.ForeignStaticField -> f(e)
            is Expr.Bool -> f(e)
            is Expr.Int32 -> f(e)
            is Expr.Int64 -> f(e)
            is Expr.Float32 -> f(e)
            is Expr.Float64 -> f(e)
            is Expr.CharE -> f(e)
            is Expr.StringE -> f(e)
            is Expr.Var -> f(e)
            is Expr.Constructor -> f(e)
            is Expr.ImplicitVar -> f(e)
            is Expr.RecordEmpty -> f(e)
            is Expr.Null -> f(e)
            is Expr.Unit -> f(e)
            is Expr.ClassConstant -> f(e)
        }
    }
    go(this)
}

fun Pattern.everywhereUnit(f: (Pattern) -> Unit) {
    fun go(p: Pattern) {
        when (p) {
            is Pattern.Ctor -> {
                f(p)
                p.fields.forEach { go(it) }
            }
            is Pattern.ListP -> {
                f(p)
                p.elems.forEach { go(it) }
                if (p.tail != null) go(p.tail)
            }
            is Pattern.Named -> {
                f(p)
                go(p.pat)
            }
            is Pattern.Record -> {
                f(p)
                p.labels.mapAllValues { it }.sortedWith { a, b ->
                    if (a.span.startLine != b.span.startLine)
                        a.span.startLine.compareTo(b.span.startLine)
                    else a.span.startColumn.compareTo(b.span.startColumn)
                }.forEach { go(it) }
            }
            is Pattern.Unit -> f(p)
            is Pattern.Wildcard -> f(p)
            is Pattern.Var -> f(p)
            is Pattern.LiteralP -> f(p)
            is Pattern.TypeTest -> f(p)
            is Pattern.Regex -> f(p)
        }
    }
    go(this)
}

fun Expr.resolvedImplicits(): List<Expr> = implicitContext?.resolveds ?: emptyList()

/**
 * Returns true if the expression is a lambda with implicit parameters.
 */
fun Expr.hasImplicitVars(): Boolean = when (this) {
    is Expr.Ann -> exp.hasImplicitVars()
    is Expr.Lambda -> binder.isImplicit || body.hasImplicitVars()
    else -> false
}
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
package novah.ast.source

import novah.data.LabelMap
import novah.data.Labels
import novah.data.show
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned

data class Module(
    val name: Spanned<String>,
    val sourceName: String,
    val imports: List<Import>,
    val foreigns: List<ForeignImport>,
    val decls: List<Decl>,
    val span: Span
) {
    var comment: Comment? = null

    var resolvedImports = emptyMap<String, String>()
    var resolvedTypealiases = emptyList<Decl.TypealiasDecl>()

    var foreignTypes = emptyMap<String, String>()

    fun withComment(c: Comment?) = apply { comment = c }
}

enum class Visibility {
    PUBLIC, PRIVATE;

    fun isPublic() = this == PUBLIC
}

/**
 * A reference that can be imported
 */
sealed class DeclarationRef(open val name: String, open val span: Span) {
    data class RefVar(override val name: String, override val span: Span) : DeclarationRef(name, span) {
        override fun toString(): String = name
    }

    /**
     * `null` ctors mean only the type is imported, but no constructors.
     * Empty ctors mean all constructors are imported.
     */
    data class RefType(val binder: Spanned<String>, override val span: Span, val ctors: List<Spanned<String>>? = null) :
        DeclarationRef(binder.value, span) {
        override fun toString(): String = when {
            ctors == null -> name
            ctors.isEmpty() -> "$name(..)"
            else -> name + ctors.joinToString(prefix = "(", postfix = ")") { it.value }
        }
    }
}

sealed class Import(open val module: Spanned<String>) {
    data class Raw(
        override val module: Spanned<String>,
        val span: Span,
        val alias: String? = null,
        val auto: Boolean = false
    ) : Import(module)

    data class Exposing(
        override val module: Spanned<String>,
        val defs: List<DeclarationRef>,
        val span: Span,
        val alias: String? = null
    ) : Import(module)

    fun alias(): String? = when (this) {
        is Raw -> alias
        is Exposing -> alias
    }

    fun span(): Span = when (this) {
        is Raw -> span
        is Exposing -> span
    }

    fun isAuto(): Boolean = when (this) {
        is Exposing -> false
        is Raw -> auto
    }

    var comment: Comment? = null

    fun withComment(c: Comment?) = apply { comment = c }
}

data class ForeignImport(val type: String, val alias: String?, val span: Span) {
    fun name() = alias ?: type.split(".").last()
}

sealed class Decl(val name: String, val visibility: Visibility) {
    class TypeDecl(
        val binder: Spanned<String>,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        visibility: Visibility,
        val isOpaque: Boolean
    ) : Decl(binder.value, visibility)

    class ValDecl(
        val binder: Spanned<String>,
        val patterns: List<Pattern>,
        val exp: Expr,
        val signature: Signature?,
        visibility: Visibility,
        val isInstance: Boolean,
        val isOperator: Boolean
    ) : Decl(binder.value, visibility)

    class TypealiasDecl(name: String, val tyVars: List<String>, val type: Type, visibility: Visibility) :
        Decl(name, visibility) {
        var expanded: Type? = null
        val freeVars = mutableSetOf<String>()
    }

    var comment: Comment? = null
    var span = Span.empty()

    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }
}

data class Signature(val type: Type, val span: Span)

data class DataConstructor(
    val name: Spanned<String>,
    val args: List<Type>,
    val visibility: Visibility,
    val span: Span
) {
    override fun toString(): String {
        return name.value + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr {
    data class Int32(val v: Int, val text: String) : Expr()
    data class Int64(val v: Long, val text: String) : Expr()
    data class Float32(val v: Float, val text: String) : Expr()
    data class Float64(val v: Double, val text: String) : Expr()
    data class StringE(val v: String, val raw: String, val multi: Boolean = false) : Expr()
    data class CharE(val v: Char, val raw: String) : Expr()
    data class Bool(val v: Boolean) : Expr()
    data class Var(val name: String, val alias: String? = null) : Expr()
    data class Operator(val name: String, val isPrefix: Boolean, val alias: String? = null) : Expr()
    data class ImplicitVar(val name: String, val alias: String? = null) : Expr()
    data class Constructor(val name: String, val alias: String? = null) : Expr()
    data class PatternLiteral(val regex: String) : Expr()
    data class Lambda(val patterns: List<Pattern>, val body: Expr) : Expr()
    data class App(val fn: Expr, val arg: Expr) : Expr()
    data class BinApp(val op: Expr, val left: Expr, val right: Expr) : Expr()
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr?) : Expr()
    data class Let(val letDef: LetDef, val body: Expr) : Expr()
    data class Match(val exps: List<Expr>, val cases: List<Case>) : Expr()
    data class Ann(val exp: Expr, val type: Type) : Expr()
    data class Do(val exps: List<Expr>) : Expr()
    data class DoLet(val letDef: LetDef) : Expr()
    data class LetBang(val letDef: LetDef, val body: Expr?) : Expr()
    data class For(val letDef: LetDef, val body: Expr) : Expr()
    data class Parens(val exp: Expr) : Expr()
    class Unit : Expr()
    class RecordEmpty : Expr()
    data class RecordSelect(val exp: Expr, val labels: List<Spanned<String>>) : Expr()
    data class RecordExtend(val labels: Labels<Expr>, val exp: Expr) : Expr()
    data class RecordRestrict(val exp: Expr, val labels: List<String>) : Expr()
    data class RecordUpdate(val exp: Expr, val labels: List<Spanned<String>>, val value: Expr, val isSet: Boolean) :
        Expr()

    data class RecordMerge(val exp1: Expr, val exp2: Expr) : Expr()
    data class ListLiteral(val exps: List<Expr>) : Expr()
    data class SetLiteral(val exps: List<Expr>) : Expr()
    data class Index(val exp: Expr, val index: Expr) : Expr()
    class Underscore : Expr()
    data class Throw(val exp: Expr) : Expr()
    data class TryCatch(val tryExpr: Expr, val cases: List<Case>, val finallyExp: Expr?) : Expr()
    data class While(val cond: Expr, val exps: List<Expr>) : Expr()
    data class Computation(val builder: Var, val exps: List<Expr>) : Expr()
    data class Return(val exp: Expr) : Expr()
    data class Yield(val exp: Expr) : Expr()
    data class DoBang(val exp: Expr) : Expr()
    class Null : Expr()
    data class TypeCast(val exp: Expr, val cast: Type) : Expr()
    data class ForeignStaticField(val clazz: Spanned<String>, val field: Spanned<String>) : Expr()
    data class ForeignField(val exp: Expr, val field: Spanned<String>) : Expr()
    data class ForeignMethod(val exp: Expr, val method: Spanned<String>, val args: List<Expr>) : Expr()
    data class ForeignStaticMethod(val clazz: Spanned<String>, val method: Spanned<String>, val args: List<Expr>) :
        Expr()

    var span = Span.empty()
    var comment: Comment? = null

    fun withSpan(s: Span) = apply { span = s }
    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }

    fun withComment(c: Comment?) = apply { comment = c }

    fun <T> withSpanAndComment(s: Spanned<T>) = apply {
        span = s.span
        comment = s.comment
    }

    fun isSimple(): Boolean = when (this) {
        is If, is Let, is Match, is Do, is DoLet, is TryCatch, is While, is Computation -> false
        is Ann -> exp.isSimple()
        else -> true
    }
}

fun Expr.Var.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.ImplicitVar.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.Constructor.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.Operator.fullname(): String = if (alias != null) "$alias.$name" else name

data class Binder(val name: String, val span: Span, val isImplicit: Boolean = false) {
    override fun toString(): String = name
}

sealed class LetDef(open val expr: Expr) {
    data class DefBind(
        val name: Binder,
        val patterns: List<Pattern>,
        override val expr: Expr,
        val isInstance: Boolean,
        val type: Type? = null
    ) : LetDef(expr)

    data class DefPattern(val pat: Pattern, override val expr: Expr) : LetDef(expr)
}

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
    data class TypeTest(val type: Type, val alias: String?, override val span: Span) : Pattern(span)
    data class ImplicitPattern(val pat: Pattern, override val span: Span) : Pattern(span)
    data class TuplePattern(val p1: Pattern, val p2: Pattern, override val span: Span) : Pattern(span)

    // those last patterns will de desugared during desugar phase
    data class Parens(val pattern: Pattern, override val span: Span) : Pattern(span)
    data class TypeAnnotation(val pat: Var, val type: Type, override val span: Span) : Pattern(span)
}

sealed class LiteralPattern {
    data class BoolLiteral(val e: Expr.Bool) : LiteralPattern()
    data class CharLiteral(val e: Expr.CharE) : LiteralPattern()
    data class StringLiteral(val e: Expr.StringE) : LiteralPattern()
    data class Int32Literal(val e: Expr.Int32) : LiteralPattern()
    data class Int64Literal(val e: Expr.Int64) : LiteralPattern()
    data class Float32Literal(val e: Expr.Float32) : LiteralPattern()
    data class Float64Literal(val e: Expr.Float64) : LiteralPattern()
}

typealias Row = Type

sealed class Type(open val span: Span) {
    data class TConst(val name: String, val alias: String? = null, override val span: Span) : Type(span)
    data class TApp(val type: Type, val types: List<Type> = listOf(), override val span: Span) : Type(span)
    data class TFun(val arg: Type, val ret: Type, override val span: Span) : Type(span)
    data class TParens(val type: Type, override val span: Span) : Type(span)
    data class TRecord(val row: Row, override val span: Span) : Type(span)
    data class TRowEmpty(override val span: Span) : Type(span)
    data class TRowExtend(val labels: Labels<Type>, val row: Row, override val span: Span) : Type(span)
    data class TImplicit(val type: Type, override val span: Span) : Type(span)

    fun isConcrete(): Boolean = when (this) {
        is TConst -> name[0].isUpperCase()
        is TParens -> type.isConcrete()
        is TApp -> type.isConcrete()
        else -> false
    }

    /**
     * Walks this type bottom->up
     */
    private fun everywhere(f: (Type) -> Type): Type {
        fun go(t: Type): Type = when (t) {
            is TConst -> f(t)
            is TApp -> f(t.copy(type = go(t.type), types = t.types.map(::go)))
            is TFun -> f(t.copy(go(t.arg), go(t.ret)))
            is TParens -> f(t.copy(go(t.type)))
            is TRecord -> f(t.copy(go(t.row)))
            is TRowEmpty -> f(t)
            is TRowExtend -> f(t.copy(row = go(t.row), labels = t.labels.map { (k, v) -> k to go(v) }))
            is TImplicit -> f(t.copy(go(t.type)))
        }
        return go(this)
    }

    fun everywhereUnit(f: (Type) -> Unit) {
        everywhere { ty ->
            f(ty)
            ty
        }
    }

    /**
     * Find any unbound variables in this type
     */
    fun findFreeVars(bound: List<String>): List<String> = when (this) {
        is TConst -> if (name[0].isLowerCase() && name !in bound) listOf(name) else listOf()
        is TFun -> arg.findFreeVars(bound) + ret.findFreeVars(bound)
        is TApp -> type.findFreeVars(bound) + types.flatMap { it.findFreeVars(bound) }
        is TParens -> type.findFreeVars(bound)
        is TRecord -> row.findFreeVars(bound)
        is TRowEmpty -> emptyList()
        is TRowExtend -> row.findFreeVars(bound) + labels.flatMap { (_, v) -> v.findFreeVars(bound) }
        is TImplicit -> type.findFreeVars(bound)
    }

    fun substVar(from: String, new: Type): Type = everywhere { ty ->
        if (ty is TConst && ty.name == from) new else ty
    }

    fun substVars(map: Map<String, Type>): Type = everywhere { ty ->
        if (ty is TConst) map[ty.name] ?: ty else ty
    }

    fun simpleName(): String? = when (this) {
        is TConst -> name
        is TApp -> type.simpleName()
        is TParens -> type.simpleName()
        else -> null
    }

    fun withSpan(s: Span) = when (this) {
        is TConst -> copy(span = s)
        is TFun -> copy(span = s)
        is TApp -> copy(span = s)
        is TParens -> copy(span = s)
        is TRecord -> copy(span = s)
        is TRowEmpty -> copy(span = s)
        is TRowExtend -> copy(span = s)
        is TImplicit -> copy(span = s)
    }

    fun show(): String = when (this) {
        is TConst -> name
        is TApp -> {
            val sname = type.show()
            if (types.isEmpty()) sname
            else sname + " " + types.joinToString(" ") { it.show() }
        }
        is TFun -> "${arg.show()} -> ${ret.show()}"
        is TParens -> "(${type.show()})"
        is TImplicit -> "{{ ${type.show()} }}"
        is TRowEmpty -> "[]"
        is TRowExtend -> {
            val labels = labels.show { k, v -> "$k : ${v.show()}" }
            val str = when (row) {
                is TRowEmpty -> labels
                !is TRowExtend -> {
                    if (labels.isBlank()) "| ${row.show()}"
                    else "$labels | ${row.show()}"
                }
                else -> {
                    val rows = row.show()
                    if (labels.isBlank()) rows.substring(2, rows.lastIndex - 1)
                    else "$labels, ${rows.substring(2, rows.lastIndex - 1)}"
                }
            }
            "[ $str ]"
        }
        is TRecord -> when (row) {
            is TRowEmpty -> "{}"
            !is TRowExtend -> "{ | ${row.show()} }"
            else -> {
                val rows = row.show()
                "{" + rows.substring(1, rows.lastIndex) + "}"
            }
        }
    }
}

fun Type.TConst.fullname(): String = if (alias != null) "$alias.$name" else name
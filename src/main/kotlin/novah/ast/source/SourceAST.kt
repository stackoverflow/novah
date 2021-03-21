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
import novah.data.flatMapList
import novah.data.mapList
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

data class Module(
    val name: String,
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
    var foreignVars = emptyMap<String, ForeignRef>()

    fun withComment(c: Comment?) = apply { comment = c }
}

enum class Visibility {
    PUBLIC, PRIVATE;
}

/**
 * A reference that can be imported
 */
sealed class DeclarationRef(open val name: String) {
    data class RefVar(override val name: String) : DeclarationRef(name) {
        override fun toString(): String = name
    }

    /**
     * `null` ctors mean only the type is imported, but no constructors.
     * Empty ctors mean all constructors are imported.
     */
    data class RefType(override val name: String, val ctors: List<String>? = null) : DeclarationRef(name) {
        override fun toString(): String = when {
            ctors == null -> name
            ctors.isEmpty() -> "$name(..)"
            else -> name + ctors.joinToString(prefix = "(", postfix = ")")
        }
    }
}

/**
 * A native imported reference.
 */
sealed class ForeignRef {
    data class MethodRef(val method: Method) : ForeignRef()
    data class FieldRef(val field: Field, val isSetter: Boolean) : ForeignRef()
    data class CtorRef(val ctor: Constructor<*>) : ForeignRef()
}

sealed class Import(open val module: String) {
    data class Raw(override val module: String, val span: Span, val alias: String? = null) : Import(module)
    data class Exposing(
        override val module: String,
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

    var comment: Comment? = null

    fun withComment(c: Comment?) = apply { comment = c }
}

sealed class ForeignImport(val type: String, val span: Span) {
    class Type(type: String, val alias: String?, span: Span) : ForeignImport(type, span)
    class Ctor(type: String, val pars: List<String>, val alias: String, span: Span) : ForeignImport(type, span)
    class Method(
        type: String,
        val name: String,
        val pars: List<String>,
        val static: Boolean,
        val alias: String?,
        span: Span
    ) : ForeignImport(type, span)

    class Getter(type: String, val name: String, val static: Boolean, val alias: String?, span: Span) :
        ForeignImport(type, span)

    class Setter(type: String, val name: String, val static: Boolean, val alias: String, span: Span) :
        ForeignImport(type, span)
}

sealed class Decl(val name: String, val visibility: Visibility) {
    class TypeDecl(
        name: String,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        visibility: Visibility,
        val isOpaque: Boolean
    ) : Decl(name, visibility)

    class ValDecl(
        name: String,
        val patterns: List<FunparPattern>,
        val exp: Expr,
        val type: Type?,
        visibility: Visibility,
        val isInstance: Boolean,
        val isOperator: Boolean
    ) : Decl(name, visibility)

    class TypealiasDecl(name: String, val tyVars: List<String>, val type: Type, visibility: Visibility) :
        Decl(name, visibility) {
        var expanded: Type? = null
    }

    var comment: Comment? = null
    var span = Span.empty()

    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }
}

data class DataConstructor(val name: String, val args: List<Type>, val visibility: Visibility, val span: Span) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr {
    data class IntE(val v: Int, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class LongE(val v: Long, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class FloatE(val v: Float, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class DoubleE(val v: Double, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class StringE(val v: String) : Expr() {
        override fun toString(): String = "\"$v\""
    }

    data class CharE(val v: Char) : Expr() {
        override fun toString(): String = "'$v'"
    }

    data class Bool(val v: Boolean) : Expr() {
        override fun toString(): String = "$v"
    }

    data class Var(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "$alias.$name" else name
    }

    data class Operator(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "$alias.$name" else name
    }

    data class ImplicitVar(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "{{$alias.$name}}" else "{{$name}}"
    }

    data class Constructor(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "$alias.$name" else name
    }

    data class Lambda(val patterns: List<FunparPattern>, val body: Expr) : Expr() {
        override fun toString(): String = "\\" + patterns.joinToString(" ") + " -> $body"
    }

    data class App(val fn: Expr, val arg: Expr) : Expr() {
        override fun toString(): String = "($fn $arg)"
    }

    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr) : Expr()
    data class Let(val letDefs: List<LetDef>, val body: Expr) : Expr()
    data class Match(val exps: List<Expr>, val cases: List<Case>) : Expr()
    data class Ann(val exp: Expr, val type: Type) : Expr()
    data class Do(val exps: List<Expr>) : Expr()
    data class DoLet(val letDefs: List<LetDef>) : Expr()
    data class Parens(val exp: Expr) : Expr() {
        override fun toString(): String = "($exp)"
    }

    class Unit : Expr()
    class RecordEmpty : Expr()
    data class RecordSelect(val exp: Expr, val label: String) : Expr()
    data class RecordExtend(val labels: LabelMap<Expr>, val exp: Expr) : Expr()
    data class RecordRestrict(val exp: Expr, val label: String) : Expr()
    data class VectorLiteral(val exps: List<Expr>) : Expr()
    data class SetLiteral(val exps: List<Expr>) : Expr()

    var span = Span.empty()
    var comment: Comment? = null

    fun withSpan(s: Span) = apply { span = s }
    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }

    fun withComment(c: Comment?) = apply { comment = c }

    fun <T> withSpanAndComment(s: Spanned<T>) = apply {
        span = s.span
        comment = s.comment
    }
}

fun Expr.Var.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.ImplicitVar.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.Constructor.fullname(): String = if (alias != null) "$alias.$name" else name
fun Expr.Operator.fullname(): String = if (alias != null) "$alias.$name" else name

data class Binder(val name: String, val span: Span, val isImplicit: Boolean = false) {
    override fun toString(): String = name
}

sealed class FunparPattern(open val span: Span) {
    data class Ignored(override val span: Span) : FunparPattern(span)
    data class Unit(override val span: Span) : FunparPattern(span)
    data class Bind(val binder: Binder) : FunparPattern(binder.span)
}

data class LetDef(
    val name: Binder,
    val patterns: List<FunparPattern>,
    val expr: Expr,
    val isInstance: Boolean,
    val type: Type? = null
)

data class Case(val patterns: List<Pattern>, val exp: Expr, val guard: Expr? = null) {
    fun patternSpan() = Span.new(patterns[0].span, patterns.last().span)
}

sealed class Pattern(open val span: Span) {
    data class Wildcard(override val span: Span) : Pattern(span)
    data class LiteralP(val lit: LiteralPattern, override val span: Span) : Pattern(span)
    data class Var(val name: String, override val span: Span) : Pattern(span)
    data class Ctor(val ctor: Expr.Constructor, val fields: List<Pattern>, override val span: Span) : Pattern(span)
    data class Parens(val pattern: Pattern, override val span: Span) : Pattern(span)
    data class Record(val labels: LabelMap<Pattern>, override val span: Span) : Pattern(span)
    data class Vector(val elems: List<Pattern>, override val span: Span) : Pattern(span)
    data class VectorHT(val head: Pattern, val tail: Pattern, override val span: Span) : Pattern(span)
    data class Named(val pat: Pattern, val name: String, override val span: Span) : Pattern(span)
    data class Unit(override val span: Span) : Pattern(span)
    data class TypeTest(val type: Type, val alias: String?, override val span: Span) : Pattern(span)
}

sealed class LiteralPattern {
    data class BoolLiteral(val e: Expr.Bool) : LiteralPattern()
    data class CharLiteral(val e: Expr.CharE) : LiteralPattern()
    data class StringLiteral(val e: Expr.StringE) : LiteralPattern()
    data class IntLiteral(val e: Expr.IntE) : LiteralPattern()
    data class LongLiteral(val e: Expr.LongE) : LiteralPattern()
    data class FloatLiteral(val e: Expr.FloatE) : LiteralPattern()
    data class DoubleLiteral(val e: Expr.DoubleE) : LiteralPattern()
}

typealias Row = Type

sealed class Type(open val span: Span) {
    data class TConst(val name: String, val alias: String? = null, override val span: Span) : Type(span)
    data class TApp(val type: Type, val types: List<Type> = listOf(), override val span: Span) : Type(span)
    data class TFun(val arg: Type, val ret: Type, override val span: Span) : Type(span)
    data class TForall(val names: List<String>, val type: Type, override val span: Span) : Type(span)
    data class TParens(val type: Type, override val span: Span) : Type(span)
    data class TRecord(val row: Row, override val span: Span) : Type(span)
    data class TRowEmpty(override val span: Span) : Type(span)
    data class TRowExtend(val labels: LabelMap<Type>, val row: Row, override val span: Span) : Type(span)
    data class TImplicit(val type: Type, override val span: Span) : Type(span)

    /**
     * Walks this type bottom->up
     */
    fun everywhere(f: (Type) -> Type): Type {
        fun go(t: Type): Type = when (t) {
            is TConst -> f(t)
            is TApp -> f(t.copy(type = go(t.type), types = t.types.map(::go)))
            is TFun -> f(t.copy(go(t.arg), go(t.ret)))
            is TForall -> f(t.copy(type = go(t.type)))
            is TParens -> f(t.copy(go(t.type)))
            is TRecord -> f(t.copy(go(t.row)))
            is TRowEmpty -> f(t)
            is TRowExtend -> f(t.copy(row = go(t.row), labels = t.labels.mapList { go(it) }))
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
        is TForall -> type.findFreeVars(bound + names)
        is TApp -> type.findFreeVars(bound) + types.flatMap { it.findFreeVars(bound) }
        is TParens -> type.findFreeVars(bound)
        is TRecord -> row.findFreeVars(bound)
        is TRowEmpty -> emptyList()
        is TRowExtend -> row.findFreeVars(bound) + labels.flatMapList { it.findFreeVars(bound) }
        is TImplicit -> type.findFreeVars(bound)
    }

    fun substVar(from: String, new: Type): Type = everywhere { ty ->
        if (ty is TConst && ty.name == from) new else ty
    }

    fun substVars(map: Map<String, Type>): Type = everywhere { ty ->
        if (ty is TConst) map[ty.name] ?: ty else ty
    }
}

fun Type.TConst.fullname(): String = if (alias != null) "$alias.$name" else name
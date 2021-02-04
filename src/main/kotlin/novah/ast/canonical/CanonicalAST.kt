package novah.ast.canonical

import novah.ast.source.Visibility
import novah.frontend.Span
import novah.frontend.hmftypechecker.Id
import novah.frontend.hmftypechecker.Type
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Constructor as JConstructor

/**
 * The canonical AST used after desugaring and after type checking.
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
    ) :
        Decl()
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
    data class Lambda(
        val pattern: FunparPattern,
        val ann: Pair<List<Id>, Type>?,
        val body: Expr,
        override val span: Span
    ) : Expr(span)

    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
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

    var type: Type? = null

    fun withType(t: Type): Type {
        type = t
        return t
    }
}

fun Expr.Constructor.fullname(module: String): String = "$module.$name"

data class Binder(val name: String, val span: Span) {
    override fun toString(): String = name
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

fun Expr.substVar(from: String, to: String): Expr = when (this) {
    is Expr.Var -> if (from == name) Expr.Var(to, span, moduleName) else this
    is Expr.Lambda -> copy(body = body.substVar(from, to))
    is Expr.App -> Expr.App(fn.substVar(from, to), arg.substVar(from, to), span)
    is Expr.If -> Expr.If(cond.substVar(from, to), thenCase.substVar(from, to), elseCase.substVar(from, to), span)
    is Expr.Let -> copy(letDef = letDef.copy(expr = letDef.expr.substVar(from, to)), body = body.substVar(from, to))
    is Expr.Match -> copy(exp = exp.substVar(from, to), cases = cases.map { it.copy(exp = exp.substVar(from, to)) })
    is Expr.Ann -> copy(exp = exp.substVar(from, to))
    is Expr.Do -> copy(exps = exps.map { it.substVar(from, to) })
    else -> this
}

fun <T> Expr.everywhereAccumulating(f: (Expr) -> List<T>): List<T> = when (this) {
    is Expr.Var -> f(this)
    is Expr.Constructor -> f(this)
    is Expr.Lambda -> f(body)
    is Expr.App -> f(fn) + f(arg)
    is Expr.If -> f(cond) + f(thenCase) + f(elseCase)
    is Expr.Let -> f(letDef.expr) + f(body)
    is Expr.Match -> f(exp) + cases.flatMap { f(it.exp) }
    is Expr.Ann -> f(exp)
    is Expr.Do -> exps.flatMap { f(it) }
    else -> emptyList()
}
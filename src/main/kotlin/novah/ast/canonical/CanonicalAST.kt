package novah.ast.canonical

import novah.frontend.Span
import novah.frontend.typechecker.Type

/**
 * The canonical AST used after desugaring and after type checking
 * the type var T will be Span after desugaring and Type after type checking
 */

typealias ModuleName = List<String>

data class Module<T>(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: List<String>,
    val decls: List<Decl<T>>
)

sealed class Import(val module: ModuleName) {
    data class Raw(val mod: ModuleName, val alias: String? = null) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>, val alias: String? = null) : Import(mod)
}

sealed class Decl<T> {
    data class DataDecl<T>(val name: String, val tyVars: List<String>, val dataCtors: List<DataConstructor>, val span: Span) : Decl<T>()
    data class TypeDecl<T>(val name: String, val type: Type, val span: Span) : Decl<T>()
    data class ValDecl<T>(val name: String, val exp: Expr<T>, val span: Span) : Decl<T>()
}

data class DataConstructor(val name: String, val args: List<Type>) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr<T>(open val value: T) {
    data class IntE<T>(val i: Long, override val value: T) : Expr<T>(value)
    data class FloatE<T>(val f: Double, override val value: T) : Expr<T>(value)
    data class StringE<T>(val s: String, override val value: T) : Expr<T>(value)
    data class CharE<T>(val c: Char, override val value: T) : Expr<T>(value)
    data class Bool<T>(val b: Boolean, override val value: T) : Expr<T>(value)
    data class Var<T>(val name: String, override val value: T, val moduleName: String? = null) : Expr<T>(value)
    data class Lambda<T>(val binder: String, val body: Expr<T>, override val value: T) : Expr<T>(value)
    data class App<T>(val fn: Expr<T>, val arg: Expr<T>, override val value: T) : Expr<T>(value)
    data class If<T>(val cond: Expr<T>, val thenCase: Expr<T>, val elseCase: Expr<T>, override val value: T) : Expr<T>(value)
    data class Let<T>(val letDef: LetDef<T>, val body: Expr<T>, override val value: T) : Expr<T>(value)
    data class Match<T>(val exp: Expr<T>, val cases: List<Case<T>>, override val value: T) : Expr<T>(value)
    data class Ann<T>(val exp: Expr<T>, val type: Type, override val value: T) : Expr<T>(value)
    data class Do<T>(val exps: List<Expr<T>>, override val value: T) : Expr<T>(value)
}

data class LetDef<T>(val name: String, val expr: Expr<T>, val type: Type? = null)

data class Case<T>(val pattern: Pattern, val exp: Expr<T>)

sealed class Pattern {
    object Wildcard : Pattern()
    data class LiteralP(val lit: LiteralPattern) : Pattern()
    data class Var(val name: String) : Pattern()
    data class Ctor(val name: String, val fields: List<Pattern>) : Pattern()
}

sealed class LiteralPattern {
    data class BoolLiteral(val b: Boolean) : LiteralPattern()
    data class CharLiteral(val c: Char) : LiteralPattern()
    data class StringLiteral(val s: String) : LiteralPattern()
    data class IntLiteral(val i: Long) : LiteralPattern()
    data class FloatLiteral(val f: Double) : LiteralPattern()
}

////////////////////////////////
// AST functions
////////////////////////////////

fun <T> Case<T>.substVar(v: String, s: Expr<T>): Case<T> {
    val e = exp.substVar(v, s)
    return if (e == exp) this else Case(pattern, e)
}

fun <T> LetDef<T>.substVar(v: String, s: Expr<T>): LetDef<T> {
    val sub = expr.substVar(v, s)
    return if (expr == sub) this else LetDef(name, sub, type)
}

fun <T> Expr<T>.substVar(v: String, s: Expr<T>): Expr<T> =
    when (this) {
        is Expr.IntE -> this
        is Expr.FloatE -> this
        is Expr.StringE -> this
        is Expr.CharE -> this
        is Expr.Bool -> this
        is Expr.Var -> if (name == v) s else this
        is Expr.App -> {
            val left = fn.substVar(v, s)
            val right = arg.substVar(v, s)
            if (left == fn && right == arg) this else Expr.App(left, right, value)
        }
        is Expr.Lambda -> {
            if (binder == v) this
            else {
                val b = body.substVar(v, s)
                if (body == b) this else Expr.Lambda(binder, b, value)
            }
        }
        is Expr.Ann -> {
            val b = exp.substVar(v, s)
            if (exp == b) this else Expr.Ann(b, type, value)
        }
        is Expr.If -> {
            val c = cond.substVar(v, s)
            val t = thenCase.substVar(v, s)
            val e = elseCase.substVar(v, s)
            if (c == cond && t == thenCase && e == elseCase) this else Expr.If(c, t, e, value)
        }
        is Expr.Let -> {
            val b = body.substVar(v, s)
            val def = letDef.substVar(v, s)
            if (b == body && def == letDef) this else Expr.Let(def, b, value)
        }
        is Expr.Match -> {
            val e = exp.substVar(v, s)
            val cs = cases.map { it.substVar(v, s) }
            if (e == exp && cs == cases) this else Expr.Match(e, cs, value)
        }
        is Expr.Do -> {
            val es = exps.map { it.substVar(v, s) }
            if (es == exps) this else Expr.Do(es, value)
        }
    }

fun <T> Expr.Lambda<T>.openLambda(s: Expr<T>): Expr<T> = body.substVar(binder, s)
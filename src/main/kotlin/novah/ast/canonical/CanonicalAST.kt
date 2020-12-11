package novah.ast.canonical

import novah.frontend.ModuleExports
import novah.frontend.Span
import novah.frontend.typechecker.Type

/**
 * The canonical AST used after desugaring
 */

typealias ModuleName = List<String>

data class Module(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: ModuleExports,
    val decls: List<Decl>
)

sealed class Import(val module: ModuleName) {
    data class Raw(val mod: ModuleName, val alias: String? = null) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>, val alias: String? = null) : Import(mod)
}

sealed class Decl {
    data class DataDecl(val name: String, val tyVars: List<String>, val dataCtors: List<DataConstructor>, val span: Span) : Decl()
    data class TypeDecl(val name: String, val type: Type, val span: Span) : Decl()
    data class ValDecl(val name: String, val exp: Expr, val span: Span) : Decl()
}

data class DataConstructor(val name: String, val args: List<Type>) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr(open val span: Span) {
    data class IntE(val i: Long, override val span: Span) : Expr(span)
    data class FloatE(val f: Double, override val span: Span) : Expr(span)
    data class StringE(val s: String, override val span: Span) : Expr(span)
    data class CharE(val c: Char, override val span: Span) : Expr(span)
    data class Bool(val b: Boolean, override val span: Span) : Expr(span)
    data class Var(val name: String, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Lambda(val binder: String, val body: Expr, override val span: Span) : Expr(span)
    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exp: Expr, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val type: Type, override val span: Span) : Expr(span)
    data class Do(val exps: List<Expr>, override val span: Span) : Expr(span)
}

data class LetDef(val name: String, val expr: Expr, val type: Type? = null)

data class Case(val pattern: Pattern, val exp: Expr)

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

fun Case.substVar(v: String, s: Expr): Case {
    val e = exp.substVar(v, s)
    return if (e == exp) this else Case(pattern, e)
}

fun LetDef.substVar(v: String, s: Expr): LetDef {
    val sub = expr.substVar(v, s)
    return if (expr == sub) this else LetDef(name, sub, type)
}

fun Expr.substVar(v: String, s: Expr): Expr =
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
            if (left == fn && right == arg) this else Expr.App(left, right, span)
        }
        is Expr.Lambda -> {
            if (binder == v) this
            else {
                val b = body.substVar(v, s)
                if (body == b) this else Expr.Lambda(binder, b, span)
            }
        }
        is Expr.Ann -> {
            val b = exp.substVar(v, s)
            if (exp == b) this else Expr.Ann(b, type, span)
        }
        is Expr.If -> {
            val c = cond.substVar(v, s)
            val t = thenCase.substVar(v, s)
            val e = elseCase.substVar(v, s)
            if (c == cond && t == thenCase && e == elseCase) this else Expr.If(c, t, e, span)
        }
        is Expr.Let -> {
            val b = body.substVar(v, s)
            val def = letDef.substVar(v, s)
            if (b == body && def == letDef) this else Expr.Let(def, b, span)
        }
        is Expr.Match -> {
            val e = exp.substVar(v, s)
            val cs = cases.map { it.substVar(v, s) }
            if (e == exp && cs == cases) this else Expr.Match(e, cs, span)
        }
        is Expr.Do -> {
            val es = exps.map { it.substVar(v, s) }
            if (es == exps) this else Expr.Do(es, span)
        }
    }

fun Expr.Lambda.openLambda(s: Expr): Expr = body.substVar(binder, s)
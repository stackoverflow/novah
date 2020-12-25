package novah.ast.canonical

import novah.frontend.Span
import novah.frontend.typechecker.*

/**
 * The canonical AST used after desugaring and after type checking
 * the type var T will be Span after desugaring and Type after type checking
 */

data class Module(
    val name: String,
    val sourceName: String,
    val decls: List<Decl>
)

enum class Visibility {
    PUBLIC, PRIVATE;
}

sealed class Decl {
    data class DataDecl(
        val name: String,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        val span: Span,
        val visibility: Visibility
    ) : Decl()

    data class TypeDecl(val name: String, val type: Type, val span: Span) : Decl()
    data class ValDecl(val name: String, val exp: Expr, val span: Span, val visibility: Visibility) : Decl()
}

data class DataConstructor(val name: String, val args: List<Type>, val visibility: Visibility) {
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
    data class Var(val name: Name, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Lambda(val binder: Binder, val body: Expr, override val span: Span) : Expr(span)
    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exp: Expr, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val annType: Type, override val span: Span) : Expr(span)
    data class Do(val exps: List<Expr>, override val span: Span) : Expr(span)

    var type: Type? = null
    private var aliases = mutableListOf<Expr>()

    fun withType(t: Type): Type {
        type = t
        if (aliases.isNotEmpty()) aliases.forEach { it.withType(t) }
        return t
    }

    fun alias(e: Expr) = apply { aliases.add(e) }
}

data class Binder(val name: Name, val span: Span) {
    override fun toString(): String = "$name"
}

data class LetDef(val binder: Binder, val expr: Expr, val type: Type? = null)

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

fun Case.substVar(v: Name, s: Expr): Case {
    val e = exp.substVar(v, s)
    return if (e == exp) this else Case(pattern, e)
}

fun LetDef.substVar(v: Name, s: Expr): LetDef {
    val sub = expr.substVar(v, s)
    return if (expr == sub) this else LetDef(binder, sub, type)
}

fun Expr.substVar(v: Name, s: Expr): Expr =
    when (this) {
        is Expr.IntE -> this
        is Expr.LongE -> this
        is Expr.FloatE -> this
        is Expr.DoubleE -> this
        is Expr.StringE -> this
        is Expr.CharE -> this
        is Expr.Bool -> this
        is Expr.Var -> if (name == v) s.alias(this) else this
        is Expr.App -> {
            val left = fn.substVar(v, s)
            val right = arg.substVar(v, s)
            if (left == fn && right == arg) this else Expr.App(left, right, span).alias(this)
        }
        is Expr.Lambda -> {
            if (binder.name == v) this
            else {
                val b = body.substVar(v, s)
                if (body == b) this else Expr.Lambda(binder, b, span).alias(this)
            }
        }
        is Expr.Ann -> {
            val b = exp.substVar(v, s)
            if (exp == b) this else Expr.Ann(b, annType, span).alias(this)
        }
        is Expr.If -> {
            val c = cond.substVar(v, s)
            val t = thenCase.substVar(v, s)
            val e = elseCase.substVar(v, s)
            if (c == cond && t == thenCase && e == elseCase) this else Expr.If(c, t, e, span).alias(this)
        }
        is Expr.Let -> {
            val b = body.substVar(v, s)
            val def = letDef.substVar(v, s)
            if (b == body && def == letDef) this else Expr.Let(def, b, span).alias(this)
        }
        is Expr.Match -> {
            val e = exp.substVar(v, s)
            val cs = cases.map { it.substVar(v, s) }
            if (e == exp && cs == cases) this else Expr.Match(e, cs, span).alias(this)
        }
        is Expr.Do -> {
            val es = exps.map { it.substVar(v, s) }
            if (es == exps) this else Expr.Do(es, span).alias(this)
        }
    }

fun Expr.Lambda.openLambda(s: Expr): Expr = body.substVar(binder.name, s)

/**
 * Resolve solved meta variables
 */
fun Expr.resolveMetas(ctx: Context) {
    tailrec fun resolveMeta(meta: Elem.CTMeta): Type? {
        val typ = meta.type
        if (typ == null || typ !is Type.TMeta) return typ
        val t = ctx.lookup<Elem.CTMeta>(typ.name)
        return if (t?.type != null) resolveMeta(t)
        else typ
    }

    val resolved = mutableMapOf<Name, Type>()
    ctx.forEachMeta { m ->
        val typ = resolveMeta(m)
        if (typ != null) resolved[m.name] = typ
    }

    resolveUnsolved(resolved)
}

/**
 * Resolve unsolved variables left in this expression
 */
fun Expr.resolveUnsolved(m: Map<Name, Type>) {
    when (this) {
        is Expr.Var -> if (type != null) withType(type!!.substTMetas(m))
        is Expr.App -> {
            if (type != null) withType(type!!.substTMetas(m))
            fn.resolveUnsolved(m)
            arg.resolveUnsolved(m)
        }
        is Expr.Lambda -> {
            if (type != null) withType(type!!.substTMetas(m))
            body.resolveUnsolved(m)
        }
        is Expr.Ann -> {
            if (type != null) withType(type!!.substTMetas(m))
            exp.resolveUnsolved(m)
        }
        is Expr.If -> {
            if (type != null) withType(type!!.substTMetas(m))
            cond.resolveUnsolved(m)
            thenCase.resolveUnsolved(m)
            elseCase.resolveUnsolved(m)
        }
        is Expr.Let -> {
            if (type != null) withType(type!!.substTMetas(m))
            letDef.expr.resolveUnsolved(m)
            body.resolveUnsolved(m)
        }
        is Expr.Match -> {
            if (type != null) withType(type!!.substTMetas(m))
            cases.forEach { it.exp.resolveUnsolved(m) }
        }
        is Expr.Do -> {
            if (type != null) withType(type!!.substTMetas(m))
            exps.forEach { it.resolveUnsolved(m) }
        }
        else -> {
        }
    }
}
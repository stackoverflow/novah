package novah.ast.canonical

import novah.frontend.Span
import novah.frontend.typechecker.Name
import novah.frontend.typechecker.Type
import java.lang.reflect.Constructor as JConstructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The canonical AST used after desugaring and after type checking.
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
    data class Var(val name: Name, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Constructor(val name: Name, override val span: Span, val moduleName: String? = null) : Expr(span)
    data class Lambda(val pattern: FunparPattern, val body: Expr, override val span: Span) : Expr(span)
    data class App(val fn: Expr, val arg: Expr, override val span: Span) : Expr(span)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val span: Span) : Expr(span)
    data class Let(val letDef: LetDef, val body: Expr, override val span: Span) : Expr(span)
    data class Match(val exp: Expr, val cases: List<Case>, override val span: Span) : Expr(span)
    data class Ann(val exp: Expr, val annType: Type, override val span: Span) : Expr(span)
    data class Do(val exps: List<Expr>, override val span: Span) : Expr(span)
    data class NativeFieldGet(val name: Name, val field: Field, override val span: Span) : Expr(span)
    data class NativeFieldSet(val name: Name, val field: Field, override val span: Span) : Expr(span)
    data class NativeMethod(val name: Name, val method: Method, override val span: Span) : Expr(span)
    data class NativeConstructor(val name: Name, val ctor: JConstructor<*>, override val span: Span) : Expr(span)
    data class Unit(override val span: Span) : Expr(span)

    var type: Type? = null
    var alias: Name? = null

    fun withType(t: Type): Type {
        type = t
        return t
    }
}

data class Binder(val name: Name, val span: Span) {
    override fun toString(): String = "$name"
}

sealed class FunparPattern(val span: Span) {
    class Ignored(span: Span) : FunparPattern(span)
    class Unit(span: Span) : FunparPattern(span)
    class Bind(val binder: Binder) : FunparPattern(binder.span)
}

data class LetDef(val binder: Binder, val expr: Expr, val type: Type? = null)

data class Case(val pattern: Pattern, val exp: Expr)

sealed class Pattern(open val span: Span) {
    data class Wildcard(override val span: Span) : Pattern(span)
    data class LiteralP(val lit: LiteralPattern, override val span: Span) : Pattern(span)
    data class Var(val name: Name, override val span: Span) : Pattern(span)
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
    is Pattern.Var -> name.toString()
    is Pattern.Ctor -> if (fields.isEmpty()) "${ctor.name}" else "${ctor.name} " + fields.joinToString(" ") { it.show() }
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

////////////////////////////////
// AST functions
////////////////////////////////

/**
 * Can only be called for lambdas
 * with a bound variable
 */
fun Expr.Lambda.aliasLambda(newName: Name): Expr =
    body.aliasVar((pattern as FunparPattern.Bind).binder.name, newName)

fun Expr.aliasVar(v: Name, newName: Name): Expr {
    when (this) {
        is Expr.Var -> if (v == name) this.alias = newName
        is Expr.Constructor -> if (v == name) this.alias = newName
        is Expr.App -> {
            fn.aliasVar(v, newName)
            arg.aliasVar(v, newName)
        }
        is Expr.Lambda -> {
            if (pattern is FunparPattern.Bind) {
                if (pattern.binder.name != v)
                    body.aliasVar(v, newName)
            } else body.aliasVar(v, newName)
        }
        is Expr.Ann -> exp.aliasVar(v, newName)
        is Expr.If -> {
            cond.aliasVar(v, newName)
            thenCase.aliasVar(v, newName)
            elseCase.aliasVar(v, newName)
        }
        is Expr.Let -> {
            body.aliasVar(v, newName)
            letDef.expr.aliasVar(v, newName)
        }
        is Expr.Match -> {
            exp.aliasVar(v, newName)
            cases.map { it.exp.aliasVar(v, newName) }
        }
        is Expr.Do -> exps.forEach { it.aliasVar(v, newName) }
        else -> {
        }
    }
    return this
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
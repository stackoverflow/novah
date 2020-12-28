package novah.ast.optimized

import novah.ast.canonical.Visibility

/**
 * The optmized AST used for code generation
 */

data class Module(val name: String, val sourceName: String, val hasLambda: Boolean, val decls: List<Decl>)

sealed class Decl(open val lineNumber: Int) {
    data class DataDecl(
        val name: String,
        val tyVars: List<String>,
        val dataCtors: List<DataConstructor>,
        val visibility: Visibility,
        override val lineNumber: Int
    ) : Decl(lineNumber)

    data class ValDecl(val name: String, val exp: Expr, val visibility: Visibility, override val lineNumber: Int) :
        Decl(lineNumber)
}

data class DataConstructor(val name: String, val args: List<Type>, val visibility: Visibility)

sealed class Expr(open val type: Type) {
    data class ByteE(val v: Byte, override val type: Type) : Expr(type)
    data class ShortE(val v: Short, override val type: Type) : Expr(type)
    data class IntE(val v: Int, override val type: Type) : Expr(type)
    data class LongE(val v: Long, override val type: Type) : Expr(type)
    data class FloatE(val v: Float, override val type: Type) : Expr(type)
    data class DoubleE(val v: Double, override val type: Type) : Expr(type)
    data class StringE(val v: String, override val type: Type) : Expr(type)
    data class CharE(val v: Char, override val type: Type) : Expr(type)
    data class Bool(val v: Boolean, override val type: Type) : Expr(type)
    data class Var(val name: String, val className: String, override val type: Type) : Expr(type)
    data class Constructor(val fullName: String, val arity: Int, override val type: Type) : Expr(type)
    data class LocalVar(val name: String, override val type: Type) : Expr(type)
    data class Lambda(
        val binder: String,
        val body: Expr,
        var internalName: String = "",
        var locals: List<LocalVar> = listOf(),
        override val type: Type
    ) : Expr(type)

    data class App(val fn: Expr, val arg: Expr, override val type: Type) : Expr(type)
    data class CtorApp(val ctor: Constructor, val args: List<Expr>, override val type: Type) : Expr(type)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val type: Type) : Expr(type)
    data class Let(val binder: String, val bindExpr: Expr, val body: Expr, override val type: Type) : Expr(type)
    data class Do(val exps: List<Expr>, override val type: Type) : Expr(type)
}

sealed class Type {
    data class TVar(val name: String, val isForall: Boolean = false) : Type()
    data class TFun(val arg: Type, val ret: Type) : Type()
    data class TConstructor(val name: String, val types: List<Type>) : Type()

    fun isGeneric(): Boolean = when (this) {
        is TVar -> isForall
        is TFun -> true
        is TConstructor -> types.isNotEmpty()
    }

    fun getReturnTypeNameOr(notFound: String): String = when (this) {
        is TVar -> if (isForall) notFound else name
        is TFun -> ret.getReturnTypeNameOr(notFound)
        is TConstructor -> name
    }
}

fun Expr.Var.fullname() = "$className.$name"
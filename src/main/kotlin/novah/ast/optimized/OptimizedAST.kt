package novah.ast.optimized

import novah.ast.canonical.Visibility

/**
 * The optmized AST used for code generation
 */

data class Module(val name: String, val sourceName: String, val decls: List<Decl>)

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
    data class IntE(val i: Long, override val type: Type) : Expr(type)
    data class FloatE(val f: Double, override val type: Type) : Expr(type)
    data class StringE(val s: String, override val type: Type) : Expr(type)
    data class CharE(val c: Char, override val type: Type) : Expr(type)
    data class Bool(val b: Boolean, override val type: Type) : Expr(type)
    data class Var(val name: String, val className: String, override val type: Type) : Expr(type)
    data class LocalVar(val name: String, val num: Int, override val type: Type) : Expr(type)
    data class Lambda(val binder: String, val body: Expr, override val type: Type) : Expr(type)
    data class App(val fn: Expr, val arg: Expr, override val type: Type) : Expr(type)
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr, override val type: Type) : Expr(type)
    data class Let(val letDef: LetDef, val body: Expr, override val type: Type) : Expr(type)
    data class Do(val exps: List<Expr>, override val type: Type) : Expr(type)
}

data class LetDef(val name: String, val num: Int, val expr: Expr)

sealed class Type {
    data class TVar(val name: String, val isForall: Boolean = false) : Type()
    data class TFun(val arg: Type, val ret: Type) : Type()
    data class TConstructor(val name: String, val types: List<Type>) : Type()

    fun isGeneric(): Boolean = when (this) {
        is TVar -> isForall
        is TFun -> arg.isGeneric() || ret.isGeneric()
        is TConstructor -> types.any(Type::isGeneric)
    }
}
package novah.ast.source

import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned

typealias ModuleName = List<String>

data class Module(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: ModuleExports,
    val decls: List<Decl>
) {
    var comment: Comment? = null

    fun withComment(c: Comment?) = apply { comment = c }
}

/**
 * A reference that can be imported or exported
 */
sealed class DeclarationRef(open val name: String) {
    data class RefVar(override val name: String) : DeclarationRef(name) {
        override fun toString(): String = name
    }

    /**
     * `null` ctors mean only the type is exported, but no constructors.
     * Empty ctors mean all constructors are exported.
     */
    data class RefType(override val name: String, val ctors: List<String>? = null) : DeclarationRef(name) {
        override fun toString(): String = when {
            ctors == null -> name
            ctors.isEmpty() -> "$name(..)"
            else -> name + ctors.joinToString(prefix = "(", postfix = ")")
        }
    }
}

sealed class ModuleExports {
    object ExportAll : ModuleExports()
    data class Hiding(val hides: List<DeclarationRef>) : ModuleExports()
    data class Exposing(val exports: List<DeclarationRef>) : ModuleExports()
}

sealed class Import(open val module: ModuleName) {
    data class Raw(override val module: ModuleName, val alias: String? = null) : Import(module)
    data class Exposing(override val module: ModuleName, val defs: List<DeclarationRef>, val alias: String? = null) :
        Import(module)

    fun alias(): String? = when (this) {
        is Raw -> alias
        is Exposing -> alias
    }

    var comment: Comment? = null

    fun withComment(c: Comment?) = apply { comment = c }
}

sealed class Decl {
    data class DataDecl(val name: String, val tyVars: List<String>, val dataCtors: List<DataConstructor>) : Decl()
    data class TypeDecl(val name: String, val type: Type) : Decl()
    data class ValDecl(val name: String, val binders: List<String>, val exp: Expr) : Decl()

    var comment: Comment? = null
    var span = Span.empty()

    fun withSpan(s: Span, e: Span) = apply { span = Span(s.start, e.end) }
}

data class DataConstructor(val name: String, val args: List<Type>) {
    override fun toString(): String {
        return name + args.joinToString(" ", prefix = " ")
    }
}

sealed class Expr {
    data class IntE(val i: Long, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class FloatE(val f: Double, val text: String) : Expr() {
        override fun toString(): String = text
    }

    data class StringE(val s: String) : Expr() {
        override fun toString(): String = "\"$s\""
    }

    data class CharE(val c: Char) : Expr() {
        override fun toString(): String = "'$c'"
    }

    data class Bool(val b: Boolean) : Expr() {
        override fun toString(): String = "$b"
    }

    data class Var(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "$alias.$name" else name
    }

    data class Operator(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = name
    }

    data class Lambda(val binders: List<String>, val body: Expr) : Expr() {
        override fun toString(): String = "\\" + binders.joinToString(" ") + " -> $body"
    }

    data class App(val fn: Expr, val arg: Expr) : Expr() {
        override fun toString(): String = "($fn $arg)"
    }

    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr) : Expr()
    data class Let(val letDefs: List<LetDef>, val body: Expr) : Expr()
    data class Match(val exp: Expr, val cases: List<Case>) : Expr()
    data class Ann(val exp: Expr, val type: Type) : Expr()
    data class Do(val exps: List<Expr>) : Expr()
    data class Parens(val exp: Expr) : Expr() {
        override fun toString(): String = "($exp)"
    }

    var span = Span.empty()
    var comment: Comment? = null

    fun withSpan(s: Span) = apply { span = s }
    fun withSpan(s: Span, e: Span) = apply { span = Span(s.start, e.end) }

    fun withComment(c: Comment?) = apply { comment = c }

    fun <T> withSpanAndComment(s: Spanned<T>) = apply {
        span = s.span
        comment = s.comment
    }
}

data class LetDef(val name: String, val binders: List<String>, val expr: Expr, val type: Type? = null)

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

sealed class Type {
    data class TVar(val name: String) : Type()
    data class TFun(val arg: Type, val ret: Type) : Type()
    data class TForall(val names: List<String>, val type: Type) : Type()
    data class TConstructor(val name: String, val types: List<Type> = listOf()) : Type()
    data class TParens(val type: Type) : Type()

    /**
     * Find any unbound variables in this type
     */
    fun findFreeVars(bound: List<String>): List<String> = when (this) {
        is TVar -> if (name[0].isLowerCase() && name !in bound) listOf(name) else listOf()
        is TFun -> arg.findFreeVars(bound) + ret.findFreeVars(bound)
        is TForall -> type.findFreeVars(bound + names)
        is TConstructor -> types.flatMap { it.findFreeVars(bound) }
        is TParens -> type.findFreeVars(bound)
    }
}

fun Module.fullName() = name.joinToString(".")

fun Import.fullName() = module.joinToString(".")
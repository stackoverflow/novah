package novah.ast.source

import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned

data class Module(
    val name: String,
    val sourceName: String,
    val imports: List<Import>,
    val exports: ModuleExports,
    val foreigns: List<ForeignImport>,
    val decls: List<Decl>,
    val span: Span
) {
    var comment: Comment? = null

    var resolvedImports = emptyMap<String, String>()

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

sealed class ForeignImport(val span: Span) {
    class Type(val fqType: String, val alias: String?, span: Span) : ForeignImport(span)
    class Ctor(val type: String, val pars: List<String>, val alias: String, span: Span) : ForeignImport(span)
    class Method(val type: String, val name: String, val pars: List<String>, val alias: String?, span: Span) :
        ForeignImport(span)

    class Getter(val type: String, val name: String, val alias: String?, span: Span) : ForeignImport(span)
    class Setter(val type: String, val name: String, val alias: String, span: Span) : ForeignImport(span)
    class StaticMethod(val type: String, val name: String, val pars: List<String>, val alias: String?, span: Span) :
        ForeignImport(span)

    class StaticGetter(val type: String, val name: String, val alias: String?, span: Span) : ForeignImport(span)
    class StaticSetter(val type: String, val name: String, val alias: String, span: Span) : ForeignImport(span)
}

sealed class Decl {
    data class DataDecl(val name: String, val tyVars: List<String>, val dataCtors: List<DataConstructor>) : Decl()
    data class TypeDecl(val name: String, val type: Type) : Decl()
    data class ValDecl(val name: String, val binders: List<Binder>, val exp: Expr) : Decl()

    var comment: Comment? = null
    var span = Span.empty()

    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }
}

data class DataConstructor(val name: String, val args: List<Type>, val span: Span) {
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

    data class Constructor(val name: String, val alias: String? = null) : Expr() {
        override fun toString(): String = if (alias != null) "$alias.$name" else name
    }

    data class Lambda(val binders: List<Binder>, val body: Expr) : Expr() {
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
    fun withSpan(s: Span, e: Span) = apply { span = Span(s.startLine, s.startColumn, e.endLine, e.endColumn) }

    fun withComment(c: Comment?) = apply { comment = c }

    fun <T> withSpanAndComment(s: Spanned<T>) = apply {
        span = s.span
        comment = s.comment
    }
}

data class Binder(val name: String, val span: Span) {
    override fun toString(): String = name
}

data class LetDef(val name: Binder, val binders: List<Binder>, val expr: Expr, val type: Type? = null)

data class Case(val pattern: Pattern, val exp: Expr)

sealed class Pattern(open val span: Span) {
    data class Wildcard(override val span: Span) : Pattern(span)
    data class LiteralP(val lit: LiteralPattern, override val span: Span) : Pattern(span)
    data class Var(val name: String, override val span: Span) : Pattern(span)
    data class Ctor(val ctor: Expr.Constructor, val fields: List<Pattern>, override val span: Span) : Pattern(span)
    data class Parens(val pattern: Pattern, override val span: Span) : Pattern(span)
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

sealed class Type {
    data class TVar(val name: String, val alias: String? = null) : Type()
    data class TConstructor(val name: String, val types: List<Type> = listOf(), val alias: String? = null) : Type()
    data class TFun(val arg: Type, val ret: Type) : Type()
    data class TForall(val names: List<String>, val type: Type) : Type()
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

fun Type.TVar.fullname(): String = if (alias != null) "$alias.$name" else name
fun Type.TConstructor.fullname(): String = if (alias != null) "$alias.$name" else name
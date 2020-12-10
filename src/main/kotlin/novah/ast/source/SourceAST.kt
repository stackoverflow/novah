package novah.ast.source

import novah.frontend.Comment
import novah.frontend.ModuleExports
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.typechecker.Type

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

sealed class Import(val module: ModuleName) {
    data class Raw(val mod: ModuleName, val alias: String? = null) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>, val alias: String? = null) : Import(mod)

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
    data class ValDecl(val name: String, val exp: Expr) : Decl()

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
    data class IntE(val i: Long) : Expr()
    data class FloatE(val f: Double) : Expr()
    data class StringE(val s: String) : Expr()
    data class CharE(val c: Char) : Expr()
    data class Bool(val b: Boolean) : Expr()
    data class Var(val name: String, val moduleName: ModuleName = listOf()) : Expr()
    data class Operator(val name: String) : Expr()
    data class Lambda(val binder: String, val body: Expr, val nested: Boolean = false) : Expr()
    data class App(val fn: Expr, val arg: Expr) : Expr()
    data class If(val cond: Expr, val thenCase: Expr, val elseCase: Expr) : Expr()
    data class Let(val letDef: LetDef, val body: Expr) : Expr()
    data class Match(val exp: Expr, val cases: List<Case>) : Expr()
    data class Ann(val exp: Expr, val type: Type) : Expr()
    data class Do(val exps: List<Expr>) : Expr()
    // only used for formatting
    data class Parens(val exps: List<Expr>) : Expr()

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
        is Expr.Operator -> if (name == v) s else this
        is Expr.App -> {
            val left = fn.substVar(v, s)
            val right = arg.substVar(v, s)
            if (left == fn && right == arg) this else Expr.App(left, right).withSpan(span)
        }
        is Expr.Lambda -> {
            if (binder == v) this
            else {
                val b = body.substVar(v, s)
                if (body == b) this else Expr.Lambda(binder, b).withSpan(span)
            }
        }
        is Expr.Ann -> {
            val b = exp.substVar(v, s)
            if (exp == b) this else Expr.Ann(b, type)
        }
        is Expr.If -> {
            val c = cond.substVar(v, s)
            val t = thenCase.substVar(v, s)
            val e = elseCase.substVar(v, s)
            if (c == cond && t == thenCase && e == elseCase) this else Expr.If(c, t, e).withSpan(span)
        }
        is Expr.Let -> {
            val b = body.substVar(v, s)
            val def = letDef.substVar(v, s)
            if (b == body && def == letDef) this else Expr.Let(def, b).withSpan(span)
        }
        is Expr.Match -> {
            val e = exp.substVar(v, s)
            val cs = cases.map { it.substVar(v, s) }
            if (e == exp && cs == cases) this else Expr.Match(e, cs).withSpan(span)
        }
        is Expr.Do -> {
            val es = exps.map { it.substVar(v, s) }
            if (es == exps) this else Expr.Do(es).withSpan(span)
        }
        else -> error("absurd")
    }

fun Expr.Lambda.openLambda(s: Expr): Expr = body.substVar(binder, s)

fun Module.fullName() = name.joinToString(".")

fun Import.fullName() = module.joinToString(".")

private fun toComment(c: Comment): String {
    return if (c.isMulti) {
        "/**\n" + c.comment + "\n*/"
    } else {
        "// " + c.comment
    }
}

fun Module.show(): String {
    return """module ${fullName()}
            !
            !${imports.joinToString("\n") { it.show() }}
            !
            !${decls.joinToString("\n\n") { it.show() }}
        """.trimMargin("!")
}

fun Import.show(): String = when (this) {
    is Import.Raw -> "import ${fullName()}"
    is Import.Exposing -> "import ${fullName()} (${defs.joinToString(", ")})"
}

fun Decl.show(): String {
    val str = when (this) {
        is Decl.ValDecl -> {
            "$name = ${exp.show()}"
        }
        is Decl.DataDecl -> {
            val dataCts = dataCtors.joinToString("\n\t| ") { it.show() }
            "type $name ${tyVars.joinToString(" ")}\n\t= $dataCts"
        }
        is Decl.TypeDecl -> "$name :: $type"
    }
    return if (comment != null) {
        toComment(comment!!) + "\n$str"
    } else str
}

fun DataConstructor.show(): String {
    return if (args.isEmpty()) {
        name
    } else {
        "$name ${args.joinToString(" ") { it.toString() }}"
    }
}

fun Expr.show(tab: String = ""): String {
    return when (this) {
        is Expr.App -> {
            if (fn is Expr.App && fn.fn is Expr.Operator) {
                "(${fn.arg.show(tab)} ${fn.fn.show(tab)} ${arg.show(tab)})"
            } else "(${fn.show(tab)} ${arg.show(tab)})"
        }
        is Expr.Operator -> "`$name`"
        is Expr.Var -> {
            if (moduleName.isEmpty()) {
                name
            } else {
                "${moduleName.joinToString(".")}.$name"
            }
        }
        is Expr.IntE -> "$i"
        is Expr.FloatE -> "$f"
        is Expr.StringE -> "\"$s\""
        is Expr.CharE -> "'$c'"
        is Expr.Bool -> "$b"
        is Expr.Match -> {
            val cs = cases.joinToString("\n  $tab ") { it.show("$tab  ") }
            "case ${exp.show()} of {\n  $tab $cs\n$tab}"
        }
        is Expr.Lambda -> "#$nested(\\$binder -> " + body.show("$tab  ") + ")"
        is Expr.If -> {
            "if ${cond.show(tab)} else\n  ${tab}${thenCase.show(tab)} \n${tab}else\n$tab  ${elseCase.show(tab)}"
        }
        is Expr.Let -> {
            "let " + letDef.show(tab) + " in\n$tab" + body.show(tab)
        }
        is Expr.Ann -> exp.show(tab)
        is Expr.Do -> {
            val t = "$tab  "
            "do {\n$t" + exps.joinToString("\n$t") { it.show(t) } + "\n$tab}"
        }
        is Expr.Parens -> "(" + exps.joinToString(" ") { it.show(tab) } + ")"
    }
}

fun LetDef.show(tab: String = ""): String = if (type == null) {
    "$name = ${expr.show()}"
} else {
    "$name :: $type\n${tab}and $name = ${expr.show(tab)}"
}

fun Case.show(tab: String = ""): String {
    return "${pattern.show()} -> ${exp.show("$tab  ")}"
}

fun Pattern.show(): String = when (this) {
    is Pattern.Wildcard -> "_"
    is Pattern.LiteralP -> lit.show()
    is Pattern.Var -> name
    is Pattern.Ctor -> if (fields.isEmpty()) {
        name
    } else {
        "($name ${fields.joinToString(" ") { it.show() }})"
    }
}

fun LiteralPattern.show(): String = when (this) {
    is LiteralPattern.BoolLiteral -> "$b"
    is LiteralPattern.CharLiteral -> "'$c'"
    is LiteralPattern.StringLiteral -> "\"$s\""
    is LiteralPattern.IntLiteral -> "$i"
    is LiteralPattern.FloatLiteral -> "$f"
}
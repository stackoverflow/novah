package novah

typealias ModuleName = List<String>

data class Module(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: List<String>,
    val decls: List<Decl>
)

sealed class Import(val module: ModuleName) {
    data class Raw(val mod: ModuleName) : Import(mod)
    data class As(val mod: ModuleName, val alias: String) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>) : Import(mod)
    data class Hiding(val mod: ModuleName, val defs: List<String>) : Import(mod)
}

sealed class Decl {
    data class DataDecl(val name: String, val tyVars: List<TypeVar>, val dataCtors: List<DataConstructor>) : Decl()

    data class VarType(val name: String, val typ: Polytype) : Decl()

    data class VarDecl(val def: Expression.Let) : Decl()
}

data class DataConstructor(val name: String, val args: List<Monotype>)

sealed class Expression {
    data class IntE(val i: Long) : Expression()
    data class FloatE(val f: Double) : Expression()
    data class StringE(val s: String) : Expression()
    data class CharE(val c: Char) : Expression()
    data class Bool(val b: Boolean) : Expression()
    data class Var(val name: String) : Expression()
    data class Operator(val name: String) : Expression()
    data class Construction(val ctor: String, val fields: List<Expression>) : Expression()
    data class Lambda(val binder: String, val body: List<Expression>) : Expression()
    data class App(val fn: Expression, val arg: Expression) : Expression()
    data class If(val cond: Expression, val thenCase: List<Expression>, val elseCase: List<Expression>) : Expression()
    data class Let(val defs: List<Def>) : Expression()
    data class Match(val exp: Expression, val cases: List<Case>) : Expression()
}

data class Def(val name: String, val expr: Expression, val type: Polytype?)

data class Case(val pattern: Pattern, val exps: List<Expression>)

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

data class TypeVar(val v: String)

sealed class Monotype {
    data class Unknown(val i: Int) : Monotype()
    data class Var(val typ: TypeVar) : Monotype()
    data class Function(val par: Monotype, val ret: Monotype) : Monotype()
    data class Constructor(val name: String, val vars: List<Monotype>) : Monotype()
}

data class Polytype(val vars: List<TypeVar>, val type: Monotype)

////////////////////////////////
// AST functions
////////////////////////////////

private fun List<String>.joinShow(sep: String): String {
    return if (isEmpty()) {
        ""
    } else {
        " " + joinToString(sep)
    }
}

fun Module.fullName() = name.joinToString(".")

fun Import.fullName() = module.joinToString(".")

fun Module.show(): String {
    return """module ${fullName()} (${exports.joinToString(", ")})
            !
            !${imports.joinToString("\n") { it.show() }}
            !
            !${decls.joinToString("\n\n") { it.show() }}
        """.trimMargin("!")
}

fun Import.show(): String = when (this) {
    is Import.Raw -> "import ${fullName()}"
    is Import.As -> "import ${fullName()} as $alias"
    is Import.Exposing -> "import ${fullName()} exposing (${defs.joinToString(", ")})"
    is Import.Hiding -> "import ${fullName()} hiding (${defs.joinToString(", ")})"
}

fun Decl.show(): String = when (this) {
    is Decl.VarType -> "$name :: ${typ.show()}"
    is Decl.VarDecl -> {
        val let = def.defs[0]
        "${let.name} = ${let.expr.show()}"
    }
    is Decl.DataDecl -> {
        val dataCts = dataCtors.joinToString("\n\t| ") { it.show() }
        "data $name${tyVars.map { it.v }.joinShow(" ")} =\n\t$dataCts"
    }
}

fun DataConstructor.show(): String {
    return if (args.isEmpty()) {
        name
    } else {
        "$name ${args.joinToString(" ") { it.show() }}"
    }
}

fun Expression.show(tab: String = ""): String = when (this) {
    is Expression.App -> "(${fn.show(tab)} ${arg.show(tab)})"
    is Expression.Operator -> "`$name`"
    is Expression.Var -> name
    is Expression.IntE -> "$i"
    is Expression.FloatE -> "$f"
    is Expression.StringE -> "\"$s\""
    is Expression.CharE -> "'$c'"
    is Expression.Bool -> "$b"
    is Expression.Construction -> {
        if (fields.isEmpty()) ctor
        else "($ctor ${fields.joinToString(" ") { it.show() }})"
    }
    is Expression.Match -> {
        val cs = cases.joinToString("\n\t") { it.show() }
        "case ${exp.show()} of\n\t$cs"
    }
    is Expression.Lambda -> "(\\$binder -> " + body.joinToString("\n\t" + tab) { it.show(tab) } + ")"
    is Expression.If -> {
        val thenC = thenCase.joinToString("\n\t" + tab) { it.show(tab) }
        val elseC = elseCase.joinToString("\n\t" + tab) { it.show(tab) }
        "if ${cond.show()} \n\tthen $thenC \n\telse $elseC"
    }
    is Expression.Let -> "let " + defs.joinToString("\n\t" + tab) { it.show() }
}

fun Def.show(): String = "$name = ${expr.show()}" + (type?.show() ?: "")

fun Case.show(): String {
    val es = exps.joinToString("\n\t\t") { it.show() }
    return "${pattern.show()} -> $es"
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

fun TypeVar.show() = v

fun Monotype.show(): String = when (this) {
    is Monotype.Unknown -> "u$i"
    is Monotype.Var -> typ.show()
    is Monotype.Function -> "(${par.show()} -> ${ret.show()})"
    is Monotype.Constructor -> if (vars.isEmpty()) {
        name
    } else {
        "($name ${vars.joinToString(" ") { it.show() }})"
    }
}

fun Polytype.show(): String {
    return if (vars.isNotEmpty()) {
        "forall " + vars.joinToString(" ") { it.show() } + ". ${type.show()}"
    } else type.show()
}
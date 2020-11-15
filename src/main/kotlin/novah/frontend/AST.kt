package novah.frontend

typealias ModuleName = List<String>

data class Module(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: List<String>,
    val decls: List<Decl>
) {
    var comment: String? = null

}

sealed class Import(val module: ModuleName) {
    data class Raw(val mod: ModuleName) : Import(mod)
    data class As(val mod: ModuleName, val alias: String) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>) : Import(mod)
}

sealed class Decl {
    var comment: Comment? = null

    data class DataDecl(val name: String, val tyVars: List<TypeVar>, val dataCtors: List<DataConstructor>) : Decl()
    data class TypeDecl(val name: String, val typ: Polytype) : Decl()
    data class ValDecl(val name: String, val exp: Expression) : Decl()
}

data class DataConstructor(val name: String, val args: List<Monotype>)

sealed class Expression {
    data class IntE(val i: Long) : Expression()
    data class FloatE(val f: Double) : Expression()
    data class StringE(val s: String) : Expression()
    data class CharE(val c: Char) : Expression()
    data class Bool(val b: Boolean) : Expression()
    data class Var(val name: String, val moduleName: ModuleName = listOf()) : Expression()
    data class Operator(val name: String) : Expression()
    data class Construction(val ctor: String, val fields: List<Expression>) : Expression()
    data class Lambda(val binder: String, val body: Expression) : Expression()
    data class App(val fn: Expression, val arg: Expression) : Expression()
    data class If(val cond: Expression, val thenCase: Expression, val elseCase: Expression) : Expression()
    data class Let(val defs: List<Def>, val exp: Expression) : Expression()
    data class Match(val exp: Expression, val cases: List<Case>) : Expression()
    data class Ann(val exp: Expression, val type: Polytype) : Expression()
    data class Do(val exps: List<Expression>) : Expression()
}

data class Def(val name: String, val expr: Expression, val type: Polytype?)

data class Case(val pattern: Pattern, val exp: Expression)

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

sealed class TypeKind {
    object Mono : TypeKind()
    object Poly : TypeKind()
}

sealed class TType<T : TypeKind> {
    data class TVar<T : TypeKind>(val v: TypeVar) : TType<T>()
    data class TExists<T : TypeKind>(val i: Int) : TType<T>()
    data class TForall(val v: TypeVar, val type: TType<TypeKind.Poly>) : TType<TypeKind.Poly>()
    data class TFun<T : TypeKind>(val t1: TType<T>, val t2: TType<T>) : TType<T>()
}

////////////////////////////////
// AST functions
////////////////////////////////

fun Polytype.isMono(): Boolean = this.vars.isEmpty()

fun Monotype.toPoly(): Polytype = Polytype(listOf(), this)

private fun toComment(c: Comment): String {
    return if (c.isMulti) {
        "/**\n" + c.comment + "\n*/"
    } else {
        "// " + c.comment
    }
}

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
    return """module ${fullName()} exposing (${exports.joinToString(", ")})
            !
            !${imports.joinToString("\n") { it.show() }}
            !
            !${decls.joinToString("\n\n") { it.show() }}
        """.trimMargin("!")
}

fun Import.show(): String = when (this) {
    is Import.Raw -> "import ${fullName()}"
    is Import.As -> "import ${fullName()} as $alias"
    is Import.Exposing -> "import ${fullName()} (${defs.joinToString(", ")})"
}

fun Decl.show(): String {
    val str = when (this) {
        is Decl.ValDecl -> {
            "$name = ${exp.show()}"
        }
        is Decl.DataDecl -> {
            val dataCts = dataCtors.joinToString("\n\t| ") { it.show() }
            "type $name${tyVars.map { it.v }.joinShow(" ")}\n\t= $dataCts"
        }
        is Decl.TypeDecl -> "$name :: ${typ.show()}"
    }
    return if (comment != null) {
        toComment(comment!!) + "\n$str"
    } else str
}

fun DataConstructor.show(): String {
    return if (args.isEmpty()) {
        name
    } else {
        "$name ${args.joinToString(" ") { it.show() }}"
    }
}

fun Expression.show(tab: String = ""): String {
    return when (this) {
        is Expression.App -> "(${fn.show(tab)} ${arg.show(tab)})"
        is Expression.Operator -> "`$name`"
        is Expression.Var -> {
            if (moduleName.isEmpty()) {
                name
            } else {
                "${moduleName.joinToString(".")}.$name"
            }
        }
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
            val cs = cases.joinToString("\n  $tab ") { it.show("$tab  ") }
            "case ${exp.show()} of {\n  $tab $cs\n$tab}"
        }
        is Expression.Lambda -> "(\\$binder -> " + body.show("$tab  ") + ")"
        is Expression.If -> {
            "if ${cond.show(tab)} else\n  ${tab}${thenCase.show(tab)} \n${tab}else\n$tab  ${elseCase.show(tab)}"
        }
        is Expression.Let -> {
            "let " + defs.joinToString("\n" + tab + "and ") { it.show(tab) } + " in\n$tab" + exp.show(tab)
        }
        is Expression.Ann -> "${exp.show(tab)} :: ${type.show()}"
        is Expression.Do -> {
            val t = "$tab  "
            "do {\n$t" + exps.joinToString("\n$t") { it.show(t) } + "\n$tab}"
        }
    }
}

fun Def.show(tab: String = ""): String = if (type == null) {
    "$name = ${expr.show()}"
} else {
    "$name :: ${type.show()}\n${tab}and $name = ${expr.show(tab)}"
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
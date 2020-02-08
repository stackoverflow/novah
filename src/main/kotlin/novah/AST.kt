package novah

typealias ModuleName = List<String>

data class Module(
    val name: ModuleName,
    val imports: List<Import>,
    val exports: List<String>,
    val decls: List<Decl>
) {

    fun fullName() = name.joinToString(".")
}

sealed class Import(private val module: ModuleName) {
    fun fullName() = module.joinToString(".")

    data class Raw(val mod: ModuleName) : Import(mod)
    data class As(val mod: ModuleName, val alias: String) : Import(mod)
    data class Exposing(val mod: ModuleName, val defs: List<String>) : Import(mod)
    data class Hiding(val mod: ModuleName, val defs: List<String>) : Import(mod)
}

sealed class Decl {
    data class DataDecl(val name: String, val tyVars: List<TypeVar>, val dataCtors: List<DataConstructor>) : Decl()

    data class VarType(val name: String, val typ: Polytype) : Decl()

    data class VarDecl(
        val name: String,
        val args: List<String>,
        val exprs: List<Expression>
    ) : Decl()
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

data class TypeVar(val v: String) {
    override fun toString() = v
}

sealed class Monotype {
    data class Unknown(val i: Int) : Monotype() {
        override fun toString(): String = "unknown$i"
    }

    data class Var(val typ: TypeVar) : Monotype() {
        override fun toString(): String = typ.v
    }

    data class Function(val par: Monotype, val ret: Monotype) : Monotype() {
        override fun toString(): String = "($par -> $ret)"
    }

    data class Constructor(val name: String, val vars: List<Monotype>) : Monotype() {
        override fun toString(): String = name + if (vars.isEmpty()) "" else " " + vars.joinToString(" ")
    }
}

data class Polytype(val vars: List<TypeVar>, val type: Monotype) {
    override fun toString(): String {
        return if (vars.isNotEmpty()) {
            "forall " + vars.joinToString(" ") { it.v } + ". $type"
        } else "$type"
    }
}
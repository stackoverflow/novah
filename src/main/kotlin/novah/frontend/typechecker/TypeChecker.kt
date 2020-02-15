package novah.frontend.typechecker

import novah.Expression
import novah.Monotype
import novah.Monotype.*
import novah.Polytype
import novah.TypeVar
import novah.frontend.typechecker.TypeErrors as TE

private val emptyTVars = listOf<TypeVar>()
private val emptyTypes = listOf<Monotype>()

fun mono(type: String): Polytype = Polytype(emptyTVars, Var(TypeVar(type)))

fun monoCtor(type: String, vararg vals: Monotype): Polytype {
    return if(vals.isEmpty()) {
        Polytype(emptyTVars, Constructor(type, emptyTypes))
    } else {
        Polytype(emptyTVars, Constructor(type, vals.toList()))
    }
}

private fun typeError(exp: Expression, msg: String): Nothing {
    throw TypecheckError(exp, msg)
}

data class Env(val env: HashMap<String, Polytype>) {

    operator fun get(name: String): Polytype? = env[name]

    operator fun set(name: String, type: Polytype) {
        env[name] = type
    }
}

/**
 * A bidirectional type checker that can infer (synthesize)
 * and check types
 */
class TypeChecker(private val env: Env) {

    /**
     * The synthesize step of the type checker.
     * Try to infer the type of exp based on the provided context.
     */
    fun synthesize(ctx: Env, exp: Expression): Polytype {
        when(exp) {
            is Expression.Var -> ctx[exp.name] ?: typeError(exp, TE.typeNotFound(exp.name))
            is Expression.Bool -> env["Boolean"]
            is Expression.CharE -> env["Char"]
            is Expression.StringE -> env["String"]
        }
        TODO()
    }

    /**
     * The check step of the type checker.
     * Given a context, an expression and a type check
     * that this exp has this type.
     */
    fun check(ctx: Env, exp: Expression, type: Polytype) {

    }
}
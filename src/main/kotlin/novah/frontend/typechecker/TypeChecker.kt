package novah.frontend.typechecker

import novah.frontend.*
import novah.frontend.Monotype.*
import novah.frontend.typechecker.TypeErrors as TE

private val emptyTVars = listOf<TypeVar>()
private val emptyTypes = listOf<Monotype>()

fun mono(type: String): Polytype =
    Polytype(emptyTVars, Var(TypeVar(type)))

fun monoCtor(type: String, vararg vals: Monotype): Polytype {
    return if (vals.isEmpty()) {
        Polytype(emptyTVars, Constructor(type, emptyTypes))
    } else {
        Polytype(emptyTVars, Constructor(type, vals.toList()))
    }
}

private fun typeError(exp: Expression, msg: String): Nothing {
    throw TypecheckError(exp, msg)
}

data class Context(val env: HashMap<String, Polytype>) {

    operator fun get(name: String): Polytype? = env[name]

    operator fun set(name: String, type: Polytype) {
        env[name] = type
    }
}

/**
 * A bidirectional type checker
 */
class TypeChecker(private val env: Context) {

    var freshCounter = 0

    fun freshVar(): Polytype = Polytype(emptyTVars, Unknown(++freshCounter))

    fun synth(ctx: Context, exp: Expression): Polytype {
        if (exp.type !== null) {
            // check the expression against the annotated type
            check(ctx, exp, exp.type!!)
            return exp.type!!
        }
        return when (exp) {
            is Expression.IntE -> mono("Int")
            is Expression.FloatE -> mono("Float")
            is Expression.Bool -> mono("Boolean")
            is Expression.CharE -> mono("Char")
            is Expression.StringE -> mono("String")
            is Expression.Var -> ctx[exp.name] ?: typeError(exp, TE.typeNotFound(exp.name))
            is Expression.App -> {
                when (val typ = synth(ctx, exp.fn).type) {
                    is Monotype.Function -> {
                        check(ctx, exp.arg, typ.par.toPoly())
                        typ.ret.toPoly()
                    }
                    else -> typeError(exp, "TODO")
                }
            }
            else -> TODO("Subtyping rule here")
        }
    }

    fun check(ctx: Context, exp: Expression, poly: Polytype) {
        val type = poly.type
        when (exp) {
            is Expression.Lambda -> {
                when (type) {
                    is Monotype.Function -> {
                        ctx[exp.binder] = type.par.toPoly()
                        //check(ctx, exp.body, type.ret.toPoly())
                    }
                    else -> typeError(exp, "TODO")
                }
            }
            else -> TODO()
        }
    }
}
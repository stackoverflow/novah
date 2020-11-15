package novah.frontend.typechecker

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.toPersistentHashMap
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

typealias Context = PersistentMap<String, Polytype>

/**
 * A bidirectional type checker
 */
class TypeChecker(private val env: Context) {

    var freshCounter = 0

    fun freshVar(): Polytype = Polytype(emptyTVars, Unknown(++freshCounter))

    fun synth(ctx: Context, exp: Expression): Polytype {
        return when (exp) {
            is Expression.IntE -> mono("Int")
            is Expression.FloatE -> mono("Float")
            is Expression.Bool -> mono("Boolean")
            is Expression.CharE -> mono("Char")
            is Expression.StringE -> mono("String")
            is Expression.Ann -> {
                // check the expression against the annotated type
                check(ctx, exp.exp, exp.type)
                exp.type
            }
            is Expression.Var -> ctx[exp.name] ?: typeError(exp, TE.typeNotFound(exp.name))
            is Expression.Operator -> ctx[exp.name] ?: typeError(exp, TE.typeNotFound(exp.name))
            is Expression.Do -> {
                // the type of a statement is the type of the last expression
                exp.exps.map { synth(ctx, it) }.last()
            }
            is Expression.App -> {
                when (val typ = synth(ctx, exp.fn).type) {
                    is Monotype.Function -> {
                        check(ctx, exp.arg, typ.par.toPoly())
                        typ.ret.toPoly()
                    }
                    else -> typeError(exp, "TODO: expected type to be function")
                }
            }
            is Expression.If -> {
                check(ctx, exp.cond, mono("Boolean"))
                val t1 = synth(ctx, exp.thenCase)
                check(ctx, exp.elseCase, t1)
                t1
            }
            else -> TODO("Subsumption rule here. Whatever subtyping novah will have")
        }
    }

    fun check(ctx: Context, exp: Expression, poly: Polytype) {
        val type = poly.type
        when (exp) {
            is Expression.Lambda -> {
                when (type) {
                    is Monotype.Function -> {
                        val newCtx = ctx.put(exp.binder, type.par.toPoly())
                        check(newCtx, exp.body, type.ret.toPoly())
                    }
                    else -> typeError(exp, "TODO: expected type to be function")
                }
            }
            is Expression.Do -> {
                // we can't really check but the last, need to synthesize
                exp.exps.dropLast(1).forEach { synth(ctx, it) }
                check(ctx, exp.exps.last(), poly)
            }
            is Expression.If -> {
                check(ctx, exp.cond, mono("Boolean"))
                check(ctx, exp.thenCase, poly)
                check(ctx, exp.elseCase, poly)
            }
            else -> TODO("Subsumption rule here. Whatever subtyping novah will have")
        }
    }
}
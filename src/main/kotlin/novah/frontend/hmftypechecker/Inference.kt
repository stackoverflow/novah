package novah.frontend.hmftypechecker

import novah.Util.internalError
import novah.ast.canonical.Expr
import novah.ast.canonical.FunparPattern
import novah.frontend.Span
import novah.frontend.error.Errors

class Inference(private val tc: Typechecker, private val uni: Unification, private val sub: Subsumption) {

    fun infer(env: Env, level: Level, exp: Expr): Type {
        return when (exp) {
            is Expr.IntE -> Type.TConst("Int")
            is Expr.LongE -> Type.TConst("Long")
            is Expr.FloatE -> Type.TConst("Float")
            is Expr.DoubleE -> Type.TConst("Double")
            is Expr.CharE -> Type.TConst("Char")
            is Expr.Bool -> Type.TConst("Boolean")
            is Expr.StringE -> Type.TConst("String")
            is Expr.Unit -> Type.TConst("Unit")
            is Expr.Var -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.Constructor -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.NativeFieldGet -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.NativeFieldSet -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.NativeMethod -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.NativeConstructor -> {
                env.lookup(exp.name.rawName()) ?: inferError(Errors.undefinedVar(exp.name), exp.span)
            }
            is Expr.Lambda -> {
                // TODO: fix me
                val name = if (exp.pattern is FunparPattern.Bind) exp.pattern.binder.name.rawName() else "x"
                val newEnv = env.makeExtension()
                val param = tc.newVar(level + 1)
                newEnv.extend(name, param)
                
                val infRetType = infer(newEnv, level + 1, exp.body)
                val retType = if (isAnnotated(exp.body)) infRetType else tc.instantiate(level + 1, infRetType)
                
                if (!retType.isMono()) inferError(Errors.polyParameterToLambda(retType.show()), Span.empty())
                else generalize(level, Type.TArrow(listOf(param), retType))
            }
            is Expr.Let -> {
                val varType = infer(env, level + 1, exp.letDef.expr)
                env.extend(exp.letDef.binder.name.rawName(), varType)
                infer (env, level, exp.body)
            }
            is Expr.App -> {
                val nextLevel = level + 1
                val fnType = tc.instantiate(nextLevel, infer(env, nextLevel, exp.fn))
                val (paramTypes, retType) = matchFunType(1, fnType)
                inferArgs(env, nextLevel, paramTypes, listOf(exp.arg))
                generalize(level, tc.instantiate(nextLevel, retType))
            }
            is Expr.Ann -> {
//                val (_, type) = tc.instantiateTypeAnnotation(level, exp.annType)
//                val expType = infer(env, level, exp.exp)
//                sub.subsume(level, type, expType)
//                type
                TODO()
            }
            else -> TODO()
        }
    }
    
    fun inferArgs(env: Env, level: Level, paramTypes: List<Type>, argList: List<Expr>) {
        val pars = paramTypes.zip(argList)
        fun ordering(type: Type): Int {
            val un = type.unlink()
            return if (un is Type.TVar && un.tvar is TypeVar.Unbound) 1 else 0
        }
        pars.sortedBy { ordering(it.first) }.forEach { (type, arg) ->
            val argType = infer(env, level, arg)
            if (isAnnotated(arg)) uni.unify(type, argType) else sub.subsume(level, type, argType)
        }
    }

    fun matchFunType(numParams: Int, t: Type): Pair<List<Type>, Type> = when {
        t is Type.TArrow -> {
            if (numParams != t.args.size) internalError("unexpected number of arguments to function: $numParams")
            t.args to t.ret
        }
        t is Type.TVar && t.tvar is TypeVar.Link -> matchFunType(numParams, (t.tvar as TypeVar.Link).type)
        t is Type.TVar && t.tvar is TypeVar.Unbound -> {
            val unb = t.tvar as TypeVar.Unbound
            val params = (1..numParams).map { tc.newVar(unb.level) }.reversed()
            val retur = tc.newVar(unb.level)
            t.tvar = TypeVar.Link(Type.TArrow(params, retur))
            params to retur
        }
        else -> internalError("expected function type")
    }

    fun generalize(level: Level, type: Type): Type {
        val ids = mutableListOf<Id>()
        fun go(t: Type) {
            when {
                t is Type.TVar && t.tvar is TypeVar.Link -> go((t.tvar as TypeVar.Link).type)
                t is Type.TVar && t.tvar is TypeVar.Generic -> internalError("unexpected generic var in generalize")
                t is Type.TVar && t.tvar is TypeVar.Unbound -> {
                    val unb = t.tvar as TypeVar.Unbound
                    if (unb.level > level) {
                        t.tvar = TypeVar.Bound(unb.id)
                        if (unb.id !in ids) {
                            ids += unb.id
                        }
                    }
                }
                t is Type.TApp -> {
                    go(t.type)
                    t.types.forEach(::go)
                }
                t is Type.TArrow -> {
                    t.args.forEach(::go)
                    go(t.ret)
                }
                t is Type.TForall -> go(t.type)
                t is Type.TConst -> {
                }
            }
        }

        go(type)
        return if (ids.isEmpty()) type
        else Type.TForall(ids, type)
    }
    
    tailrec fun isAnnotated(exp: Expr): Boolean = when (exp) {
        is Expr.Ann -> true
        is Expr.Let -> isAnnotated(exp.body)
        else -> false
    }
}
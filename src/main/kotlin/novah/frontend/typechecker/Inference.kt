package novah.frontend.typechecker

import novah.frontend.*
import novah.frontend.typechecker.InferContext._apply
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.store
import novah.frontend.typechecker.Subsumption.subsume
import novah.frontend.typechecker.WellFormed.wfContext
import novah.frontend.typechecker.WellFormed.wfType

object Inference {

    fun generalize(unsolved: List<String>, type: Type): Type {
        val ns = type.unsolvedInType(unsolved)
        val m = mutableMapOf<String, Type.TVar>()
        ns.forEach { x ->
            val name = store.fresh(x)
            m[x] = Type.TVar(name)
        }
        var c = type.substTMetas(m)
        for (i in ns.indices.reversed()) {
            c = Type.TForall(m[ns[i]]!!.name, c)
        }
        return c
    }

    fun generalizeFrom(marker: String, type: Type): Type =
        generalize(context.leaveWithUnsolved(marker), type)

    fun typesynth(exp: Expr): Type {
        return when (exp) {
            is Expr.IntE -> Type.TInt
            is Expr.FloatE -> Type.TFloat
            is Expr.StringE -> Type.TString
            is Expr.CharE -> Type.TChar
            is Expr.Bool -> Type.TBoolean
            is Expr.Var -> {
                val x = context.lookup<Elem.CVar>(exp.name)
                    ?: inferError("undefined variable ${exp.name}", exp)
                x.type
            }
            is Expr.Operator -> {
                val x = context.lookup<Elem.CVar>(exp.name)
                    ?: inferError("undefined operator ${exp.name}", exp)
                x.type
            }
            is Expr.Lambda -> {
                val x = store.fresh(exp.binder)
                val a = store.fresh(exp.binder)
                val b = store.fresh(exp.binder)
                val ta = Type.TMeta(a)
                val tb = Type.TMeta(b)
                val m = store.fresh("m")
                context.enter(m, Elem.CTMeta(a), Elem.CTMeta(b), Elem.CVar(x, ta))
                typecheck(exp.openLambda(Expr.Var(x)), tb)
                val ty = _apply(Type.TFun(ta, tb))
                generalizeFrom(m, ty)
            }
            is Expr.App -> {
                val left = typesynth(exp.fn)
                typeappsynth(_apply(left), exp.arg)
            }
            is Expr.Ann -> {
                val ty = exp.type
                wfType(ty)
                typecheck(exp.exp, ty)
                ty
            }
            is Expr.If -> {
                typecheck(exp.cond, Type.TBoolean)
                val thenType = typesynth(exp.thenCase)
                typecheck(exp.elseCase, thenType)
                thenType
            }
            is Expr.Let -> {
                // infer the binding
                val ld = exp.letDef
                val name = store.fresh(ld.name)
                val binding = if (ld.type != null) {
                    wfType(ld.type)
                    typecheck(ld.expr, ld.type)
                    Elem.CVar(name, ld.type)
                } else {
                    val typ = typesynth(ld.expr)
                    Elem.CVar(name, typ)
                }

                // add the binding to the context
                val m = store.fresh("m")
                context.enter(m, binding)
                val subExp = exp.body.substVar(ld.name, Expr.Var(name))

                // infer the body
                generalizeFrom(m, _apply(typesynth(subExp)))
            }
            is Expr.Do -> {
                var ty: Type? = null
                for (e in exp.exps) {
                    ty = typesynth(e)
                }
                ty ?: inferError("got empty `do` statement", exp)
            }
            else -> inferError("cannot infer type for $exp", exp)
        }
    }

    fun typecheck(expr: Expr, type: Type) {
        when {
            expr is Expr.IntE && type is Type.TInt -> return
            expr is Expr.FloatE && type is Type.TFloat -> return
            expr is Expr.StringE && type is Type.TString -> return
            expr is Expr.CharE && type is Type.TChar -> return
            expr is Expr.Bool && type is Type.TBoolean -> return
            type is Type.TForall -> {
                val x = store.fresh(type.name)
                val m = store.fresh("m")
                context.enter(m, Elem.CTVar(x))
                typecheck(expr, Type.openTForall(type, Type.TVar(x)))
                context.leave(m)
            }
            type is Type.TFun && expr is Expr.Lambda -> {
                val x = store.fresh(expr.binder)
                val m = store.fresh("m")
                context.enter(m, Elem.CVar(x, type.arg))
                typecheck(expr.openLambda(Expr.Var(x)), type.ret)
                context.leave(m)
            }
            else -> {
                val ty = typesynth(expr)
                subsume(_apply(ty), _apply(type), expr)
            }
        }
    }

    fun typeappsynth(type: Type, expr: Expr): Type {
        return when (type) {
            is Type.TForall -> {
                val x = store.fresh(type.name)
                context.add(Elem.CTMeta(x))
                typeappsynth(Type.openTForall(type, Type.TMeta(x)), expr)
            }
            is Type.TMeta -> {
                val x = type.name
                val a = store.fresh(x)
                val b = store.fresh(x)
                val ta = Type.TMeta(a)
                val tb = Type.TMeta(b)
                context.replace<Elem.CTMeta>(
                    x, listOf(
                        Elem.CTMeta(b),
                        Elem.CTMeta(a),
                        Elem.CTMeta(x, Type.TFun(ta, tb))
                    )
                )
                typecheck(expr, ta)
                tb
            }
            is Type.TFun -> {
                typecheck(expr, type.arg)
                type.ret
            }
            else -> inferError("Cannot typeappsynth: $type @ $expr", expr)
        }
    }

    fun infer(expr: Expr): Type {
        store.reset()
        wfContext()
        val m = store.fresh("m")
        context.enter(m)
        val ty = generalizeFrom(m, _apply(typesynth(expr)))
        if (!context.isComplete()) inferError("Context is not complete", expr)
        return ty
    }

    fun infer(mod: Module): Map<String, Type> {
        val m = mutableMapOf<String, Type>()
        mod.decls.filterIsInstance<Decl.ValDecl>().forEach { decl ->
            val ty = infer(decl.exp)
            context.add(Elem.CVar(decl.name, ty))
            m[decl.name] = ty
        }
        return m
    }
}
package novah.frontend.typechecker

import novah.ast.canonical.*
import novah.frontend.Span
import novah.frontend.typechecker.InferContext._apply
import novah.frontend.typechecker.InferContext.context
import novah.frontend.typechecker.InferContext.store
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tByte
import novah.frontend.typechecker.Prim.tChar
import novah.frontend.typechecker.Prim.tDouble
import novah.frontend.typechecker.Prim.tFloat
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tLong
import novah.frontend.typechecker.Prim.tShort
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Subsumption.subsume
import novah.frontend.typechecker.WellFormed.wfContext
import novah.frontend.typechecker.WellFormed.wfType

object Inference {

    fun generalize(unsolved: List<String>, type: Type, expr: Expr): Type {
        val ns = type.unsolvedInType(unsolved)
        val m = mutableMapOf<String, Type.TVar>()
        ns.forEach { x ->
            val name = store.fresh(x)
            m[x] = Type.TVar(name)
        }
        if (m.isNotEmpty()) expr.resolveUnsolved(m)
        var c = type.substTMetas(m)
        for (i in ns.indices.reversed()) {
            c = Type.TForall(m[ns[i]]!!.name, c)
        }
        return c
    }

    fun generalizeFrom(marker: String, type: Type, expr: Expr): Type {
        expr.resolveMetas(context)
        return generalize(context.leaveWithUnsolved(marker), type, expr)
    }

    fun typesynth(exp: Expr): Type {
        return when (exp) {
            is Expr.IntE -> exp.withType(tInt)
            is Expr.LongE -> exp.withType(tLong)
            is Expr.FloatE -> exp.withType(tFloat)
            is Expr.DoubleE -> exp.withType(tDouble)
            is Expr.StringE -> exp.withType(tString)
            is Expr.CharE -> exp.withType(tChar)
            is Expr.Bool -> exp.withType(tBoolean)
            is Expr.Var -> {
                val x = context.lookup<Elem.CVar>(exp.canonicalName())
                    ?: inferError("undefined variable ${exp.name}", exp)
                exp.withType(x.type)
            }
            is Expr.Lambda -> {
                val x = store.fresh(exp.binder)
                val a = store.fresh(exp.binder)
                val b = store.fresh(exp.binder)
                val ta = Type.TMeta(a)
                val tb = Type.TMeta(b)
                val m = store.fresh("m")
                context.enter(m, Elem.CTMeta(a), Elem.CTMeta(b), Elem.CVar(x, ta))
                typecheck(exp.openLambda(Expr.Var(x, Span.empty())), tb)

                val ty = _apply(Type.TFun(ta, tb))
                exp.withType(generalizeFrom(m, ty, exp))
            }
            is Expr.App -> {
                val left = typesynth(exp.fn)
                exp.withType(typeappsynth(_apply(left), exp.arg))
            }
            is Expr.Ann -> {
                val ty = exp.annType
                wfType(ty)
                typecheck(exp.exp, ty)
                // set not only the type of the annotation but the type of the inner expression
                exp.exp.withType(ty)
                exp.withType(ty)
            }
            is Expr.If -> {
                typecheck(exp.cond, tBoolean)
                val tt = typesynth(exp.thenCase)
                val te = typesynth(exp.elseCase)
                subsume(_apply(tt), _apply(te), exp)
                exp.withType(tt)
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
                val subExp = exp.body.substVar(ld.name, Expr.Var(name, Span.empty()))

                // infer the body
                exp.withType(generalizeFrom(m, _apply(typesynth(subExp)), exp))
            }
            is Expr.Do -> {
                var ty: Type? = null
                for (e in exp.exps) {
                    ty = typesynth(e)
                }
                exp.withType(ty ?: inferError("got empty `do` statement", exp))
            }
            else -> inferError("cannot infer type for $exp", exp)
        }
    }

    fun typecheck(expr: Expr, type: Type) {
        when {
            expr is Expr.IntE && type == tByte && expr.v >= Byte.MIN_VALUE && expr.v <= Byte.MAX_VALUE ->
                expr.withType(tByte)
            expr is Expr.IntE && type == tShort && expr.v >= Short.MIN_VALUE && expr.v <= Short.MAX_VALUE ->
                expr.withType(tShort)
            expr is Expr.IntE && type == tInt -> expr.withType(tInt)
            expr is Expr.LongE && type == tLong -> expr.withType(tLong)
            expr is Expr.FloatE && type == tFloat -> expr.withType(tFloat)
            expr is Expr.DoubleE && type == tDouble -> expr.withType(tDouble)
            expr is Expr.StringE && type == tString -> expr.withType(tString)
            expr is Expr.CharE && type == tChar -> expr.withType(tChar)
            expr is Expr.Bool && type == tBoolean -> expr.withType(tBoolean)
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
                typecheck(expr.openLambda(Expr.Var(x, Span.empty())), type.ret)
                context.leave(m)
                expr.withType(type)
            }
            expr is Expr.If -> {
                typecheck(expr.cond, tBoolean)
                typecheck(expr.thenCase, type)
                typecheck(expr.elseCase, type)
                if (expr.thenCase.type != null) expr.withType(expr.thenCase.type!!)
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
        val ty = generalizeFrom(m, _apply(typesynth(expr)), expr)
        if (!context.isComplete()) inferError("Context is not complete", expr)
        return ty
    }

    fun infer(mod: Module): Map<String, Type> {
        val m = mutableMapOf<String, Type>()

        val ctorTypes = mutableListOf<Type>()
        mod.decls.filterIsInstance<Decl.DataDecl>().forEach { ddecl ->
            dataDeclToType(ddecl, mod.name).forEach { el ->
                context.add(el)
                if (el is Elem.CVar) ctorTypes += el.type
            }
        }
        ctorTypes.forEach { wfType(it) }

        val vals = mod.decls.filterIsInstance<Decl.ValDecl>()
        vals.forEach { decl ->
            val expr = decl.exp
            if (expr is Expr.Ann) {
                wfType(expr.annType)
                context.add(Elem.CVar(decl.name, expr.annType))
            } else {
                val t = store.fresh("t")
                context.add(Elem.CVar(decl.name, Type.TForall(t, Type.TVar(t))))
            }
        }

        vals.forEach { decl ->
            val ty = infer(decl.exp)
            context.replaceCVar(decl.name, Elem.CVar(decl.name, ty))
            m[decl.name] = ty
        }
        return m
    }

    /**
     * Takes a data declaration and returns all the types and
     * variables (constructors) that should be added to the context.
     */
    private fun dataDeclToType(dd: Decl.DataDecl, moduleName: String): List<Elem> {
        fun nestForalls(acc: List<String>, type: Type): Type {
            return if (acc.isEmpty()) type
            else Type.TForall(acc.first(), nestForalls(acc.drop(1), type))
        }

        fun nestFuns(acc: List<Type>): Type {
            return if (acc.size == 1) acc.first()
            else Type.TFun(acc.first(), nestFuns(acc.drop(1)))
        }

        val elems = mutableListOf<Elem>()
        val typeName = "$moduleName." + dd.name
        elems += Elem.CTVar(typeName, dd.tyVars.size)

        val innerTypes = dd.tyVars.map { Type.TVar(it) }
        val dataType = Type.TConstructor(typeName, innerTypes)

        dd.dataCtors.forEach { ctor ->
            val type = nestForalls(dd.tyVars, nestFuns(ctor.args + dataType))
            elems.add(Elem.CVar(ctor.name, type))
        }

        return elems
    }
}
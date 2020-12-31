package novah.frontend.typechecker

import novah.Util.hasDuplicates
import novah.Util.internalError
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

    fun generalize(unsolved: List<Name>, type: Type): Type {
        val ns = type.unsolvedInType(unsolved)
        val m = mutableMapOf<Name, Type.TVar>()
        ns.forEach { x ->
            val tvar = Type.TVar(store.fresh(x))
            Subsumption.setSolved(x, tvar)
            m[x] = tvar
        }
        var c = type.substTMetas(m)
        for (i in ns.indices.reversed()) {
            c = Type.TForall(m[ns[i]]!!.name, c)
        }
        return c
    }

    fun generalizeFrom(marker: Name, type: Type): Type =
        generalize(context.leaveWithUnsolved(marker), type)

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
                val x = context.lookup<Elem.CVar>(exp.alias ?: exp.name)
                    ?: inferError("undefined variable ${exp.name}", exp)
                exp.withType(x.type)
            }
            is Expr.Constructor -> {
                val x = context.lookup<Elem.CVar>(exp.alias ?: exp.name)
                    ?: inferError("undefined constructor ${exp.name}", exp)
                exp.withType(x.type)
            }
            is Expr.Lambda -> {
                val binder = exp.binder.name
                checkShadow(binder, exp.binder.span)

                val x = store.fresh(binder)
                val a = store.fresh(binder)
                val b = store.fresh(binder)
                val ta = Type.TMeta(a)
                val tb = Type.TMeta(b)
                val m = store.fresh("m")
                context.enter(m, Elem.CTMeta(a), Elem.CTMeta(b), Elem.CVar(x, ta))
                typecheck(exp.aliasLambda(x), tb)

                val ty = _apply(Type.TFun(ta, tb))
                exp.withType(generalizeFrom(m, ty))
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
                subsume(_apply(tt), _apply(te), exp.span)
                exp.withType(tt)
            }
            is Expr.Let -> {
                // infer the binding
                val ld = exp.letDef
                val binder = ld.binder.name
                checkShadow(binder, ld.binder.span)

                val name = store.fresh(binder)
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
                val subExp = exp.body.aliasVar(binder, name)

                // infer the body
                exp.withType(generalizeFrom(m, _apply(typesynth(subExp))))
            }
            is Expr.Do -> {
                var ty: Type? = null
                for (e in exp.exps) {
                    ty = typesynth(e)
                }
                exp.withType(ty ?: inferError("got empty `do` statement", exp))
            }
            is Expr.Match -> {
                val expType = typesynth(exp.exp)
                var resType: Type? = null

                exp.cases.forEach { case ->
                    val vars = typecheckpattern(case.pattern, expType)
                    withEnteringContext(vars) {
                        if (resType == null) resType = typesynth(case.exp)
                        else typecheck(case.exp, resType!!)
                    }
                }
                exp.withType(resType ?: internalError("empty pattern matching: $exp"))
            }
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
                val x = store.fresh(expr.binder.name)
                val m = store.fresh("m")
                context.enter(m, Elem.CVar(x, type.arg))
                typecheck(expr.aliasLambda(x), type.ret)
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
                subsume(_apply(ty), _apply(type), expr.span)
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

    private fun typecheckpattern(pat: Pattern, type: Type): List<Elem.CVar> {
        tailrec fun peelArgs(args: List<Type>, t: Type): Pair<List<Type>, Type> = when (t) {
            is Type.TFun -> {
                if (t.ret is Type.TFun) peelArgs(args + t.arg, t.ret)
                else args + t.arg to t.ret
            }
            else -> args to t
        }

        return when (pat) {
            is Pattern.LiteralP -> {
                typecheck(pat.lit.e, type)
                listOf()
            }
            is Pattern.Wildcard -> listOf()
            is Pattern.Var -> {
                checkShadow(pat.name, pat.span)
                listOf(Elem.CVar(pat.name, type))
            }
            is Pattern.Ctor -> {
                val ctorTyp = typesynth(pat.ctor)

                val (ctorTypes, ret) = peelArgs(listOf(), instantiateForalls(ctorTyp))
                subsume(ret, _apply(type), pat.span)

                val diff = ctorTypes.size - pat.fields.size
                if (diff > 0) inferError("too few parameters given to type constructor ${pat.ctor.name}", pat.span)
                if (diff < 0) inferError("too many parameters given to type constructor ${pat.ctor.name}", pat.span)

                if (ctorTypes.isEmpty()) listOf()
                else {
                    val vars = mutableListOf<Elem.CVar>()
                    ctorTypes.zip(pat.fields).forEach { (type, pattern) ->
                        vars.addAll(typecheckpattern(pattern, type))
                    }
                    val varNames = vars.map { it.name }
                    if (varNames.hasDuplicates()) inferError(
                        "overlapping names in binder ${varNames.joinToString()}",
                        pat.span
                    )
                    vars
                }
            }
        }
    }

    fun infer(expr: Expr): Type {
        store.reset()
        Subsumption.cleanSolvedMetas()
        wfContext()
        val m = store.fresh("m")
        context.enter(m)
        val ty = generalizeFrom(m, _apply(typesynth(expr)))

        val solvedMetas = Subsumption.getSolvedMetas()
        if (solvedMetas.isNotEmpty())
            expr.resolveUnsolved(solvedMetas)

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
            val name = decl.name.raw()
            if (expr is Expr.Ann) {
                wfType(expr.annType)
                context.add(Elem.CVar(name, expr.annType))
            } else {
                val t = store.fresh("t")
                context.add(Elem.CVar(name, Type.TForall(t, Type.TVar(t))))
            }
        }

        vals.forEach { decl ->
            val ty = infer(decl.exp)
            val name = decl.name.raw()
            context.replaceCVar(name, Elem.CVar(name, ty))
            m[decl.name] = ty
        }
        return m
    }

    /**
     * Takes a data declaration and returns all the types and
     * variables (constructors) that should be added to the context.
     */
    private fun dataDeclToType(dd: Decl.DataDecl, moduleName: String): List<Elem> {
        fun nestForalls(acc: List<Name>, type: Type): Type {
            return if (acc.isEmpty()) type
            else Type.TForall(acc.first(), nestForalls(acc.drop(1), type))
        }

        fun nestFuns(acc: List<Type>): Type {
            return if (acc.size == 1) acc.first()
            else Type.TFun(acc.first(), nestFuns(acc.drop(1)))
        }

        val elems = mutableListOf<Elem>()
        val typeName = "$moduleName.${dd.name}".raw()
        elems += Elem.CTVar(typeName, dd.tyVars.size)

        val innerTypes = dd.tyVars.map { Type.TVar(it.raw()) }
        val dataType = Type.TConstructor(typeName, innerTypes)

        dd.dataCtors.forEach { ctor ->
            val type = nestForalls(dd.tyVars.map(Name::Raw), nestFuns(ctor.args + dataType))
            elems.add(Elem.CVar(ctor.name.raw(), type))
        }

        return elems
    }

    private fun instantiateForalls(type: Type): Type = when (type) {
        is Type.TForall -> {
            val x = store.fresh(type.name)
            context.add(Elem.CTMeta(x))
            instantiateForalls(Type.openTForall(type, Type.TMeta(x)))
        }
        is Type.TFun -> Type.TFun(instantiateForalls(type.arg), instantiateForalls(type.ret))
        is Type.TConstructor -> Type.TConstructor(type.name, type.types.map { instantiateForalls(it) })
        else -> type
    }

    /**
     * Check if `name` is shadowing some variable
     * and throw an error if that's the case.
     */
    private fun checkShadow(name: Name, span: Span) {
        val shadow = context.lookupShadow<Elem.CVar>(name)
        if (shadow != null) inferError("Variable $name is shadowed", span)
    }

    private inline fun <T> withEnteringContext(vars: List<Elem.CVar>, f: () -> T): T {
        return if (vars.isEmpty()) f()
        else {
            val m = store.fresh("m")
            context.enter(m, *vars.toTypedArray())
            val res = f()
            context.leave(m)
            res
        }
    }
}
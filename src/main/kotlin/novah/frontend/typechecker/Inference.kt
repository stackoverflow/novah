package novah.frontend.typechecker

import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.FunparPattern
import novah.ast.canonical.Module
import novah.frontend.Span
import novah.frontend.typechecker.Prim.tBoolean
import novah.frontend.typechecker.Prim.tByte
import novah.frontend.typechecker.Prim.tChar
import novah.frontend.typechecker.Prim.tDouble
import novah.frontend.typechecker.Prim.tFloat
import novah.frontend.typechecker.Prim.tInt
import novah.frontend.typechecker.Prim.tLong
import novah.frontend.typechecker.Prim.tShort
import novah.frontend.typechecker.Prim.tString
import novah.frontend.typechecker.Prim.tUnit
import novah.frontend.typechecker.Subsumption.subsume
import novah.frontend.typechecker.Type.Companion.openTForall
import novah.frontend.error.Errors as E
import novah.frontend.typechecker.Typechecker.freshName
import novah.frontend.typechecker.WellFormed.wfType
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

object Inference {

    fun infer(ctx: Context, mod: Module): ModuleEnv {
        val decls = mutableMapOf<String, DeclRef>()
        val types = mutableMapOf<String, TypeDeclRef>()

//        mod.decls.filterIsInstance<Decl.DataDecl>().forEach { ddecl ->
//            val (ddtype, ddelem) = dataDeclToType(ddecl, mod.name)
//            types[ddecl.name] = TypeDeclRef(ddtype, ddecl.visibility, ddecl.dataCtors.map { it.name })
//            ictx.context.add(ddelem)
//
//            dataConstructorsToType(ddecl, ddtype).forEach { (ctor, type) ->
//                decls[ctor.name] = DeclRef(type, ctor.visibility)
//                wf.wfType(type, ctor.span)
//                ictx.context.add(Elem.CVar(ctor.name.raw(), type))
//            }
//        }
//
//        val vals = mod.decls.filterIsInstance<Decl.ValDecl>()
//        vals.forEach { decl ->
//            val expr = decl.exp
//            val name = decl.name.raw()
//            if (expr is Expr.Ann) {
//                wf.wfType(expr.annType, expr.span)
//                ictx.context.add(Elem.CVar(name, expr.annType))
//            } else {
//                val t = store.fresh("t")
//                ictx.context.add(Elem.CVar(name, Type.TForall(t, Type.TVar(t))))
//            }
//        }
//
//        val errorNames = wf.wfContext()
//        val errors = errorNames.map { err ->
//            val decl = vals.find { it.name == err.rawName() } ?: internalError("Could not find defined variable $err.")
//            val msg = if (decl.name[0].isUpperCase()) E.duplicatedType(err)
//            else E.duplicatedVariable(err)
//            CompilerProblem(msg, ProblemContext.TYPECHECK, decl.span, mod.sourceName, mod.name)
//        }
//        if (errors.isNotEmpty()) throw CompilationError(errors)
//
//        vals.forEach { decl ->
//            val ty = infer(decl.exp)
//            val name = decl.name.raw()
//            ictx.context.replaceCVar(name, Elem.CVar(name, ty))
//            decls[decl.name] = DeclRef(ty, decl.visibility)
//        }
        return ModuleEnv(decls, types)
    }

    /**
     * The inference mode of the type checker.
     * Synthesizes a type for the given expression
     */
    fun infer(ctx: Context, exp: Expr): Pair<Type, Context> {
        return when (exp) {
            is Expr.IntE -> exp.withType(tInt) to ctx
            is Expr.LongE -> exp.withType(tLong) to ctx
            is Expr.FloatE -> exp.withType(tFloat) to ctx
            is Expr.DoubleE -> exp.withType(tDouble) to ctx
            is Expr.CharE -> exp.withType(tChar) to ctx
            is Expr.Bool -> exp.withType(tBoolean) to ctx
            is Expr.StringE -> exp.withType(tString) to ctx
            is Expr.Unit -> exp.withType(tUnit) to ctx
            is Expr.Var -> {
                val x = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(x.type) to ctx
            }
            is Expr.Constructor -> {
                val x = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(x.type) to ctx
            }
            is Expr.NativeFieldGet -> {
                val ty = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type) to ctx
            }
            is Expr.NativeFieldSet -> {
                val ty = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type) to ctx
            }
            is Expr.NativeMethod -> {
                val ty = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type) to ctx
            }
            is Expr.NativeConstructor -> {
                val ty = ctx.lookup<CVar>(exp.name) ?: inferError(E.undefinedVar(exp.name), exp.span)
                exp.withType(ty.type) to ctx
            }
            is Expr.Lambda -> {
                when (val pattern = exp.pattern) {
                    is FunparPattern.Ignored -> {
                        val bodyType = infer(ctx, exp.body).first
                        val tvar = freshName("t")
                        val ty = TForall(tvar, TArrow(TVar(tvar), bodyType))
                        exp.withType(ty) to ctx
                    }
                    is FunparPattern.Unit -> {
                        val bodyType = infer(ctx, exp.body).first
                        val ty = TArrow(tUnit, bodyType)
                        exp.withType(ty) to ctx
                    }
                    is FunparPattern.Bind -> {
                        val binder = pattern.binder.name
                        checkShadow(ctx, binder, exp.pattern.span)

                        val a = freshName(binder)
                        val b = freshName(binder)
                        val ta = TMeta(a)
                        val tb = TMeta(b)
                        val marker = ctx.mark()
                        var ctx2 = ctx.append(CTMeta(a), CTMeta(b), CVar(binder, ta))
                        ctx2 = check(ctx2, exp.body, tb)

                        val ty = ctx2.apply(TArrow(ta, tb))
                        exp.withType(generalizeFrom(ctx2, marker, ty)) to ctx
                    }
                }
            }
            is Expr.App -> {
                val (left, ctx2) = infer(ctx, exp.fn)
                val (right, ctx3) = inferapp(ctx2, ctx2.apply(left), exp.arg)
                exp.withType(right) to ctx3
            }
            is Expr.Ann -> {
                val ty = exp.annType
                wfType(ctx, ty, exp.span)
                val ctx2 = check(ctx, exp.exp, ty)
                // set not only the type of the annotation but the type of the inner expression
                exp.exp.withType(ty)
                exp.withType(ty) to ctx2
            }
            is Expr.If -> {
                check(ctx, exp.cond, tBoolean)
                val (tt, _) = infer(ctx, exp.thenCase)
                val (te, _) = infer(ctx, exp.elseCase)
                subsume(ctx, tt, te, exp.span)
                exp.withType(tt) to ctx
            }
            is Expr.Let -> {
                val ld = exp.letDef
                val binder = ld.binder.name
                checkShadow(ctx, binder, ld.binder.span)

                // infer or check the binding
                val ctx2 = if (ld.type != null) {
                    wfType(ctx, ld.type, ld.binder.span)
                    val ctx2 = check(ctx, ld.expr, ld.type)
                    ctx2.append(CVar(binder, ld.type))
                } else {
                    val (typ, ctx2) = infer(ctx, ld.expr)
                    ctx2.append(CVar(binder, typ))
                }

                // infer the body
                val (ty, _) = infer(ctx2, exp.body)
                exp.withType(ty) to ctx
            }
            is Expr.Do -> {
                var ty: Type? = null
                var ctx2 = ctx
                for (e in exp.exps) {
                    val res = infer(ctx, e)
                    ty = res.first
                    ctx2 = res.second
                }
                exp.withType(ty!!) to ctx2
            }
            else -> TODO()
        }
    }

    /**
     * The check mode of the type checker.
     * Checks expression [exp] has type [ty].
     */
    fun check(ctx: Context, exp: Expr, ty: Type): Context {
        return when {
            exp is Expr.IntE && ty == tByte && exp.v >= Byte.MIN_VALUE && exp.v <= Byte.MAX_VALUE -> {
                exp.withType(tByte)
                ctx
            }
            exp is Expr.IntE && ty == tShort && exp.v >= Short.MIN_VALUE && exp.v <= Short.MAX_VALUE -> {
                exp.withType(tShort)
                ctx
            }
            exp is Expr.IntE && ty == tInt -> {
                exp.withType(tInt)
                ctx
            }
            exp is Expr.LongE && ty == tLong -> {
                exp.withType(tLong)
                ctx
            }
            exp is Expr.FloatE && ty == tFloat -> {
                exp.withType(tFloat)
                ctx
            }
            exp is Expr.DoubleE && ty == tDouble -> {
                exp.withType(tDouble)
                ctx
            }
            exp is Expr.StringE && ty == tString -> {
                exp.withType(tString)
                ctx
            }
            exp is Expr.CharE && ty == tChar -> {
                exp.withType(tChar)
                ctx
            }
            exp is Expr.Bool && ty == tBoolean -> {
                exp.withType(tBoolean)
                ctx
            }
            exp is Expr.Unit -> {
                exp.withType(tUnit)
                ctx
            }
            ty is TForall -> {
                val x = freshName(ty.name)
                val ctx2 = ctx.append(CTVar(x))
                check(ctx2, exp, openTForall(ty, TVar(x)))
                ctx
            }
            ty is TArrow && exp is Expr.Lambda -> {
                when (exp.pattern) {
                    is FunparPattern.Ignored -> {
                        check(ctx, exp.body, ty.right)
                        exp.withType(ty)
                        ctx
                    }
                    is FunparPattern.Unit -> {
                        subsume(ctx, ty.left, tUnit, exp.span)
                        check(ctx, exp.body, ty.right)
                        exp.withType(ty)
                        ctx
                    }
                    is FunparPattern.Bind -> {
                        val x = exp.pattern.binder.name
                        val ctx2 = ctx.append(CVar(x, ty.left))
                        check(ctx2, exp.body, ty.right)
                        exp.withType(ty)
                        ctx
                    }
                }
            }
            exp is Expr.If -> {
                check(ctx, exp.cond, tBoolean)
                check(ctx, exp.thenCase, ty)
                check(ctx, exp.elseCase, ty)
                if (exp.thenCase.type != null) exp.withType(exp.thenCase.type!!)
                ctx
            }
            else -> {
                val (type, ctx2) = infer(ctx, exp)
                subsume(ctx2, ctx2.apply(type), ctx2.apply(type), exp.span)
            }
        }
    }

    private fun inferapp(ctx: Context, type: Type, expr: Expr): Pair<Type, Context> {
        return when (type) {
            is TForall -> {
                val x = freshName(type.name)
                val ctx2 = ctx.append(CTMeta(x))
                inferapp(ctx2, openTForall(type, TMeta(x)), expr)
            }
            is TMeta -> {
                val x = type.name
                val a = freshName(x)
                val b = freshName(x)
                val ta = TMeta(a)
                val tb = TMeta(b)
                var ctx2 = ctx.replace<CTMeta>(x, CTMeta(b), CTMeta(a), CTMeta(x, TArrow(ta, tb)))
                ctx2 = check(ctx2, expr, ta)
                tb to ctx2
            }
            is TArrow -> {
                val ctx2 = check(ctx, expr, type.left)
                type.right to ctx2
            }
            else -> inferError(E.NOT_A_FUNCTION, expr.span)
        }
    }

    private fun generalize(unsolved: List<String>, type: Type): Type {
        val ns = type.unsolvedInType(unsolved)
        if (ns.isEmpty()) return type

        val m = mutableMapOf<String, TVar>()
        for (x in ns) m[x] = TVar(freshName(x))

        var res = type.substMetas(m)
        for (i in ns.size - 1 downTo 0) {
            res = TForall(m[ns[i]]!!.name, res)
        }
        return res
    }

    private fun generalizeFrom(ctx: Context, index: Long, type: Type): Type {
        val (_, ty) = ctx.leaveWithUnsolved(index)
        return generalize(ty, type)
    }

    /**
     * Check if `name` is shadowing some variable
     * and throw an error if that's the case.
     */
    private fun checkShadow(ctx: Context, name: String, span: Span) {
        val shadow = ctx.lookupShadow<CVar>(name)
        if (shadow != null) inferError(E.shadowedVariable(name), span)
    }
}
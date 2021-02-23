package novah.frontend.typechecker

import novah.frontend.Span
import novah.frontend.error.ProblemContext
import novah.frontend.typechecker.Context.Companion.applyCtx
import novah.frontend.typechecker.Type.Companion.openTForall
import novah.frontend.typechecker.Typechecker.freshName
import novah.frontend.typechecker.WellFormed.wfType
import novah.frontend.error.Errors as E

object Subsumption {

    fun solve(ctx: Context, x: TMeta, type: Type, span: Span): Context {
        if (!type.isMono()) subsumeError(E.cannotSolveWithPoly(x.show(false), type.show(false)), span)
        val (ctx2, right) = ctx.split(CTMeta(x.name, null))
        wfType(ctx2, type, span)
        return ctx2.append(CTMeta(x.name, type)).concat(right)
    }

    fun instL(ctx: Context, x: TMeta, type: Type, span: Span): Context {
        return try {
            solve(ctx, x, type, span)
        } catch (_: InferenceError) {
            when (type) {
                is TMeta -> solve(ctx, type, x, span)
                is TForall -> {
                    val y = freshName(type.name)
                    val new = ctx.append(CTVar(y))
                    instL(new, x, openTForall(type, TVar(y)), span)
                    ctx
                }
                is TArrow -> {
                    val y = x.name
                    val a = freshName(y)
                    val b = freshName(y)
                    val ta = TMeta(a)
                    val tb = TMeta(b)
                    val ctx2 = ctx.replace(CTMeta(y), CTMeta(b), CTMeta(a), CTMeta(y, TArrow(ta, tb)))
                    val ctx3 = instR(ctx2, type.left, ta, span)
                    instL(ctx3, tb, applyCtx(ctx3, type.right), span)
                }
                else -> subsumeError(E.typeIsNotInstance(x.show(false), type.show(false)), span)
            }
        }
    }

    fun instR(ctx: Context, type: Type, x: TMeta, span: Span): Context {
        return try {
            solve(ctx, x, type, span)
        } catch (_: InferenceError) {
            when (type) {
                is TMeta -> solve(ctx, type, x, span)
                is TForall -> {
                    val y = freshName(type.name)
                    instR(ctx, openTForall(type, TMeta(y)), x, span)
                    ctx
                }
                is TArrow -> {
                    val y = x.name
                    val a = freshName(y)
                    val b = freshName(y)
                    val ta = TMeta(a)
                    val tb = TMeta(b)
                    val ctx2 = ctx.replace(CTMeta(y), CTMeta(b), CTMeta(a), CTMeta(y, TArrow(ta, tb)))
                    val ctx3 = instL(ctx2, ta, type.left, span)
                    instR(ctx3, applyCtx(ctx3, type.right), tb, span)
                }
                else -> subsumeError(E.typeIsNotInstance(x.show(false), type.show(false)), span)
            }
        }
    }

    fun subsume(ctx: Context, a: Type, b: Type, span: Span): Context {
        if (a == b) return ctx
        if (a is TConst && b is TConst && a.name == b.name) return ctx
        if (a is TVar && b is TVar && a.name == b.name) return ctx
        if (a is TMeta && b is TMeta && a.name == b.name) return ctx
        if (a is TArrow && b is TArrow) {
            val new = subsume(ctx, a.left, b.left, span)
            return subsume(new, applyCtx(new, a.right), applyCtx(new, b.right), span)
        }
        if (a is TApp && b is TApp) {
            TODO("Unification")
        }
        if (a is TRecord && b is TRecord) {
            TODO("Unification")
        }
        if (a is TForall) {
            val x = freshName(a.name)
            val new = ctx.append(CTVar(x))
            subsume(new, openTForall(a, TMeta(x)), b, span)
            return ctx
        }
        if (b is TForall) {
            val x = freshName(b.name)
            val new = ctx.append(CTVar(x))
            subsume(new, a, openTForall(b, TMeta(x)), span)
            return ctx
        }
        if (a is TMeta) {
            if (b.containsMeta(a.name)) inferError(E.infiniteType(a.show(false)), span)
            return instL(ctx, a, b, span)
        }
        if (b is TMeta) {
            if (a.containsMeta(b.name)) inferError(E.infiniteType(b.show(false)), span)
            return instR(ctx, a, b, span)
        }
        subsumeError(E.typeIsNotInstance(a.show(false), b.show(false)), span)
    }

    private fun subsumeError(msg: String, span: Span): Nothing = inferError(msg, span, ProblemContext.SUBSUMPTION)
}
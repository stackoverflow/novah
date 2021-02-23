package novah.frontend.typechecker

import novah.data.mapList
import novah.frontend.Span
import novah.frontend.error.Errors

object WellFormed {

    fun wfType(ctx: Context, t: Type, span: Span) {
        when (t) {
            is TConst -> {
                if (ctx.lookup<CTVar>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TVar -> {
                if (ctx.lookup<CTVar>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TMeta -> {
                if (ctx.lookup<CTMeta>(t.name) == null) inferError(Errors.undefinedVar(t.name), span)
            }
            is TArrow -> {
                wfType(ctx, t.left, span)
                wfType(ctx, t.right, span)
            }
            is TApp -> {
                wfType(ctx, t.left, span)
                wfType(ctx, t.right, span)
            }
            is TForall -> wfType(ctx, t.type, span)
            is TRecord -> wfType(ctx, t.row, span)
            is TRowEmpty -> {}
            is TRowExtend -> {
                wfType(ctx, t.row, span)
                t.labels.mapList { wfType(ctx, it, span) }
            }
        }
    }

    fun wfElem(ctx: Context, elem: Elem, span: Span) {
        when (elem) {
            is CTVar -> {
                if (ctx.lookup<CTVar>(elem.name) != null) inferError(Errors.duplicatedType(elem.name), span)
            }
            is CVar -> {
                if (ctx.lookup<CVar>(elem.name) != null) inferError(Errors.duplicatedType(elem.name), span)
            }
            is CTMeta -> {
                if (ctx.lookup<CTMeta>(elem.name) != null) inferError(Errors.duplicatedType(elem.name), span)
            }
        }
    }

    fun wfContext(ctx: Context, span: Span) {
        var c = ctx
        while (c.size() > 0) {
            val (elem, new) = c.removeLast()
            wfElem(new, elem, span)
            c = new
        }
    }
}
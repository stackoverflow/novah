package novah.frontend.typechecker

import io.lacuna.bifurcan.IList
import io.lacuna.bifurcan.List
import novah.data.mapList

sealed class Elem(open val name: String)

data class CVar(override val name: String, val type: Type) : Elem(name)
data class CTVar(override val name: String) : Elem(name)
data class CTMeta(override val name: String, val type: Type? = null) : Elem(name)
//data class CMarker(override val name: String) : Elem(name)

// TODO: optimize with reverse indexes
class Context private constructor(private val ctx: IList<Elem>) {

    fun size() = ctx.size()

    fun lookupInner(clazz: Class<*>, name: String): Elem? {
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified T> lookup(name: String): T? {
        return lookupInner(T::class.java, name) as? T
    }

    fun split(elem: Elem): Pair<Context, IList<Elem>> {
        val (ct, after) = innerSplit(elem)
        return Context(ct) to after
    }

    fun innerSplit(elem: Elem): Pair<IList<Elem>, IList<Elem>> {
        val index = ctx.indexOf(elem).toLong()
        if (index <= 0) return ctx to List()
        val before = ctx.slice(0, index)
        val after = ctx.slice(index + 1, ctx.size())
        return before to after
    }

    fun append(elem: Elem): Context = Context(ctx.addLast(elem))

    fun concat(elems: IList<Elem>): Context = Context(ctx.concat(elems))

    fun removeLast(): Pair<Elem, Context> {
        val last = ctx.last()
        return last to Context(ctx.removeLast())
    }

    fun replace(elem: Elem, vararg els: Elem): Context {
        val (new, right) = innerSplit(elem)
        return Context(els.fold(new) { acc, el -> acc.addLast(el) }.concat(right))
    }

    companion object {
        fun new(): Context {
            return Context(List())
        }

        // context application

        fun applyCtx(ctx: Context, ty: Type): Type = when (ty) {
            is TConst, is TRowEmpty, is TVar -> ty
            is TMeta -> {
                val solved = ctx.lookup<CTMeta>(ty.name)
                if (solved?.type != null) applyCtx(ctx, solved.type) else ty
            }
            is TArrow -> {
                val l = applyCtx(ctx, ty.left)
                val r = applyCtx(ctx, ty.right)
                if (l == ty.left && r == ty.right) ty else ty.copy(left = l, right = r)
            }
            is TApp -> {
                val l = applyCtx(ctx, ty.left)
                val r = applyCtx(ctx, ty.right)
                if (l == ty.left && r == ty.right) ty else ty.copy(left = l, right = r)
            }
            is TForall -> {
                val b = applyCtx(ctx, ty.type)
                if (b == ty.type) ty else ty.copy(type = b)
            }
            is TRecord -> {
                val r = applyCtx(ctx, ty.row)
                if (r == ty.row) ty else ty.copy(row = r)
            }
            is TRowExtend -> {
                val r = applyCtx(ctx, ty.row)
                val ls = ty.labels.mapList { applyCtx(ctx, it) }
                if (r == ty.row && ty.labels == ls) ty else ty.copy(labels = ls, row = r)
            }
        }
    }
}
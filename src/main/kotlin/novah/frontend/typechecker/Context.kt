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
class Context private constructor(val ctx: IList<Elem>) {

    fun size() = ctx.size()
    fun mark() = ctx.size()

    fun lookupInner(clazz: Class<out Elem>, name: String): Elem? {
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified T : Elem> lookup(name: String): T? {
        return lookupInner(T::class.java, name) as? T
    }

    fun lookupShadowInner(clazz: Class<out Elem>, name: String): Elem? {
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified R : Elem> lookupShadow(name: String): R? {
        return lookupShadowInner(R::class.java, name) as R?
    }

    inline fun <reified R : Elem> split(name: String): Pair<Context, IList<Elem>> {
        val (ct, after) = innerSplit(R::class.java, name)
        return ct to after
    }

    fun innerSplit(clazz: Class<out Elem>, name: String): Pair<Context, IList<Elem>> {
        var i = 0L
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) break
            i++
        }
        if (i <= 0) return this to List()
        val before = ctx.slice(0, i)
        val after = ctx.slice(i + 1, ctx.size())
        return Context(before) to after
    }

    fun append(elem: Elem): Context = Context(ctx.addLast(elem))

    fun append(vararg es: Elem): Context {
        return Context(es.fold(ctx) { acc, el -> acc.addLast(el) })
    }

    fun concat(elems: IList<Elem>): Context = Context(ctx.concat(elems))

    fun removeLast(): Pair<Elem, Context> {
        val last = ctx.last()
        return last to Context(ctx.removeLast())
    }

    fun replaceInner(clazz: Class<out Elem>, name: String, vararg els: Elem): Context {
        val (new, right) = innerSplit(clazz, name)
        return Context(els.fold(new.ctx) { acc, el -> acc.addLast(el) }.concat(right))
    }

    inline fun <reified R : Elem> replace(name: String, vararg els: Elem): Context {
        return replaceInner(R::class.java, name, *els)
    }

    // instead of using a marker we use a index for optimization purposes
    fun leaveWithUnsolved(index: Long): Pair<Context, kotlin.collections.List<String>> {
        if (index >= ctx.size()) return this to emptyList()
        val ns = mutableListOf<String>()
        for (i in index until ctx.size()) {
            val n = ctx.nth(i)
            if (n is CTMeta && n.type == null) ns += n.name
        }
        val c = ctx.slice(0, index)
        return Context(c) to ns
    }

    /**
     * Context application
     */
    fun apply(ty: Type): Type = ty.everywhere { t ->
        if (t is TMeta) {
            val solved = lookup<CTMeta>(t.name)
            if (solved?.type != null) apply(solved.type) else t
        } else t
    }

    companion object {
        fun new(): Context = Context(List())
        fun new(ctx: IList<Elem>) = Context(ctx)
    }
}
package novah.frontend.typechecker

/**
 * The different types of elements the [Context] can have.
 */
sealed class Elem {
    // Types in the context
    data class CTVar(val name: Name, val kind: Int = 0) : Elem() {
        override fun toString(): String = name.toString()
    }
    data class CTMeta(val name: Name, val type: Type? = null) : Elem() {
        override fun toString(): String = "?$name${if (type != null) " = $type" else ""}"
    }
    // Variables in the context (with type)
    data class CVar(val name: Name, val type: Type) : Elem() {
        override fun toString(): String = "$name : $type"
    }
    data class CMarker(val name: Name) : Elem() {
        override fun toString(): String = "|$name"
    }

    fun name() = when (this) {
        is CTVar -> name
        is CTMeta -> name
        is CVar -> name
        is CMarker -> name
    }
}

/**
 * The ordered context/environment where typed variables live.
 */
class Context {

    internal var ctx = mutableListOf<Elem>()

    override fun toString(): String {
        return ctx.joinToString(", ", transform = Elem::toString)
    }

    fun clone(): Context {
        val newCtx = mutableListOf<Elem>()
        newCtx.addAll(ctx)
        return newContext(newCtx)
    }

    fun addAll(es: List<Elem>): Context = apply { ctx.addAll(es) }

    fun add(e: Elem): Context = apply { ctx.add(e) }

    fun innerContains(clazz: Class<out Elem>, name: Name): Boolean {
        for (e in ctx) {
            if (e.name() == name && clazz.isInstance(e)) return true
        }
        return false
    }

    inline fun <reified R : Elem> contains(name: Name): Boolean {
        return innerContains(R::class.java, name)
    }

    fun innerLookup(clazz: Class<out Elem>, name: Name): Elem? {
        for (e in ctx) {
            if (e.name() == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified R : Elem> lookup(name: Name): R? {
        return innerLookup(R::class.java, name) as R?
    }

    fun innerLookupShadow(clazz: Class<out Elem>, name: Name): Elem? {
        for (e in ctx) {
            if (e.name().rawName() == name.rawName() && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified R : Elem> lookupShadow(name: Name): R? {
        return innerLookupShadow(R::class.java, name) as R?
    }

    fun pop(): Elem? = ctx.removeLastOrNull()

    fun _split(clazz: Class<out Elem>, name: Name): List<Elem> {
        val before = mutableListOf<Elem>()
        val after = mutableListOf<Elem>()
        var found = false

        for (e in ctx) {
            if (found) {
                after.add(e)
            } else if (e.name() == name && clazz.isInstance(e)) {
                found = true
            } else {
                before.add(e)
            }
        }
        ctx = before
        return after
    }

    inline fun <reified R : Elem> split(name: Name): List<Elem> {
        return _split(R::class.java, name)
    }

    fun _replace(clazz: Class<out Elem>, name: Name, es: List<Elem>): Context = apply {
        val right = _split(clazz, name)
        addAll(es)
        addAll(right)
    }

    inline fun <reified R : Elem> replace(name: Name, es: List<Elem>): Context {
        return _replace(R::class.java, name, es)
    }

    fun isComplete(): Boolean {
        for (e in ctx) {
            if (e is Elem.CTMeta && e.type == null) return false
        }
        return true
    }

    fun enter(m: Name, vararg es: Elem) {
        ctx.add(Elem.CMarker(m))
        ctx.addAll(es)
    }

    fun leave(m: Name) {
        split<Elem.CMarker>(m)
    }

    fun leaveWithUnsolved(m: Name): List<Name> {
        val res = split<Elem.CMarker>(m)
        val ns = mutableListOf<Name>()
        for (e in res) {
            if (e is Elem.CTMeta && e.type == null) ns.add(e.name)
        }
        return ns
    }

    fun reset() = ctx.clear()

    fun forEachMeta(f: (Elem.CTMeta) -> Unit) {
        for (e in ctx) {
            if (e is Elem.CTMeta) f(e)
        }
    }

    fun replaceCVar(name: Name, new: Elem.CVar) {
        var index = -1
        for (i in ctx.indices) {
            val e = ctx[i]
            if (e is Elem.CVar && e.name == name) {
                index = i
                break
            }
        }
        if (index >= 0) ctx[index] = new
    }

    companion object {
        internal fun newContext(elems: MutableList<Elem>): Context {
            val ctx = Context()
            ctx.ctx = elems
            return ctx
        }
    }
}
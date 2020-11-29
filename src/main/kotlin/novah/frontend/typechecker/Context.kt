package novah.frontend.typechecker

sealed class Elem(val _name: String) {
    data class CTVar(val name: String) : Elem(name) {
        override fun toString(): String = name
    }
    data class CTMeta(val name: String, val type: Type? = null) : Elem(name) {
        override fun toString(): String = "?$name${if (type != null) " = $type" else ""}"
    }
    data class CVar(val name: String, val type: Type) : Elem(name) {
        override fun toString(): String = "$name : $type"
    }
    data class CMarker(val name: String) : Elem(name) {
        override fun toString(): String = "|$name"
    }
}

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

    fun append(e: Elem): Context = apply { ctx.add(e) }

    fun _contains(clazz: Class<out Elem>, name: String): Boolean {
        for (e in ctx) {
            if (e._name == name && clazz.isInstance(e)) return true
        }
        return false
    }

    inline fun <reified R : Elem> contains(name: String): Boolean {
        return _contains(R::class.java, name)
    }

    fun _lookup(clazz: Class<out Elem>, name: String): Elem? {
        for (e in ctx) {
            if (e._name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified R : Elem> lookup(name: String): R? {
        return _lookup(R::class.java, name) as R?
    }

    fun pop(): Elem? = ctx.removeLastOrNull()

    fun _split(clazz: Class<out Elem>, name: String): List<Elem> {
        val before = mutableListOf<Elem>()
        val after = mutableListOf<Elem>()
        var found = false

        for (e in ctx) {
            if (found) {
                after.add(e)
            } else if (e._name == name && clazz.isInstance(e)) {
                found = true
            } else {
                before.add(e)
            }
        }
        ctx = before
        return after
    }

    inline fun <reified R : Elem> split(name: String): List<Elem> {
        return _split(R::class.java, name)
    }

    fun _replace(clazz: Class<out Elem>, name: String, es: List<Elem>): Context = apply {
        val right = _split(clazz, name)
        addAll(es)
        addAll(right)
    }

    inline fun <reified R : Elem> replace(name: String, es: List<Elem>): Context {
        return _replace(R::class.java, name, es)
    }

    fun isComplete(): Boolean {
        for (e in ctx) {
            if (e is Elem.CTMeta && e.type == null) return false
        }
        return true
    }

    fun enter(m: String, vararg es: Elem) {
        ctx.add(Elem.CMarker(m))
        ctx.addAll(es)
    }

    fun leave(m: String) {
        split<Elem.CMarker>(m)
    }

    fun leaveWithUnsolved(m: String): List<String> {
        val res = split<Elem.CMarker>(m)
        val ns = mutableListOf<String>()
        for (e in res) {
            if (e is Elem.CTMeta && e.type == null) ns.add(e.name)
        }
        return ns
    }

    companion object {
        internal fun newContext(elems: MutableList<Elem>): Context {
            val ctx = Context()
            ctx.ctx = elems
            return ctx
        }
    }
}
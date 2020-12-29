package novah.frontend.typechecker

/**
 * The Type of Types
 */
sealed class Type {
    data class TVar(val name: Name) : Type() {
        override fun toString(): String = "$name"
    }

    data class TMeta(val name: Name) : Type() {
        override fun toString(): String = "?$name"
    }

    data class TFun(val arg: Type, val ret: Type) : Type() {
        override fun toString(): String = "($arg -> $ret)"
    }

    data class TForall(val name: Name, val type: Type) : Type() {
        override fun toString(): String = "forall $name. $type"
    }

    data class TConstructor(val name: Name, val types: List<Type> = listOf()) : Type() {
        override fun toString(): String = if (types.isEmpty()) {
            "$name"
        } else {
            "$name " + types.joinToString(" ")
        }
    }

    /*********************************
     * Helper functions
     *********************************/

    fun containsTMeta(m: Name): Boolean = when (this) {
        is TVar -> false
        is TMeta -> name == m
        is TFun -> arg.containsTMeta(m) || ret.containsTMeta(m)
        is TForall -> type.containsTMeta(m)
        is TConstructor -> types.any { it.containsTMeta(m) }
    }

    fun isMono(): Boolean = when (this) {
        is TVar, is TMeta -> true
        is TFun -> arg.isMono() && ret.isMono()
        is TForall -> false
        is TConstructor -> types.all(Type::isMono)
    }

    fun substTVar(x: Name, s: Type): Type = when (this) {
        is TVar -> if (name == x) s else this
        is TMeta -> this
        is TFun -> {
            val left = arg.substTVar(x, s)
            val right = ret.substTVar(x, s)
            if (arg == left && ret == right) this else TFun(left, right)
        }
        is TForall -> {
            if (name == x) this
            else {
                val body = type.substTVar(x, s)
                if (type == body) this else TForall(name, body)
            }
        }
        is TConstructor -> {
            if (name == x) this
            else {
                val body = types.map { it.substTVar(x, s) }
                if (types == body) this else TConstructor(name, body)
            }
        }
    }

    fun substTMetas(m: Map<Name, Type>): Type = when (this) {
        is TVar -> this
        is TMeta -> m.getOrDefault(name, this)
        is TFun -> {
            val left = arg.substTMetas(m)
            val right = ret.substTMetas(m)
            if (arg == left && ret == right) this else TFun(left, right)
        }
        is TForall -> {
            val body = type.substTMetas(m)
            if (type == body) this else TForall(name, body)
        }
        is TConstructor -> {
            val body = types.map { it.substTMetas(m) }
            if (types == body) this else TConstructor(name, body)
        }
    }

    fun unsolvedInType(unsolved: List<Name>, ns: MutableList<Name> = mutableListOf()): List<Name> = when (this) {
        is TVar -> ns
        is TMeta -> {
            if (unsolved.contains(name) && !ns.contains(name)) {
                ns.add(name)
            }
            ns
        }
        is TFun -> {
            arg.unsolvedInType(unsolved, ns)
            ret.unsolvedInType(unsolved, ns)
        }
        is TForall -> type.unsolvedInType(unsolved, ns)
        is TConstructor -> {
            types.forEach { it.unsolvedInType(unsolved, ns) }
            ns
        }
    }

    fun substFreeVar(replace: String): Type {
        return if (this is TForall) {
            TForall(replace.raw(), type.substTVar(name, TVar(replace.raw())))
        } else this
    }

    fun findTMeta(): List<TMeta> = when (this) {
        is TVar -> emptyList()
        is TMeta -> listOf(this)
        is TFun -> arg.findTMeta() + ret.findTMeta()
        is TForall -> type.findTMeta()
        is TConstructor -> types.flatMap { it.findTMeta() }
    }

    /**
     * Non qualified version of [toString]
     */
    fun simpleName(): String = when (this) {
        is TVar -> name.rawName().split('.').last()
        is TConstructor -> {
            val sname = "$name".split('.').last()
            if (types.isEmpty()) sname else sname + " " + types.joinToString(" ") { it.simpleName() }
        }
        is TFun -> "(${arg.simpleName()} -> ${ret.simpleName()})"
        is TForall -> "forall ${name.rawName()}. ${type.simpleName()}"
        else -> toString()
    }

    companion object {

        fun openTForall(forall: TForall, s: Type): Type =
            forall.type.substTVar(forall.name, s)
    }
}
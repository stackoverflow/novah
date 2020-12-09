package novah.frontend.typechecker

/**
 * The Type of Types
 */
sealed class Type {
    data class TVar(val name: String) : Type() {
        override fun toString(): String = name
    }

    data class TMeta(val name: String) : Type() {
        override fun toString(): String = "?$name"
    }

    data class TFun(val arg: Type, val ret: Type) : Type() {
        override fun toString(): String = "($arg -> $ret)"
    }

    data class TForall(val name: String, val type: Type) : Type() {
        override fun toString(): String = "forall $name. $type"
    }

    data class TConstructor(val name: String, val types: List<Type> = listOf()) : Type() {
        override fun toString(): String = if (types.isEmpty()) {
            name
        } else {
            "$name " + types.joinToString(" ")
        }
    }

    /*********************************
     * Helper functions
     *********************************/

    fun containsTMeta(m: String): Boolean = when (this) {
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

    fun substTVar(x: String, s: Type): Type = when (this) {
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

    fun substTMetas(m: Map<String, Type>): Type = when (this) {
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

    fun unsolvedInType(unsolved: List<String>, ns: MutableList<String> = mutableListOf()): List<String> = when (this) {
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

    /**
     * Find any unbound variables in this type
     */
    fun findFreeVars(bound: List<String>): List<String> = when (this) {
        is TVar -> if (name[0].isLowerCase() && name !in bound) listOf(name) else listOf()
        is TFun -> arg.findFreeVars(bound) + ret.findFreeVars(bound)
        is TForall -> type.findFreeVars(bound + name)
        is TConstructor -> types.flatMap { it.findFreeVars(bound) }
        is TMeta -> listOf()
    }

    fun substFreeVar(replace: String): Type {
        return if (this is TForall) {
            TForall(replace, type.substTVar(name, TVar(replace)))
        } else this
    }

    companion object {

        fun openTForall(forall: TForall, s: Type): Type =
            forall.type.substTVar(forall.name, s)
    }
}
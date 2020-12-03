package novah.frontend.typechecker

sealed class Type {
    // primitives
    object TInt : Type() {
        override fun toString(): String = "Int"
    }
    object TFloat : Type() {
        override fun toString(): String = "Float"
    }
    object TString : Type() {
        override fun toString(): String = "String"
    }
    object TChar : Type() {
        override fun toString(): String = "Char"
    }
    object TBoolean : Type() {
        override fun toString(): String = "Boolean"
    }

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

    /*********************************
     * Helper functions
     *********************************/

    fun containsTMeta(m: String): Boolean =
        when (this) {
            is TInt -> false
            is TFloat -> false
            is TString -> false
            is TChar -> false
            is TBoolean -> false
            is TVar -> false
            is TMeta -> name == m
            is TFun -> arg.containsTMeta(m) || ret.containsTMeta(m)
            is TForall -> type.containsTMeta(m)
        }

    fun isMono(): Boolean =
        when (this) {
            is TInt -> true
            is TFloat -> true
            is TString -> true
            is TChar -> true
            is TBoolean -> true
            is TVar -> true
            is TMeta -> true
            is TFun -> arg.isMono() && ret.isMono()
            is TForall -> false
        }

    fun substTVar(x: String, s: Type): Type =
        when (this) {
            is TInt -> this
            is TFloat -> this
            is TString -> this
            is TChar -> this
            is TBoolean -> this
            is TVar -> if (name == x) s else this
            is TMeta -> this
            is TFun -> {
                val left = arg.substTVar(x, s)
                val right = ret.substTVar(x, s)
                if (arg == left && ret == right) this else TFun(left, right)
            }
            is TForall -> {
                if (name == x) {
                    this
                } else {
                    val body = type.substTVar(x, s)
                    if (type == body) this else TForall(name, body)
                }
            }
        }

    fun substTMetas(m: Map<String, Type>): Type =
        when (this) {
            is TInt -> this
            is TFloat -> this
            is TString -> this
            is TChar -> this
            is TBoolean -> this
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
        }

    fun unsolvedInType(unsolved: List<String>, ns: MutableList<String> = mutableListOf()): List<String> =
        when (this) {
            is TInt -> ns
            is TFloat -> ns
            is TString -> ns
            is TChar -> ns
            is TBoolean -> ns
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
        }

    companion object {

        fun openTForall(forall: TForall, s: Type): Type =
            forall.type.substTVar(forall.name, s)
    }
}
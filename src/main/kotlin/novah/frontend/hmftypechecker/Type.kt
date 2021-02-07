package novah.frontend.hmftypechecker

import novah.Util.joinToStr
import novah.frontend.Span

typealias Id = Int
typealias Level = Int

typealias Name = String

sealed class Kind(open val arity: Int) {
    object Star : Kind(0) {
        override fun toString(): String = "Type"
    }
    data class Constructor(override val arity: Int) : Kind(arity) {
        override fun toString(): String = kindArityToString(arity)
    }
}

fun kindArityToString(arity: Int): String = (0..arity).joinToString(" -> ") { "Type" }

sealed class TypeVar {
    data class Unbound(val id: Id, val level: Level) : TypeVar()
    data class Link(val type: Type) : TypeVar()
    data class Generic(val id: Id) : TypeVar()
    data class Bound(val id: Id) : TypeVar()
}

sealed class Type {
    data class TConst(val name: Name, val kind: Kind = Kind.Star) : Type()
    data class TApp(val type: Type, val types: List<Type>) : Type()
    data class TArrow(val args: List<Type>, val ret: Type) : Type()
    data class TForall(val ids: List<Id>, val type: Type) : Type()
    class TVar(var tvar: TypeVar) : Type() {
        override fun equals(other: Any?): Boolean {
            if (other == null || other !is TVar) return false
            return tvar == other.tvar
        }

        override fun hashCode(): Int {
            return tvar.hashCode() * 31
        }

        override fun toString(): String = "TVar($tvar)"
    }

    var span: Span? = null
    fun span(s: Span?): Type = apply { span = s }

    fun unlink(): Type = when {
        this is TVar && tvar is TypeVar.Link -> {
            val ty = (tvar as TypeVar.Link).type.unlink()
            tvar = TypeVar.Link(ty)
            ty
        }
        else -> this
    }

    fun isMono(): Boolean = when (this) {
        is TForall -> false
        is TConst -> true
        is TVar -> {
            if (tvar is TypeVar.Link) (tvar as TypeVar.Link).type.isMono()
            else true
        }
        is TApp -> type.isMono() && types.all(Type::isMono)
        is TArrow -> args.all(Type::isMono) && ret.isMono()
    }

    fun substConst(map: Map<String, Type>): Type = when (this) {
        is TConst -> {
            val ty = map[name]
            ty ?: this
        }
        is TApp -> copy(type.substConst(map), types.map { it.substConst(map) })
        is TArrow -> copy(args.map { it.substConst(map) }, ret.substConst(map))
        is TForall -> copy(ids, type.substConst(map))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> TVar(TypeVar.Link(tv.type.substConst(map))).span(span)
                else -> this
            }
        }
    }

    /**
     * Recursively walks this type top->down
     */
    fun everywhere(f: (Type) -> Unit) {
        when (this) {
            is TConst -> f(this)
            is TApp -> {
                f(this)
                f(type)
                types.forEach(f)
            }
            is TArrow -> {
                f(this)
                args.forEach(f)
                f(ret)
            }
            is TForall -> {
                f(this)
                f(type)
            }
            is TVar -> {
                f(this)
                if (tvar is TypeVar.Link) f((tvar as TypeVar.Link).type)
            }
        }
    }
    
    fun kind(): Kind = when (this) {
        is TConst -> kind
        is TForall -> type.kind()
        is TArrow -> Kind.Constructor(1)
        is TApp -> type.kind()
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.kind()
                else -> Kind.Star
            }
        }
    }

    /**
     * Pretty print version of [toString]
     */
    fun show(qualified: Boolean): String {
        fun go(t: Type, nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is TConst -> if (qualified) t.name else t.name.split('.').last()
            is TApp -> {
                val sname = go(t.type, nested)
                val str = if (t.types.isEmpty()) sname
                else sname + " " + t.types.joinToString(" ") { go(it, true) }
                if (nested) "($str)" else str
            }
            is TArrow -> {
                val args = if (t.args.size == 1) {
                    go(t.args[0], false)
                } else {
                    t.args.joinToString(" ", prefix = "(", postfix = ")") { go(it, false) }
                }
                if (nested) "($args -> ${go(t.ret, false)})"
                else "$args -> ${go(t.ret, nested)}"
            }
            is TForall -> {
                val str = "forall ${t.ids.joinToStr(" ") { "t$it" }}. ${go(t.type, false)}"
                if (nested || !topLevel) "($str)" else str
            }
            is TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> go(tv.type, nested)
                    is TypeVar.Unbound -> "t${tv.id}"
                    is TypeVar.Generic -> "t${tv.id}"
                    is TypeVar.Bound -> "t${tv.id}"
                }
            }
        }
        return go(this, false, topLevel = true)
    }
}
package novah.frontend.hmftypechecker

import novah.Util.joinToStr

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
        is TApp -> TApp(type.substConst(map), types.map { it.substConst(map) })
        is TArrow -> TArrow(args.map { it.substConst(map) }, ret.substConst(map))
        is TForall -> TForall(ids, type.substConst(map))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> TVar(TypeVar.Link(tv.type.substConst(map)))
                else -> this
            }
        }
    }

    fun everywhere(f: (Type) -> Unit): Unit = when (this) {
        is TConst -> f(this)
        is TApp -> {
            f(type)
            types.forEach(f)
        }
        is TArrow -> {
            args.forEach(f)
            f(ret)
        }
        is TForall -> f(type)
        is TVar -> {
            if (tvar is TypeVar.Link) f((tvar as TypeVar.Link).type)
            else f(this)
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
    fun show(qualified: Boolean, nested: Boolean = false): String = when (this) {
        is TConst -> if (qualified) name else name.split('.').last()
        is TApp -> {
            val sname = type.show(qualified, nested)
            if (types.isEmpty()) sname else sname + " " + types.joinToString(" ") { it.show(qualified, true) }
        }
        is TArrow -> {
            val args = if (args.size == 1) {
                args[0].show(qualified, !nested)
            } else args.joinToString(" ", prefix = "(", postfix = ")")
            
            if (nested) "($args -> ${ret.show(qualified)})"
            else
                "$args -> ${ret.show(qualified, nested)}"
        }
        is TForall -> {
            val str = "forall ${ids.joinToStr(" ") { "t$it" }}. ${type.show(qualified)}"
            if (nested) "($str)" else str
        }
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.show(qualified, nested)
                is TypeVar.Unbound -> "t${tv.id}"
                is TypeVar.Generic -> "t${tv.id}"
                is TypeVar.Bound -> "t${tv.id}"
            }
        }
    }
}
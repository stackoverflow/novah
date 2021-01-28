package novah.frontend.hmftypechecker

import novah.Util.joinToStr

typealias Id = Int
typealias Level = Int

typealias Name = String

sealed class TypeVar {
    data class Unbound(val id: Id, val level: Level) : TypeVar()
    data class Link(val type: Type) : TypeVar()
    data class Generic(val id: Id) : TypeVar()
    data class Bound(val id: Id) : TypeVar()
    
    fun show(): String = when (this) {
        is Unbound -> "?t$id"
        is Link -> type.show()
        is Generic -> "t$id"
        is Bound -> "t$id"
    }
}

sealed class Type {
    data class TConst(val name: Name) : Type()
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
    
    fun show(): String = when (this) {
        is TConst -> name
        is TApp -> type.show() + types.joinToStr(" ", prefix = " ") { it.show() }
        is TArrow -> {
            if (args.size == 1) args[0].show() + " -> " + ret.show()
            else args.joinToStr(prefix = "(", postfix = ")") + " -> " + ret.show()
        }
        is TForall -> "forall " + ids.joinToStr(" ") { "t$it" } + ". ${type.show()}"
        is TVar -> tvar.show()
    }
}
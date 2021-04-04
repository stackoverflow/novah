package novah.frontend.typechecker

import novah.data.*
import novah.frontend.Span

typealias Id = Int
typealias Level = Int

typealias Name = String

sealed class Kind(open val arity: Int) {
    object Star : Kind(0) {
        override fun toString(): String = "Type"
    }

    data class Constructor(override val arity: Int) : Kind(arity) {
        override fun toString(): String = (0..arity).joinToString(" -> ") { "Type" }
    }
}

sealed class TypeVar {
    data class Unbound(val id: Id, val level: Level) : TypeVar()
    data class Link(val type: Type) : TypeVar()
    data class Generic(val id: Id) : TypeVar()
}

typealias Row = Type

data class TConst(val name: Name, val kind: Kind = Kind.Star) : Type()
data class TApp(val type: Type, val types: List<Type>) : Type()
data class TArrow(val args: List<Type>, val ret: Type) : Type()
data class TImplicit(val type: Type) : Type()
data class TRecord(val row: Row) : Type()
data class TRowExtend(val labels: LabelMap<Type>, val row: Row) : Type()
class TRowEmpty : Type() {
    override fun equals(other: Any?): Boolean = other != null && other is TRowEmpty
    override fun hashCode(): Int = 31
    override fun toString(): String = "TRowEmpty"
}

class TVar(var tvar: TypeVar) : Type() {
    override fun hashCode(): Int = tvar.hashCode() * 31
    override fun toString(): String = "TVar($tvar)"
    override fun equals(other: Any?): Boolean {
        if (other == null || other !is TVar) return false
        return tvar == other.tvar
    }
}

sealed class Type {

    var span: Span? = null
    fun span(s: Span?): Type = apply { span = s }

    fun realType(): Type = when {
        this is TVar && tvar is TypeVar.Link -> (tvar as TypeVar.Link).type.realType()
        else -> this
    }

    /**
     * Recursively walks this type up->bottom
     */
    fun everywhereUnit(f: (Type) -> Unit) {
        fun go(t: Type) {
            when (t) {
                is TConst -> f(t)
                is TApp -> {
                    f(t)
                    go(t.type)
                    t.types.forEach(::go)
                }
                is TArrow -> {
                    f(t)
                    t.args.forEach(::go)
                    go(t.ret)
                }
                is TVar -> {
                    f(t)
                    if (t.tvar is TypeVar.Link) go((t.tvar as TypeVar.Link).type)
                }
                is TRowEmpty -> f(t)
                is TRecord -> {
                    f(t)
                    go(t.row)
                }
                is TRowExtend -> {
                    f(t)
                    go(t.row)
                    t.labels.forEachList(::go)
                }
                is TImplicit -> {
                    f(t)
                    go(t.type)
                }
            }
        }
        go(this)
    }

    fun kind(): Kind = when (this) {
        is TConst -> kind
        is TArrow -> Kind.Constructor(1)
        is TApp -> type.kind()
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.kind()
                else -> Kind.Star
            }
        }
        // this is not right, but I'll postpone adding real row kinds for now
        is TRowEmpty -> Kind.Star
        is TRecord -> row.kind()
        is TRowExtend -> row.kind()
        is TImplicit -> type.kind()
    }

    fun substConst(map: Map<String, Type>): Type = when (this) {
        is TConst -> map[name] ?: this
        is TApp -> copy(type.substConst(map), types.map { it.substConst(map) })
        is TArrow -> copy(args.map { it.substConst(map) }, ret.substConst(map))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> TVar(
                    TypeVar.Link(
                        tv.type.substConst(map)
                    )
                ).span(span)
                else -> this
            }
        }
        is TRowEmpty -> this
        is TRecord -> copy(row.substConst(map))
        is TRowExtend -> copy(row = row.substConst(map), labels = labels.mapList { it.substConst(map) })
        is TImplicit -> copy(type.substConst(map))
    }

    /**
     * Collects all the rows from this type.
     * Does nothing if called on a non-row type.
     */
    fun collectRows(): Pair<LabelMap<Type>, Type> = when (this) {
        is TRowEmpty -> LabelMap<Type>() to this
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> tv.type.collectRows()
                else -> LabelMap<Type>() to this
            }
        }
        is TRowExtend -> {
            val (restLabels, restTy) = row.collectRows()
            if (restLabels.isEmpty()) labels to restTy
            else labels.merge(restLabels) to restTy
        }
        else -> LabelMap<Type>() to this
    }

    /**
     * Returns the name of this type if it's a const
     * or empty string.
     */
    fun typeNameOrEmpty(): String = when (this) {
        is TConst -> name
        is TImplicit -> type.typeNameOrEmpty()
        is TVar -> {
            if (tvar is TypeVar.Link) (tvar as TypeVar.Link).type.typeNameOrEmpty()
            else ""
        }
        else -> ""
    }

    /**
     * Pretty print version of [toString]
     */
    fun show(qualified: Boolean = true): String {
        fun showId(id: Id) = if (id >= 0) "t$id" else "u${-id}"

        fun go(t: Type, nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is TConst -> {
                val isCurrent = Typechecker.context?.mod?.name?.let { t.name.startsWith(it) } ?: false
                val shouldQualify = !t.name.startsWith("prim.") && !isCurrent
                if (qualified && shouldQualify) t.name else t.name.split('.').last()
            }
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
            is TVar -> {
                when (val tv = t.tvar) {
                    is TypeVar.Link -> go(tv.type, nested, topLevel)
                    is TypeVar.Unbound -> showId(tv.id)
                    is TypeVar.Generic -> showId(tv.id)
                }
            }
            is TRowEmpty -> "{}"
            is TRecord -> {
                when (val ty = t.row.realType()) {
                    is TRowEmpty -> "{}"
                    !is TRowExtend -> "{ | ${go(ty, topLevel = true)} }"
                    else -> {
                        val rows = go(ty, topLevel = true)
                        "{" + rows.substring(1, rows.lastIndex) + "}"
                    }
                }
            }
            is TRowExtend -> {
                val labels = t.labels.show { k, v -> "$k : ${go(v, topLevel = true)}" }
                val str = when (val ty = t.row.realType()) {
                    is TRowEmpty -> labels
                    !is TRowExtend -> "$labels | ${go(ty, topLevel = true)}"
                    else -> {
                        val rows = go(ty, topLevel = true)
                        "$labels, ${rows.substring(2, rows.lastIndex - 1)}"
                    }
                }
                "[ $str ]"
            }
            is TImplicit -> "{{ ${go(t.type)} }}"
        }
        return go(this, false, topLevel = true)
    }

    companion object {
        fun nestArrows(args: List<Type>, ret: Type): Type = when {
            args.isEmpty() -> ret
            else -> TArrow(listOf(args[0]), nestArrows(args.drop(1), ret))
        }
    }
}
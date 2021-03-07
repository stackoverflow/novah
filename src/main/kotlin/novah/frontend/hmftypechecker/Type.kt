/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend.hmftypechecker

import novah.Util.joinToStr
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
    data class Bound(val id: Id) : TypeVar()
}

typealias Row = Type

data class TConst(val name: Name, val kind: Kind = Kind.Star) : Type()
data class TApp(val type: Type, val types: List<Type>) : Type()
data class TArrow(val args: List<Type>, val ret: Type) : Type()
data class TForall(val ids: List<Id>, val type: Type) : Type()
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
        is TRowEmpty -> true
        is TVar -> {
            if (tvar is TypeVar.Link) (tvar as TypeVar.Link).type.isMono()
            else true
        }
        is TApp -> type.isMono() && types.all(Type::isMono)
        is TArrow -> args.all(Type::isMono) && ret.isMono()
        is TRecord -> row.isMono()
        is TRowExtend -> row.isMono() && labels.allList(Type::isMono)
        is TImplicit -> type.isMono()
    }

    fun isUnbound(): Boolean = this is TVar && tvar is TypeVar.Unbound

    fun isConst(name: Name): Boolean = this is TConst && this.name == name

    fun substConst(map: Map<String, Type>): Type = when (this) {
        is TConst -> map[name] ?: this
        is TApp -> copy(type.substConst(map), types.map { it.substConst(map) })
        is TArrow -> copy(args.map { it.substConst(map) }, ret.substConst(map))
        is TForall -> copy(ids, type.substConst(map))
        is TVar -> {
            when (val tv = tvar) {
                is TypeVar.Link -> TVar(TypeVar.Link(tv.type.substConst(map))).span(span)
                else -> this
            }
        }
        is TRowEmpty -> this
        is TRecord -> copy(row.substConst(map))
        is TRowExtend -> copy(row = row.substConst(map), labels = labels.mapList { it.substConst(map) })
        is TImplicit -> copy(type.substConst(map))
    }

    /**
     * Recursively walks this type up->bottom
     */
    fun everywhere(f: (Type) -> Unit) {
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
                is TForall -> {
                    f(t)
                    go(t.type)
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
        is TForall -> type.kind()
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

    /**
     * Pretty print version of [toString]
     */
    fun show(qualified: Boolean = true): String {
        fun go(t: Type, nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is TConst -> if (qualified && !t.name.startsWith("prim.")) t.name else t.name.split('.').last()
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
                    is TypeVar.Link -> go(tv.type, nested, topLevel)
                    is TypeVar.Unbound -> "t${tv.id}"
                    is TypeVar.Bound -> "t${tv.id}"
                    is TypeVar.Generic -> "t${tv.id}"
                }
            }
            is TRowEmpty -> "{}"
            is TRecord -> {
                when (val ty = t.row.unlink()) {
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
                val str = when (val ty = t.row.unlink()) {
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
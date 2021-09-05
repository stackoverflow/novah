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

    fun copyTvar(): TVar = when (val tv = tvar) {
        is TypeVar.Unbound -> TVar(tv.copy())
        is TypeVar.Link -> TVar(tv.copy(type = tv.type.clone()))
        is TypeVar.Generic -> TVar(tv.copy())
    }
}

sealed class Type {

    var span: Span? = null
    fun span(s: Span?): Type = apply { span = s }

    fun realType(): Type = when {
        this is TVar && tvar is TypeVar.Link -> (tvar as TypeVar.Link).type.realType()
        else -> this
    }

    fun isUnbound(): Boolean {
        val rt = realType()
        return rt is TVar && rt.tvar is TypeVar.Unbound
    }

    fun clone(): Type = when (this) {
        is TConst -> this
        is TApp -> copy(type.clone(), types.map(Type::clone))
        is TArrow -> copy(args.map(Type::clone), ret.clone())
        is TImplicit -> copy(type.clone())
        is TRecord -> copy(row.clone())
        is TRowExtend -> copy(labels.mapList { it.clone() }, row.clone())
        is TRowEmpty -> TRowEmpty()
        is TVar -> copyTvar()
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
    fun show(qualified: Boolean = true, typeVarsMap: Map<Int, String>? = null): String {
        fun showId(id: Id): String {
            return if (typeVarsMap != null && typeVarsMap.containsKey(id)) typeVarsMap[id]!!
            else if (id >= 0) "t$id" else "u${-id}"
        }

        fun go(t: Type, nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is TConst -> {
                val shouldQualify = !t.name.startsWith("prim.")
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
                    go(t.args[0], t.args[0] is TArrow)
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
            is TRowEmpty -> "[]"
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
                    !is TRowExtend -> {
                        if (labels.isBlank()) "| ${go(ty, topLevel = true)}"
                        else "$labels | ${go(ty, topLevel = true)}"
                    }
                    else -> {
                        val rows = go(ty, topLevel = true)
                        if (labels.isBlank()) rows.substring(2, rows.lastIndex - 1)
                        else "$labels, ${rows.substring(2, rows.lastIndex - 1)}"
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
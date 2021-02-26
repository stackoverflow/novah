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

import novah.Util.joinToStr
import novah.data.*

typealias Row = Type

data class TConst(val name: String) : Type()
data class TArrow(val left: Type, val right: Type) : Type()
data class TApp(val left: Type, val right: Type) : Type()
data class TVar(val name: String) : Type()
data class TForall(val name: String, val type: Type) : Type()
data class TRecord(val row: Row) : Type()
data class TRowExtend(val labels: PLabelMap<Type>, val row: Row) : Type()
data class TRowEmpty(val _id: Int = 0) : Type()
data class TMeta(val name: String) : Type()

sealed class Type {

    /**
     * Walks this type bottom->up
     */
    fun everywhere(f: (Type) -> Type): Type {
        fun go(t: Type): Type = when (t) {
            is TConst, is TVar, is TMeta, is TRowEmpty -> f(t)
            is TApp -> f(t.copy(left = go(t.left), right = go(t.right)))
            is TArrow -> f(t.copy(left = go(t.left), right = go(t.right)))
            is TForall -> f(t.copy(type = go(t.type)))
            is TRecord -> f(t.copy(go(t.row)))
            is TRowExtend -> f(t.copy(row = go(t.row), labels = t.labels.mapList { go(it) }))
        }
        return go(this)
    }

    fun isMono(): Boolean = when (this) {
        is TConst, is TRowEmpty, is TMeta, is TVar -> true
        is TArrow -> left.isMono() && right.isMono()
        is TApp -> left.isMono() && right.isMono()
        is TForall -> false
        is TRecord -> row.isMono()
        is TRowExtend -> row.isMono() && labels.allList { it.isMono() }
    }

    fun substVar(name: String, sub: Type): Type = everywhere { t ->
        if (t is TVar && t.name == name) sub else t
    }

    fun substMetas(m: Map<String, Type>): Type = everywhere { t ->
        if (t is TMeta && m.containsKey(t.name)) m[t.name]!! else t
    }

    fun containsMeta(name: String): Boolean = when (this) {
        is TMeta -> this.name == name
        is TConst, is TRowEmpty, is TVar -> false
        is TArrow -> left.containsMeta(name) || right.containsMeta(name)
        is TApp -> left.containsMeta(name) || right.containsMeta(name)
        is TForall -> type.containsMeta(name)
        is TRecord -> row.containsMeta(name)
        is TRowExtend -> row.containsMeta(name) || labels.anyList { it.containsMeta(name) }
    }

    fun unsolvedInType(unsolved: List<String>, ns: MutableList<String> = mutableListOf()): List<String> = when (this) {
        is TConst, is TVar, is TRowEmpty -> ns
        is TMeta -> {
            if (unsolved.contains(name) && !ns.contains(name)) ns += name
            ns
        }
        is TArrow -> {
            left.unsolvedInType(unsolved, ns)
            right.unsolvedInType(unsolved, ns)
        }
        is TApp -> {
            left.unsolvedInType(unsolved, ns)
            right.unsolvedInType(unsolved, ns)
        }
        is TForall -> type.unsolvedInType(unsolved, ns)
        is TRecord -> row.unsolvedInType(unsolved, ns)
        is TRowExtend -> {
            row.unsolvedInType(unsolved, ns)
            labels.forEachList { it.unsolvedInType(unsolved, ns) }
            ns
        }
    }

    /**
     * Pretty print version of [toString]
     */
    fun show(qualified: Boolean = true): String {
        fun go(t: Type, nested: Boolean = false, topLevel: Boolean = false): String = when (t) {
            is TConst -> if (qualified) t.name else t.name.split('.').last()
            is TVar -> t.name
            is TMeta -> "^${t.name}"
            is TApp -> {
                val sname = go(t.left, nested)
                val str = "$sname ${go(t.right, true)}"
                if (nested) "($str)" else str
            }
            is TArrow -> {
                val args = go(t.left, false)
                if (nested) "($args -> ${go(t.right, false)})"
                else "$args -> ${go(t.right, nested)}"
            }
            is TForall -> {
                val (names, type) = unnestForalls(t)
                val str = "forall ${names.joinToStr(" ")}. ${go(type, false)}"
                if (nested || !topLevel) "($str)" else str
            }
            is TRowEmpty -> "{}"
            is TRecord -> {
                when (val ty = t.row) {
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
                val str = when (val ty = t.row) {
                    is TRowEmpty -> labels
                    !is TRowExtend -> "$labels | ${go(ty, topLevel = true)}"
                    else -> {
                        val rows = go(ty, topLevel = true)
                        "$labels, ${rows.substring(2, rows.lastIndex - 1)}"
                    }
                }
                "[ $str ]"
            }
        }
        return go(this, false, topLevel = true)
    }

    companion object {
        fun instantiateForall(ty: TForall, vvar: Type): Type = ty.type.substVar(ty.name, vvar)

        fun nestForalls(names: List<String>, type: Type): Type =
            names.foldRight(type) { name, acc -> TForall(name, acc) }

        fun unnestForalls(type: TForall): Pair<List<String>, Type> {
            val names = mutableListOf(type.name)
            var ty = type.type
            while (ty is TForall) {
                names += ty.name
                ty = ty.type
            }
            return names to ty
        }

        fun nestArrows(types: List<Type>): Type =
            types.reduceRight { t, acc -> TArrow(t, acc) }

        fun nestApps(app: Type, args: List<Type>): Type =
            args.reversed().foldRight(app) { arg, acc -> TApp(acc, arg) }

        fun unnestApps(app: TApp): Pair<Type, List<Type>> {
            val ts = mutableListOf<Type>()
            ts += app.right
            var t = app.left
            while (t is TApp) {
                ts += t.right
                t = t.left
            }
            ts.reverse()
            return t to ts
        }
    }
}
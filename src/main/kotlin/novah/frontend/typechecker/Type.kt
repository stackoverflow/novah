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

    fun isMono(): Boolean = when (this) {
        is TConst, is TRowEmpty, is TMeta, is TVar -> true
        is TArrow -> left.isMono() && right.isMono()
        is TApp -> left.isMono() && right.isMono()
        is TForall -> false
        is TRecord -> row.isMono()
        is TRowExtend -> row.isMono() && labels.allList { it.isMono() }
    }

    fun substVar(name: String, sub: Type): Type = when (this) {
        is TConst, is TRowEmpty, is TMeta -> this
        is TVar -> if (this.name == name) sub else this
        is TArrow -> {
            val l = left.substVar(name, sub)
            val r = right.substVar(name, sub)
            if (l == left && r == right) this else TArrow(l, r)
        }
        is TApp -> {
            val l = left.substVar(name, sub)
            val r = right.substVar(name, sub)
            if (l == left && r == right) this else TApp(l, r)
        }
        is TForall -> {
            if (this.name == name) this
            else {
                val b = type.substVar(name, sub)
                if (type == b) this else copy(type = b)
            }
        }
        is TRecord -> {
            val r = row.substVar(name, sub)
            if (r == row) this else TRecord(r)
        }
        is TRowExtend -> {
            val r = row.substVar(name, sub)
            val ls = labels.mapList { it.substVar(name, sub) }
            if (r == row && labels == ls) this else TRowExtend(ls, r)
        }
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

    private fun TForall.unnest(): Pair<List<String>, Type> {
        val ids = mutableListOf(name)
        var ty = type
        while (ty is TForall) {
            ids += ty.name
            ty = ty.type
        }
        return ids to ty
    }

    /**
     * Pretty print version of [toString]
     */
    fun show(qualified: Boolean): String {
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
                val (ids, type) = t.unnest()
                val str = "forall ${ids.joinToStr(" ") { "t$it" }}. ${go(type, false)}"
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
        fun openTForall(ty: TForall, vvar: Type): Type = ty.substVar(ty.name, vvar)
    }
}
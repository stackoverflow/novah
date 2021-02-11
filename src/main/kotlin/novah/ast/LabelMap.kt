package novah.ast

import java.util.*
import novah.Util.map

/**
 * A sorted map of labels
 * with duplicated keys
 */
typealias LabelMap<V> = TreeMap<String, LinkedList<V>>

object LabelMapBuilder {

    fun <V> singleton(s: String, vs: LinkedList<V>): LabelMap<V> {
        val map = LabelMap<V>()
        map[s] = vs
        return map
    }
}

private val labelRegex = Regex("""[a-z](?:\w+|_)*""")

fun isValidLabel(ident: String): Boolean = ident.matches(labelRegex)

fun showLabel(label: String): String =
    if (isValidLabel(label)) label else "\"$label\""

fun <V> LabelMap<V>.show(fn: (String, V) -> String): String {
    val builder = StringBuilder()
    var addComma = false
    forEach { (k, l) ->
        l.forEach { v ->
            if (addComma) builder.append(", ")
            builder.append(fn(showLabel(k), v))
            addComma = true
        }
    }
    return builder.toString()
}

fun <V, R> LabelMap<V>.mapList(fn: (V) -> R): LabelMap<R> {
    val new = LabelMap<R>()
    forEach { (k, v) ->
        new[k] = v.map(fn)
    }
    return new
}

fun <V> LabelMap<V>.forEachList(fn: (V) -> Any) {
    forEach { (_, v) ->
        v.map(fn)
    }
}

fun <V, R> LabelMap<V>.flatMapList(fn: (V) -> Iterable<R>): List<R> {
    return flatMap { (_, v) ->
        v.flatMap(fn)
    }
}

fun <V> LabelMap<V>.allList(fn: (V) -> Boolean): Boolean {
    return all { (_, v) -> v.all(fn) }
}

fun <V> LabelMap<V>.putMulti(key: String, value: V) {
    val list = this[key]

    if (list != null) list.add(value)
    else {
        val ll = LinkedList<V>()
        ll.add(value)
        this[key] = ll
    }
}

fun <V> LabelMap<V>.merge(other: LabelMap<V>): LabelMap<V> {
    val new = LabelMap(this)
    other.forEach { (k, vs) ->
        vs.forEach { new.putMulti(k, it) }
    }
    return new
}

fun <V> LabelMap<V>.toLList(): List<Pair<String, LinkedList<V>>> {
    val list = mutableListOf<Pair<String, LinkedList<V>>>()
    forEach { (k, v) -> list += k to v }
    return list
}

@Suppress("UNCHECKED_CAST")
fun <V> labelMapWith(kvs: List<Pair<String, V>>): LabelMap<V> {
    val map = LabelMap<V>()
    kvs.forEach { (k, v) -> map.putMulti(k, v) }
    return map
}
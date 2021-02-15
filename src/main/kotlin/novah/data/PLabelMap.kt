package novah.data

import org.organicdesign.fp.collections.PersistentTreeMap
import java.util.*

typealias PLabelMap<V> = PersistentTreeMap<String, PersistentList<V>>

private val labelRegex = Regex("""[a-z](?:\w+|_)*""")

fun isValidLabel(ident: String): Boolean = ident.matches(labelRegex)

fun showLabel(label: String): String =
    if (isValidLabel(label)) label else "\"$label\""

fun <V> PLabelMap<V>.show(fn: (String, V) -> String): String {
    val builder = StringBuilder()
    var addComma = false
    forEach { k, l ->
        l.forEach { v ->
            if (addComma) builder.append(", ")
            builder.append(fn(showLabel(k), v))
            addComma = true
        }
    }
    return builder.toString()
}

fun <V> emptyPmap() = PLabelMap.empty<String, PersistentList<V>>()

fun <V> singletonPMap(k: String, v: V): PLabelMap<V> = PLabelMap.empty<String, PersistentList<V>>().assoc(k, Cons(v))

fun <V, R> PLabelMap<V>.mapList(fn: (V) -> R): PLabelMap<R> {
    var m: PLabelMap<R> = PLabelMap.empty()
    forEach { k, l ->
        m = m.assoc(k, l.map(fn))
    }
    return m
}

fun <V> PLabelMap<V>.forEachList(fn: (V) -> Unit) {
    forEach { _, v ->
        v.forEach(fn)
    }
}

fun <V, R> PLabelMap<V>.flatMapList(fn: (V) -> Iterable<R>): List<R> {
    return flatMap { (_, v) ->
        v.toList().flatMap(fn)
    }.toList()
}

fun <V> PLabelMap<V>.allList(fn: (V) -> Boolean): Boolean {
    return fold(true) { acc, v -> acc && v.value.all(fn) }
}

fun <V> PLabelMap<V>.putMulti(key: String, value: V): PLabelMap<V> {
    val list = this[key]

    return if (list != null) assoc(key, list.cons(value))
    else {
        val ll = Cons(value)
        assoc(key, ll)
    }
}

fun <V> PLabelMap<V>.assocat(key: String, values: PersistentList<V>): PLabelMap<V> {
    val list = this[key]

    return if (list != null) assoc(key, list.concat(values))
    else assoc(key, values)
}

fun <V> PLabelMap<V>.toPList(): List<Pair<String, PersistentList<V>>> {
    val list = mutableListOf<Pair<String, PersistentList<V>>>()
    forEach { k, v -> list += k to v }
    return list
}

fun <V> labelMapWith(kvs: List<Pair<String, V>>): PLabelMap<V> {
    return kvs.fold(emptyPmap()) { acc, el -> acc.putMulti(el.first, el.second) }
}

fun <V> PLabelMap<V>.merge(other: PLabelMap<V>): PLabelMap<V> {
    return other.fold(this) { acc, vs ->
        vs.value.foldl(acc) { acc2, el -> acc2.putMulti(vs.key, el) }
    }
}
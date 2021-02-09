package novah.ast

typealias LabelMap<V> = LinkedHashMap<String, V>

private val labelRegex = Regex("""[a-z](?:\w+|_)*""")

fun isValidLabel(ident: String): Boolean = ident.matches(labelRegex)

fun showLabel(label: String): String =
    if (isValidLabel(label)) label else "\"$label\""

fun <V> LabelMap<List<V>>.show(fn: (String, V) -> String): String {
    val builder = StringBuilder()
    var addComma = false
    forEach { (k, l) ->
        if (addComma) builder.append(", ")
        l.forEach { v ->
            builder.append(fn(showLabel(k), v))
        }
        addComma = true
    }
    return builder.toString()
}

fun <V> LabelMap<List<V>>.mapList(fn: (V) -> V): LabelMap<List<V>> {
    val new = LabelMap<List<V>>(size)
    forEach { (k, v) ->
        new[k] = v.map(fn)
    }
    return new
}

fun <V> LabelMap<MutableList<V>>.putMulti(key: String, value: V) {
    val list = this[key]

    if (list != null) list += value
    else this[key] = mutableListOf(value)
}

@Suppress("UNCHECKED_CAST")
fun <V> labelMapWith(kvs: List<Pair<String, V>>): LabelMap<List<V>> {
    val map = LabelMap<MutableList<V>>()
    kvs.forEach { (k, v) -> map.putMulti(k, v) }
    return map as LabelMap<List<V>>
}
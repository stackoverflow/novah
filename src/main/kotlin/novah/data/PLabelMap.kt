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
package novah.data

import io.lacuna.bifurcan.List
import io.lacuna.bifurcan.SortedMap

typealias PList<V> = List<V>

typealias PLabelMap<V> = SortedMap<String, PList<V>>

private val labelRegex = Regex("""[a-z](?:\w+|_)*""")

fun isValidLabel(ident: String): Boolean = ident.matches(labelRegex)

fun showLabel(label: String): String =
    if (isValidLabel(label)) label else "\"$label\""

fun <V> PLabelMap<V>.show(fn: (String, V) -> String): String {
    val builder = StringBuilder()
    var addComma = false
    forEach { kv ->
        kv.value().forEach { v ->
            if (addComma) builder.append(", ")
            builder.append(fn(showLabel(kv.key()), v))
            addComma = true
        }
    }
    return builder.toString()
}

fun <V> singletonPMap(k: String, v: V): PLabelMap<V> =
    PLabelMap<V>().put(k, PList.of(v))

fun <V, R> PList<V>.map(fn: (V) -> R): PList<R> {
    val nlist = List.empty<R>().linear()
    return fold(nlist) { acc, v ->
        acc.addLast(fn(v))
    }.forked()
}

fun <V> PList<V>.isEmpty() = size() == 0L

fun <V> PLabelMap<V>.isEmpty() = size() == 0L

fun <V, R> PLabelMap<V>.mapList(fn: (V) -> R): PLabelMap<R> {
    return mapValues { _, l -> l.map(fn) }
}

fun <V> PLabelMap<V>.forEachList(fn: (V) -> Unit) {
    forEach { kv ->
        kv.value().forEach(fn)
    }
}

fun <V, R> PLabelMap<V>.flatMapList(fn: (V) -> Iterable<R>): List<R> {
    return PList.from(flatMap { kv ->
        kv.value().flatMap(fn)
    })
}

fun <V> PLabelMap<V>.allList(fn: (V) -> Boolean): Boolean {
    return fold(true) { acc, v -> acc && v.value().all(fn) }
}

fun <V> PLabelMap<V>.putMulti(key: String, value: V): PLabelMap<V> {
    val list = get(key, null)

    return if (list != null) put(key, list.addFirst(value))
    else put(key, PList.of(value))
}

fun <V> PLabelMap<V>.assocat(key: String, values: PList<V>): PLabelMap<V> {
    val list = get(key, null)

    return if (list != null) put(key, list.concat(values) as PList<V>)
    else put(key, values)
}

fun <V> PLabelMap<V>.toPList(): List<Pair<String, PList<V>>> {
    val list = PList.empty<Pair<String, PList<V>>>().linear()
    return fold(list) { acc, kv ->
        acc.addLast(kv.key() to kv.value())
    }.forked()
}

fun <V> labelMapWith(kvs: kotlin.collections.List<Pair<String, V>>): PLabelMap<V> {
    return kvs.fold(PLabelMap()) { acc, el -> acc.putMulti(el.first, el.second) }
}

fun <V> PLabelMap<V>.merge(other: PLabelMap<V>): PLabelMap<V> {
    val m = forked().linear()
    return other.fold(m) { acc, kv ->
        acc.assocat(kv.key(), kv.value())
    }.forked()
}
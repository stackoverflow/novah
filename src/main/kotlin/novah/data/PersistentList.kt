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

sealed class PersistentList<out V> {

    fun size(): Int = foldl(0) { acc, _ -> acc + 1 }

    @Suppress("UNCHECKED_CAST")
    fun <R> map(f: (V) -> R) = foldl(Nil as PersistentList<R>) { acc, el -> acc.cons(f(el)) }

    fun all(f: (V) -> Boolean) = foldl(true) { acc, el -> acc && f(el) }

    fun <R> foldl(init: R, f: (R, V) -> R): R {
        var acc = init
        var l = this
        while (l is Cons) {
            acc = f(acc, l.head)
            l = l.tail
        }
        return acc
    }

    fun forEach(f: (V) -> Unit) {
        var l = this
        while (l is Cons) {
            f(l.head)
            l = l.tail
        }
    }

    fun <R> flatMap(f: (V) -> PersistentList<R>): PersistentList<R> =
        foldl(Nil as PersistentList<R>) { acc, el -> acc.concat(f(el)) }

    fun isEmpty() = this is Nil
}

object Nil : PersistentList<Nothing>()

data class Cons<V>(val head: V, val tail: PersistentList<V> = Nil) : PersistentList<V>()


fun <V> empty(): PersistentList<V> = Nil

fun <V> PersistentList<V>.cons(head: V): PersistentList<V> = Cons(head, this)

operator fun <V> PersistentList<V>.plus(other: V) = cons(other)

fun <V> PersistentList<V>.concat(other: PersistentList<V>) = foldl(other) { acc, el -> acc.cons(el) }

fun <V> PersistentList<V>.toList() = foldl(mutableListOf<V>()) {acc, el ->
    acc += el
    acc
}
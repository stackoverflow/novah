package novah

import java.lang.RuntimeException
import java.util.*

private class InternalError(msg: String) : RuntimeException(msg)

sealed class ListMatch<out E> {
    object Nil : ListMatch<Nothing>()
    data class HT<E>(val head: E, val tail: List<E>) : ListMatch<E>()
}

object Util {

    /**
     * Represents a compiler bug.
     * Should never happen.
     */
    fun internalError(message: String): Nothing {
        throw InternalError(message)
    }

    fun <E> List<E>.match(): ListMatch<E> {
        val head = firstOrNull()
        return if (head == null) return ListMatch.Nil
        else ListMatch.HT(head, drop(1))
    }

    /**
     * Unsafe for empty lists
     */
    fun <E> List<E>.headTail(): Pair<E, List<E>> = first() to drop(1)

    fun <E> List<E>.prepend(e: E): List<E> = listOf(e) + this

    fun <E> List<E>.hasDuplicates(): Boolean = toSet().size != size
    
    fun String.splitAt(index: Int): Pair<String, String> {
        return substring(0, index) to substring(index + 1)
    }

    fun <V, R> LinkedList<V>.map(fn: (V) -> R): LinkedList<R> {
        val new = LinkedList<R>()
        forEach { new.add(fn(it)) }
        return new
    }

    fun <V> linkedListOf(vararg vs: V): LinkedList<V> {
        val ll = LinkedList<V>()
        vs.forEach { ll.add(it) }
        return ll
    }

    /**
     * Like [joinToString] but don't append the prefix/suffix if
     * the list is empty.
     */
    fun <T> List<T>.joinToStr(
        separator: CharSequence = ", ",
        prefix: CharSequence = "",
        postfix: CharSequence = "",
        limit: Int = -1,
        truncated: CharSequence = "...",
        transform: ((T) -> CharSequence)? = null
    ): String {
        val pre = if (isEmpty()) "" else prefix
        val pos = if (isEmpty()) "" else postfix
        return joinTo(StringBuilder(), separator, pre, pos, limit, truncated, transform).toString()
    }
}
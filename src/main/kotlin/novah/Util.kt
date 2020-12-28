package novah

import java.lang.RuntimeException

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
}
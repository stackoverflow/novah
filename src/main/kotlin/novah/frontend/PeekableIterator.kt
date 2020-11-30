package novah.frontend

/**
 * An iterator that allows peeking the next value.
 */
class PeekableIterator<T>(private val iterator: Iterator<T>) : Iterator<T> {

    private var lookahead: T? = null

    private var current: T? = null

    override fun hasNext(): Boolean = lookahead != null || iterator.hasNext()

    override fun next(): T {
        current = if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            temp
        } else {
            iterator.next()
        }
        return current!!
    }

    fun peek(): T {
        lookahead = lookahead ?: iterator.next()
        return lookahead!!
    }

    fun current(): T {
        return if (current == null) {
            error("called current element before the iterator started")
        } else current!!
    }
}
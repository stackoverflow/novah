package novah.frontend

/**
 * An iterator that allows peeking the next value.
 */
class PeekableIterator<T>(private val iterator: Iterator<T>) : Iterator<T> {

    private var lookahead: T? = null

    override fun hasNext(): Boolean = lookahead != null || iterator.hasNext()

    override fun next(): T {
        return if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            temp
        } else {
            iterator.next()
        }
    }

    fun peek(): T {
        lookahead = lookahead ?: iterator.next()
        return lookahead!!
    }
}
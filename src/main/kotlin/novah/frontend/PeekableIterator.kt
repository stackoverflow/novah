package novah.frontend

/**
 * An iterator that allows peeking the next value
 * and ignoring some value
 */
class PeekableIterator<T>(private val iterator: Iterator<T>) : Iterator<T> {

    private var lookahead: T? = null

    private var shouldIgnore = false
    private var ignoreFun: (T) -> Boolean = { false }

    override fun hasNext(): Boolean {
        return if (lookahead != null) {
            true
        } else {
            iterator.hasNext()
        }
    }

    override fun next(): T {
        return if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            if (shouldIgnore && ignoreFun(temp)) {
                next()
            } else temp
        } else {
            val t = iterator.next()
            if (shouldIgnore && ignoreFun(t)) {
                next()
            } else t
        }
    }

    fun peek(): T {
        lookahead = lookahead ?: iterator.next()
        while(shouldIgnore && ignoreFun(lookahead!!)) {
            lookahead = iterator.next()
        }
        return lookahead!!
    }

    fun ignore(fn: (T) -> Boolean) {
        shouldIgnore = true
        ignoreFun = fn
    }

    fun stopIgnoring() {
        shouldIgnore = false
    }
}
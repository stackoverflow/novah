package novah.data

import novah.frontend.Spanned

/**
 * An iterator that allows peeking the next value
 * and keeps tab of the offside rule.
 */
class PeekableIterator<T>(private val iterator: Iterator<Spanned<T>>, private val onError: (Spanned<T>) -> Nothing) :
    Iterator<Spanned<T>> {

    private var lookahead: Spanned<T>? = null

    private var current: Spanned<T>? = null

    // keep track of offside rules
    private var offside = 1
    private var ignoreOffside = false

    override fun hasNext(): Boolean = lookahead != null || iterator.hasNext()

    override fun next(): Spanned<T> {
        val t = if (lookahead != null) {
            val temp = lookahead!!
            lookahead = null
            temp
        } else {
            iterator.next()
        }
        if (!ignoreOffside && t.offside() < offside) onError(t)
        current = t
        return t
    }

    fun peek(): Spanned<T> {
        lookahead = lookahead ?: iterator.next()
        return lookahead!!
    }

    fun peekIsOffside(): Boolean {
        return !ignoreOffside && peek().offside() < offside
    }

    fun current(): Spanned<T> {
        return if (current == null) {
            error("called current element before the iterator started")
        } else current!!
    }

    fun ignoreOffside() = ignoreOffside

    fun withIgnoreOffside(shouldIgnore: Boolean = true) {
        ignoreOffside = shouldIgnore
    }

    fun offside() = offside

    fun withOffside(column: Int) {
        offside = column
    }
}
package novah.util

import java.io.Reader

/**
 * Char iterator backed by a reader.
 */
class BufferedCharIterator(private val reader: Reader) : Iterator<Char> {
    
    private var next: Int = reader.read()
    
    override fun hasNext(): Boolean {
        return next != -1
    }

    override fun next(): Char {
        if (next == -1) throw NoSuchElementException("Reader is already at end.")
        val chr = next.toChar()
        next = reader.read()
        return chr
    }
}
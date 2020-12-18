package novah

import java.lang.RuntimeException

object Util {

    private class InternalError(msg: String) : RuntimeException(msg)

    /**
     * Represents a compiler bug.
     * Should never happen.
     */
    fun internalError(message: String): Nothing {
        throw InternalError(message)
    }
}
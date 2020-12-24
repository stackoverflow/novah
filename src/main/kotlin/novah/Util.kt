package novah

import java.lang.RuntimeException

private class InternalError(msg: String) : RuntimeException(msg)

object Util {

    /**
     * Represents a compiler bug.
     * Should never happen.
     */
    fun internalError(message: String): Nothing {
        throw InternalError(message)
    }
}
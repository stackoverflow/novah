package novah.frontend.typechecker

import novah.frontend.Span
import novah.frontend.error.ProblemContext

object Typechecker {
    private var ids = mutableMapOf<String, Int>()

    fun resetIds() {
        ids.clear()
    }

    fun freshName(name: String): String {
        val next = ids[name] ?: 0
        ids[name] = next + 1
        return "$name\$$next"
    }
}

class InferenceError(val msg: String, val span: Span, val ctx: ProblemContext) : RuntimeException(msg)

fun inferError(msg: String, span: Span, ctx: ProblemContext = ProblemContext.TYPECHECK): Nothing =
    throw InferenceError(msg, span, ctx)
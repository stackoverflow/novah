/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend.error

import novah.frontend.Span
import novah.frontend.typechecker.TypingContext

enum class Severity {
    WARN, ERROR, FATAL
}

sealed class Action {
    object None : Action()
    data class NoType(val inferedTty: String) : Action()
    data class UnusedImport(val vvar: String) : Action()
}

/**
 * Some problem returned by the compilation process.
 */
data class CompilerProblem(
    val msg: String,
    val span: Span,
    val fileName: String,
    val module: String? = null,
    val typingContext: TypingContext? = null,
    val severity: Severity = Severity.ERROR,
    val action: Action = Action.None
) {
    fun formatToConsole(): String {
        val mod = if (module != null) "module $YELLOW$module$RESET\n" else ""
        val at = "at $fileName:$span\n\n"

        val ctx = if (typingContext != null) formatTypingContext(typingContext) else ""
        return "$mod$at${msg.prependIndent("  ")}\n\n$ctx"
    }

    fun isFatal() = severity == Severity.FATAL

    fun isErrorOrFatal() = severity != Severity.WARN

    private fun formatTypingContext(ctx: TypingContext): String {
        var str = ""
        val ty = ctx.types.peek()
        if (ty != null) {
            str += "while checking type ${ty.show()}\n"
        }

        val decl = ctx.decl
        if (decl != null) {
            str += "in declaration ${decl.name.value}"
        }
        return if (str.isEmpty()) str else "$str\n\n"
    }

    companion object {
        const val RED = "\u001b[31m"
        const val YELLOW = "\u001b[33m"
        const val RESET = "\u001b[0m"
    }
}
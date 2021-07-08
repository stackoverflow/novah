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

import novah.ast.canonical.show
import novah.frontend.Span
import novah.frontend.typechecker.TypingContext

enum class Severity {
    WARN, ERROR;
}

/**
 * Some problem returned by the compilation process.
 */
data class CompilerProblem(
    val msg: String,
    val context: ProblemContext,
    val span: Span,
    val fileName: String,
    val module: String? = null,
    val typingContext: TypingContext? = null,
    val severity: Severity = Severity.ERROR
) {
    fun formatToConsole(): String {
        val mod = if (module != null) "module $YELLOW$module$RESET\n" else ""
        val at = "at $fileName:$span\n\n"
        
        val ctx = if (typingContext != null) formatTypingContext(typingContext) else ""
        return "$mod$at${msg.prependIndent("  ")}\n\n$ctx"
    }
    
    private fun formatTypingContext(ctx: TypingContext): String {
        var str = ""
        val ty = ctx.types.peek()
        if (ty != null) {
            str += "while checking type ${ty.show()}\n"
        }

        val exp = ctx.exps.peek()
        if (exp != null) {
            var expStr = exp.show()
            expStr = if (expStr.length > MAX_EXPR_SIZE) expStr.substring(0, MAX_EXPR_SIZE) + " ..." else expStr
            str += "while checking expression $expStr\n"
        }

        val decl = ctx.decl
        if (decl != null) {
            str += "in declaration ${decl.name}"
        }
        return if (str.isEmpty()) str else "$str\n\n"
    }

    companion object {
        const val RED = "\u001b[31m"
        const val YELLOW = "\u001b[33m"
        const val RESET = "\u001b[0m"
        
        const val MAX_EXPR_SIZE = 50
    }
}

/**
 * The context in which the problem
 * happened.
 */
enum class ProblemContext {
    MODULE,
    PARSER,
    IMPORT,
    FOREIGN_IMPORT,
    DESUGAR,
    TYPECHECK,
    PATTERN_MATCHING,
    FOREIGN,
    UNIFICATION;
}
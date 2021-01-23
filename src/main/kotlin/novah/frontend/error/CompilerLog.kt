package novah.frontend.error

import novah.frontend.Span

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
    val severity: Severity = Severity.ERROR
) {
    fun formatToConsole(): String {
        val mod = if (module != null) "module $YELLOW$module$RESET\n" else ""
        val at = "at $fileName:$span\n\n"
        return "$mod$at${msg.prependIndent("  ")}\n\n"
    }

    companion object {
        const val RED = "\u001b[31m"
        const val YELLOW = "\u001b[33m"
        const val RESET = "\u001b[0m"
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
    SUBSUMPTION,
    INSTL,
    INSTR,
    PATTERN_MATCHING,
    FOREIGN,
    OPTIMIZATION;
}
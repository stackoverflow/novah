package novah.frontend

object Errors {
    val MODULE_DEFINITION = """Expected file to begin with a module declaration.
        |Example:
        |
        |module Some.Module
    """.trimMargin()

    const val MODULE_NAME = "Module names should be composed of upper case identifiers separated by dots."

    val IMPORT_ALIAS = """Expected module import alias to be an upper case identifier:
        |Example: import Data.Module as Mod
    """.trimMargin()

    const val EXPORT_REFER = "Expected exposing/hiding definitions to be a comma-separated list of upper or lower case identifiers."

    const val DATA_NAME = "Expected new data type name to be a upper case identifier."

    const val DATA_EQUALS = "Expected equals `=` after data name declaration."

    const val TYPE_DCOLON = "Expected `::` before type definition."

    const val TYPE_VAR = "Expected type variable (lower case identifier)."

    const val TYPE_DEF = "Expected a type definition."

    const val FORALL_DOT = "Expected `.` after forall quantifier."

    const val CTOR_NAME = "Expected constructor name (upper case identifier)."

    const val TOPLEVEL_IDENT = "Expected variable definition or variable type at the top level."

    val PATTERN = """Expected a pattern expression.
        |Patterns can be one of:
        |
        |Wildcard pattern: _
        |Literal pattern: 3, 'a', "a string", false etc
        |Variable pattern: x, y, myVar, etc
        |Constructor pattern: Just "ok", Result res, Nothing, etc
    """.trimMargin()

    val LAMBDA_VAR = """Expected identifier after start of lambda definition:
        |Example: \x -> x + 3
    """.trimMargin()

    const val EQUALS = "Expected `=` after function parameters/patterns."

    const val LAMBDA_ARROW = "Expected `->` after lambda parameter definition."

    const val LAMBDA_BACKSLASH = "Expected lambda definition to start with backslash: `\\`."

    const val VARIABLE = "Expected variable name."

    const val THEN = "Expected `then` after if condition."

    const val ELSE = "Expected `else` after then condition."

    const val LET_DECL = "Expected variable name after `let`."

    const val LET_EQUALS = "Expected `=` after let name declaration."

    const val LET_IN = "Expected `in` after let definition."

    val LET_AND = """Expected `and` after type declaration.
        |
        |ex: let x :: Int
        |    and x = 8 in x
    """.trimMargin()

    val LET_TYPE = """Types should be defined before their declaration, not after.
        |
        |ex: let x :: Int
        |    and x = 8 in x
    """.trimMargin()

    const val CASE_OF = "Expected `of` after a case expression."

    const val CASE_ARROW = "Expected `->` after case pattern."

    const val MALFORMED_EXPR = "Malformed expression."

    const val IMPORTED_DOT = "Expected identifier after imported variable reference."

    val IMPORT_NOT_FOUND = """Could not find imported alias. Make sure the module is imported.
        |
        |ex: import Some.Module as M
    """.trimMargin()

    fun literalExpected(name: String) = "Expected $name literal."

    fun lparensExpected(ctx: String) = "Expected `(` after $ctx."
    fun rparensExpected(ctx: String) = "Expected `)` after $ctx."

    fun lbracketExpected(ctx: String) = "Expected `{` after $ctx."
    fun rbracketExpected(ctx: String) = "Expected `}` after $ctx."

    fun lsbracketExpected(ctx: String) = "Expected `[` after $ctx."
    fun rsbracketExpected(ctx: String) = "Expected `]` after $ctx."

    fun semicolonExpected(ctx: String) = "Expected `;` after $ctx."

    fun pipeExpected(ctx: String) = "Expected `|` after $ctx."
}
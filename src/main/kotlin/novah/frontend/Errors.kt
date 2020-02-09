package novah.frontend

object Errors {
    val MODULE_DEFINITION = """Expected file to begin with a module declaration.
        |Example:
        |
        |module Some.Module
    """.trimMargin()

    val MODULE_END = """Expected proper module definition end.
        |Modules should end either with a new line of with a `hiding` or `exposing` definition
        |and can only have an indentation after a hiding or exposing.
        |Examples:
        |
        |module MyModule
        |
        |module MyModule hiding
        |  ( privateFun
        |  , otherFun
        |  )
        |
        |module MyModule exposing (publicFun, otherFun)
    """.trimMargin()

    const val MODULE_NAME = "Module names should be composed of upper case identifiers separated by dots."

    val IMPORT_ALIAS = """Expected module import alias to be an upper case identifier:
        |Example: import Data.Module as Mod
    """.trimMargin()

    val IMPORT_INVALID = """Invalid import definition.
        |Valid examples:
        |
        |import Data.Module
        |import Data.Module as M
        |import Data.Module (Type, function1, function2)
    """.trimMargin()

    const val EXPORT_REFER = "Expected exposing/hiding definitions to be a comma-separated list of upper or lower case identifiers."

    const val DATA_NAME = "Expected new data type name to be a upper case identifier."

    const val DATA_EQUALS = "Expected equals symbol: `=` after data name declaration."

    const val LET_EQUALS = "Expected equals symbol: `=` after let name declaration."

    const val TYPE_VAR = "Expected type variable (lower case identifier)."

    const val TYPE_DEF = "Expected a type definition."

    const val FORALL_DOT = "Expected a dot: `.` after forall quantifier."

    const val CTOR_NAME = "Expected constructor name (upper case identifier)."

    const val PIPE_SEPARATOR = "Expected pipe character: `|` between data constructors."

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

    const val EQUALS = "Expected equals symbol: `=` after function parameters/patterns."

    const val LAMBDA_ARROW = "Expected arrow symbol: `->` after lambda parameter definition."

    const val LAMBDA_BACKSLASH = "Expected lambda definition to start with backslash: `\\`."

    const val VARIABLE = "Expected variable name."

    const val THEN = "Expected `then` after if condition."

    const val ELSE = "Expected `else` after then condition."

    const val GENERIC_DEDENT = "Expected closing indentation."

    const val TYPE_DECL = "Expected start of type declaration: `::`"

    const val LET_DECL = "Expected variable name after `let`."

    const val EXPRESSION_DEF = "Expected expression definition."

    const val CASE_OF = "Expected `of` after a case expression."

    const val CASE_ARROW = "Expected arrow symbol: `->` after case pattern."

    val MALFORMED_EXPR = "Malfored expression."

    fun literalExpected(name: String) = "Expected $name literal."

    fun newlineExpected(ctx: String) = "Expected new line after $ctx."

    fun lparensExpected(ctx: String) = "Expected open parens after $ctx."
    fun rparensExpected(ctx: String) = "Expected closing parens after $ctx."

    fun indentExpected(ctx: String) = "Expected opening indentation after $ctx."
    fun dedentExpected(ctx: String) = "Expected closing indentation after $ctx."

    fun commaExpected(ctx: String) = "Expected comma: `,` between $ctx."
}
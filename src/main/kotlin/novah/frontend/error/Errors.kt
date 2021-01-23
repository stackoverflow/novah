package novah.frontend.error

import novah.frontend.typechecker.Name
import novah.frontend.typechecker.Type

object Errors {
    val MODULE_DEFINITION = """Expected file to begin with a module declaration.
        |Example:
        |
        |module some.namespace
    """.trimMargin()

    const val MODULE_NAME =
        "Module names should be composed of identifiers started with a lower case character and separated by dots."

    val IMPORT_ALIAS = """Expected module import alias to be capitalized:
        |Example: import data.namespace as Mod
    """.trimMargin()

    val FOREIGN_IMPORT = """Expected keyword `import` after foreign:
        |Example: foreign import java.util.ArrayList as AList
    """.trimMargin()

    const val UPPER_LOWER = "Expected upper or lower case starting identifier."

    const val EXPORT_REFER =
        "Expected exposing/hiding definitions to be a comma-separated list of upper or lower case identifiers."

    const val DATA_NAME = "Expected new data type name to be a upper case identifier."

    const val DATA_EQUALS = "Expected equals `=` after data name declaration."

    const val TYPE_DCOLON = "Expected `::` before type definition."

    const val TYPE_VAR = "Expected type variable (lower case identifier)."

    const val TYPE_DEF = "Expected a type definition."

    val FORALL_TVARS = """Expected type variable(s) after forall quantifier:
        |Example: forall a b. a -> b
    """.trimMargin()

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

    val LET_TYPE = """Types should be defined before their declaration, not after.
        |
        |ex: let x :: Int
        |        x = 8 in x
    """.trimMargin()

    const val CASE_OF = "Expected `of` after a case expression."

    const val CASE_ARROW = "Expected `->` after case pattern."

    const val MALFORMED_EXPR = "Malformed expression."

    const val IMPORTED_DOT = "Expected identifier after imported variable reference."

    const val TYPEALIAS_DOT = "Expected type identifier after dot."

    const val MISMATCHED_INDENTATION = "Mismatched indentation."

    val DECLARATION_REF_ALL = """To import or export all constructor of a type use a (..) syntax.
        |
        |ex: import namespace (fun1, SomeType(..), fun2)
    """.trimMargin()

    const val EMPTY_DO = "`do` statement cannot be empty."

    const val EMPTY_MATCH = "Pattern match needs at least 1 case."

    const val INCOMPLETE_CONTEXT = "Context is not complete."

    const val NON_EXHAUSTIVE_PATTERN = "A case expression could not be determined to cover all inputs."

    const val FOREIGN_TYPE_ALIAS = "Type has to start with a upper case letter."

    const val FOREIGN_ALIAS = "Identifier has to start with a lower case letter."
    
    const val LET_DO_IN = "Let expressions in a do statement cannot have an `in` clause."
    
    const val LET_DO_LAST = "Do expression cannot end with a let definition."
    
    private val foreignExamples = mapOf(
        "getter" to """foreign import get my.java.SomeClass.field
            |// static field
            |foreign import get java.lang.Integer:MAX_VALUE as maxValue""".trimMargin(),
        "type" to """foreign import type java.util.ArrayList as List
            |foreign import type java.io.File""".trimMargin(),
        "constructor" to "foreign import new java.util.ArrayList(Int) as newArrayList",
        "method" to """foreign import java.io.File.setExecutable(Boolean)
            |// static method
            |foreign import java.io.File:createTempFile(String, String) as makeTmpFile""".trimMargin(),
        "setter" to """foreign import set some.package.MyClass.field as setField
            |// static field
            |foreign import set some.package.MyStaticClass:FIELD as setField
        """.trimMargin()
    )

    fun invalidForeign(ctx: String?): String {
        val example = if (ctx != null) foreignExamples[ctx] else foreignExamples.values.joinToString("\n")
        return """Wrong format for foreign $ctx import.
        |
        |Example:
        |$example
    """.trimMargin()
    }

    fun classNotFound(name: String) = "Could not find class $name in classpath."

    fun methodNotFound(name: String, type: String) = "Could not find method $name for type $type."

    fun nonStaticMethod(name: String, type: String) = "Method $name of class $type is not static."

    fun staticMethod(name: String, type: String) = "Method $name of class $type is static."

    fun ctorNotFound(type: String) = "Could not find constructor of class $type."

    fun fieldNotFound(name: String, type: String) = "Could not find field $name for class $type."

    fun nonStaticField(name: String, type: String) = "Field $name of class $type is not static."

    fun staticField(name: String, type: String) = "Field $name of class $type is static."

    fun immutableField(name: String, type: String) = "Field $name of class $type is not mutable."

    fun undefinedVarInCtor(name: String, typeVars: List<String>): String {
        return if (typeVars.size == 1) "The variable ${typeVars[0]} is undefined in constructor $name."
        else "The variables ${typeVars.joinToString()} are undefined in constructor $name."
    }

    fun exportError(v: String) = if (v[0].isLowerCase()) {
        "Cannot export unknown value $v."
    } else {
        "Cannot export unknown type or constructor $v."
    }

    fun emptyImportExport(ctx: String) = "${ctx.capitalize()} list cannot be empty."

    fun literalExpected(name: String) = "Expected $name literal."

    fun duplicateModule(name: String) = "Found duplicate module $name."

    fun cycleFound(nodes: List<String>) =
        nodes.joinToString(", ", prefix = "Found cycle between modules ", postfix = ".")

    fun moduleNotFound(name: String) = "Could not find module $name."

    fun cannotFindInModule(name: String, module: String) = "Cannot find $name in module $module."
    fun cannotImportInModule(name: String, module: String) = "Cannot import private $name in module $module."

    fun topLevelDisallowed(declName: String) =
        "Only lambdas and primitives can be defined at the top level for declaration $declName."

    fun wrongConstructorName(typeName: String) =
        "Multi constructor type cannot have the same name as their type: $typeName."

    fun undefinedType(type: Name) = "Undefined type $type."

    fun wrongArgsToNative(name: Name, ctx: String, should: Int, got: Int) = """
        Foreign setters, methods and constructors cannot be partially applied.
        Foreign $ctx $name needs $should parameter(s), got $got.
    """.trimIndent()

    fun unkonwnArgsToNative(name: Name, ctx: String) = """
        Foreign setters, methods and constructors cannot be partially applied.
        For $ctx $name.
    """.trimIndent()

    fun wrongKind(expected: String, got: String) = """
        Could not match kind
        
            $expected
            
        with kind
        
            $got
    """.trimIndent()

    fun duplicatedType(name: Name) = "Duplicated type $name."

    fun cannotSolvePolytype(name: Name, type: Type) = "Cannot solve type $name with polytype $type."

    fun unknownType(name: Type) = "Unknown type $name."

    fun infiniteType(name: Type) = "Occurs check failed: infinite type $name."

    fun subsumptionFailed(a: Type, b: Type) = """
        Cannot subsume type
        
            $a
        
        with type
        
            $b
    """.trimIndent()

    fun undefinedVar(name: Name) = "Undefined variable $name."

    fun cannotCheck(type: Type) = "Cannot check type of $type."

    fun wrongArgumentsToCtor(name: Name, quant: String) = "Too $quant arguments given to type constructor $name."

    fun overlappingNamesInBinder(names: List<Name>) = """
        Overlapping names in binder:
        
            ${names.joinToString()}
    """.trimIndent()

    fun shadowedVariable(name: Name) = "Variable $name is shadowed."

    fun redundantMatches(pats: List<String>) = """
        A case expression contains redundant cases:
        
            ${pats.joinToString()}
    """.trimIndent()

    fun lparensExpected(ctx: String) = "Expected `(` after $ctx."
    fun rparensExpected(ctx: String) = "Expected `)` after $ctx."

    fun lbracketExpected(ctx: String) = "Expected `{` after $ctx."
    fun rbracketExpected(ctx: String) = "Expected `}` after $ctx."

    fun lsbracketExpected(ctx: String) = "Expected `[` after $ctx."
    fun rsbracketExpected(ctx: String) = "Expected `]` after $ctx."

    fun pipeExpected(ctx: String) = "Expected `|` after $ctx."
}
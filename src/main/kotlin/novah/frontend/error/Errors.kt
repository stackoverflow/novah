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

import java.util.*

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

    const val UPPER_LOWER_STR = "Expected a string or an identifier."

    const val IMPORT_REFER =
        "Expected exposing definitions to be a comma-separated list of upper or lower case identifiers."

    const val DATA_NAME = "Expected new data type name to be a upper case identifier."

    const val DATA_EQUALS = "Expected equals `=` after data name declaration."

    const val TYPE_COLON = "Expected `:` before type definition."

    const val RECORD_COLON = "Expected `:` after record label."

    const val RECORD_EQUALS = "Expected `=` after record labels in update expression."

    const val RECORD_MERGE = "Cannot merge records with unknown labels."

    const val TYPE_VAR = "Expected type variable (lower case identifier)."

    const val TYPE_DEF = "Expected a type definition."

    const val CTOR_NAME = "Expected constructor name (upper case identifier)."

    const val TOPLEVEL_IDENT = "Expected variable definition or variable type at the top level."

    val PATTERN = """Expected a pattern expression.
        |Patterns can be one of:
        |
        |Wildcard pattern: _
        |Literal pattern: 3, 'a', "a string", false, etc
        |Variable pattern: x, y, myVar, etc
        |Constructor pattern: Some "ok", Result res, None, etc
        |Record pattern: { x, y: 3 }
        |List pattern: [], [x, y, _], [x :: xs]
        |Named pattern: 10 as number
        |Type test: :? Int as i
    """.trimMargin()

    val LAMBDA_VAR = """Expected identifier after start of lambda definition:
        |Example: \x -> x + 3
    """.trimMargin()

    const val LAMBDA_ARROW = "Expected `->` after lambda parameter definition."

    const val LAMBDA_BACKSLASH = "Expected lambda definition to start with backslash: `\\`."

    const val VARIABLE = "Expected variable name."

    const val THEN = "Expected `then` after if condition."

    const val ELSE = "Expected `else` after then condition."

    const val LET_DECL = "Expected variable name after `let`."

    const val LET_EQUALS = "Expected `=` after let name declaration."

    const val LET_IN = "Expected `in` after let definition."

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

    const val NON_EXHAUSTIVE_PATTERN = "A case expression could not be determined to cover all inputs."

    const val FOREIGN_TYPE_ALIAS = "Type has to start with a upper case letter."

    const val FOREIGN_ALIAS = "Identifier has to start with a lower case letter."

    const val FOREIGN_METHOD_ERROR = "Wrong syntax for foreign method."

    const val LET_DO_LAST = "Do expression cannot end with a let definition."

    const val PUB_PLUS = "Visibility of value or typealias declaration can only be public (pub) not pub+."

    val NOT_A_FUNCTION = """
        Expected expression to be a function.
        If you are trying to pass an instance argument to a function explicitily
        make sure to use the {{}} syntax.
        Ex.: myFunction {{parameter}}
    """.trimIndent()

    const val TYPEALIAS_NAME = "Expected name for typealias."

    const val TYPEALIAS_EQUALS = "Expected `=` after typealias declaration."

    const val TYPEALIAS_PUB = "A public type alias cannot reference private types."

    const val RECORD_LABEL = "A label of a record can only be a lower case identifier or a String."

    const val RECURSIVE_ROWS = "Recursive row types"

    const val INSTANCE_TYPE = "Instance types need to be enclosed in double brackets: {{ type }}."

    const val INSTANCE_VAR = "Instance variables need to be enclosed in double brackets: {{var}}."

    const val INSTANCE_ERROR = "Type and type alias declarations cannot be instances, only values."

    const val ALIAS_DOT = "Expected dot (.) after aliased variable."

    const val INVALID_OPERATOR_DECL = "Operator declarations have to be defined between parentheses."

    const val INVALID_OPAQUE = "Opaque types should be in the form: opaque type <Name> = <type>."

    val IMPORT_RAW = """Raw imports should only be used for core namespaces.
        |Use an alias or explicit import instead.""".trimMargin()

    const val TYPE_TEST_TYPE = "Expected type in type test."

    const val IMPLICIT_PATTERN =
        "Implicit patterns can only be used in function parameters before any destructuring happens."

    const val APPLIED_DO_LET = "Cannot apply let statement as a function."

    const val NO_FINALLY = "Expected `finally` definition after try expression without cases."

    const val WRONG_CATCH = "Catch patterns can only be a type test and cannot have guards."

    const val DO_WHILE = "Expected keyword `do` after while condition."

    const val EXP_SIMPLE = "Invalid expression for while condition."

    const val LET_BANG = "`let!` syntax can only be used inside a computation expression."

    val ANONYMOUS_FUNCTION_ARGUMENT = """
        Invalid context for anonymous function argument.
        
        Valid ones are:
        Operator sections: (_ + 1)
        Record access: _.name
        Record values: { name: _ }, { age: 10 | _ }
        Record restrictions: { - name | _ }
        Ifs: if _ then 1 else 0, if check then _ else _
        Cases: case _ of ...
    """.trimIndent()

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

    fun notException(type: String) = """
        Type
        
            $type
        
        is not a subclass of Throwable and cannot be thrown.
    """.trimIndent()

    fun wrongArityToCase(got: Int, expected: Int) = "Case expression expected $expected patterns but got $got."

    fun wrongArityCtorPattern(name: String, got: Int, expected: Int) =
        "Constructor pattern $name expected $expected parameter(s) but got $got."

    fun noTypeAnnDecl(name: String, inferedType: String) = """
        No type annotation given for top-level declaration $name.
        Consider adding a type annotation.
        
        The inferred type was:
        
            $inferedType
    """.trimIndent()

    fun expectedDefinition(name: String) = "Expected definition to follow its type declaration for $name."

    fun expectedLetDefinition(name: String) =
        "Expected definition to follow its type declaration for $name in let clause."

    fun classNotFound(name: String) = "Could not find class $name in classpath."

    fun methodNotFound(name: String, type: String) = "Could not find method $name for type $type."

    fun nonStaticMethod(name: String, type: String) = "Method $name of class $type is not static."

    fun staticMethod(name: String, type: String) = "Method $name of class $type is static."

    fun ctorNotFound(type: String) = "Could not find constructor $type."

    fun fieldNotFound(name: String, type: String) = "Could not find field $name for class $type."

    fun nonStaticField(name: String, type: String) = "Field $name of class $type is not static."

    fun staticField(name: String, type: String) = "Field $name of class $type is static."

    fun immutableField(name: String, type: String) = "Field $name of class $type is not mutable."

    fun undefinedVarInCtor(name: String, typeVars: List<String>): String {
        return if (typeVars.size == 1) "The variable ${typeVars[0]} is undefined in constructor $name."
        else "The variables ${typeVars.joinToString()} are undefined in constructor $name."
    }

    fun emptyImportExport(ctx: String) =
        "${ctx.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }} list cannot be empty."

    fun duplicatedImport(name: String) = "Another import with name $name is already in scope."

    fun literalExpected(name: String) = "Expected $name literal."

    fun duplicateModule(name: String) = "Found duplicate module $name."

    fun cycleFound(nodes: List<String>) =
        nodes.joinToString(prefix = "Found cycle between modules ", postfix = ".")

    fun cycleInValues(nodes: List<String>) =
        nodes.joinToString(prefix = "Found cycle between values ", postfix = ".")

    fun cycleInFunctions(nodes: List<String>) =
        nodes.joinToString(prefix = "Mutually recursive functions ", postfix = " need type annotations.")

    fun moduleNotFound(name: String) = "Could not find module $name."

    fun cannotFindInModule(name: String, module: String) = "Cannot find $name in module $module."
    fun cannotImportInModule(name: String, module: String) = "Cannot import private $name in module $module."

    fun wrongConstructorName(typeName: String) =
        "Multi constructor type cannot have the same name as their type: $typeName."

    fun undefinedType(type: String) = "Undefined type $type."

    fun wrongArgsToNative(name: String, ctx: String, should: Int, got: Int) = """
        Foreign setters, methods and constructors cannot be partially applied.
        Foreign $ctx $name needs $should parameter(s), got $got.
    """.trimIndent()

    fun unkonwnArgsToNative(name: String, ctx: String) = """
        Foreign setters, methods and constructors cannot be partially applied.
        For $ctx $name.
    """.trimIndent()

    fun wrongKind(expected: String, got: String) = """
        Could not match kind
        
            $expected
            
        with kind
        
            $got
    """.trimIndent()

    fun infiniteType(name: String) = "Occurs check failed: infinite type $name."

    fun typesDontMatch(a: String, b: String, reason: String?): String {
        val fmt = """
            Cannot match type
            
                $a
            
            with type
            
                $b
        """.trimIndent()

        return if (reason == null) fmt else fmt + "\n\n$reason"
    }
    
    fun incompatibleTypes(t1: String, t2: String) = "Incompatible types $t1 and $t2."

    fun implicitTypesDontMatch(vari: String, type: String) = """
        Cannot match instance variable $vari with non-instance type
        
            $type
    """.trimIndent()

    fun maxSearchDepth(type: String) = """
        Could not find instance for type
        
            $type
        
        The maximum recursion depth was reached.
    """.trimIndent()

    fun unamedType(type: String) = """
        Type
        
            $type
        
        is not a named type.
        Only named types can be used as instance arguments.
    """.trimIndent()

    fun partiallyAppliedAlias(name: String, expected: Int, got: Int): String = """
        Partially applied type alias $name.
        $name expects $expected parameter(s) but got $got.
    """.trimIndent()

    fun recursiveAlias(name: String) = "Typealias $name is recursive."

    fun undefinedVar(name: String) = "Undefined variable $name."

    fun shadowedVariable(name: String) = "Value $name is shadowing another value with the same name."

    fun freeVarsInTypealias(talias: String, vars: Set<String>) = """
        Type variables
        
            ${vars.joinToString()}
        
        are unbounded in type alias $talias.
    """.trimIndent()

    fun duplicatedDecl(name: String) = "Declaration $name is already defined or imported."

    fun duplicatedType(name: String) = "Type $name is already defined or imported."

    fun unusedVariables(vars: List<String>): String {
        return if (vars.size == 1) "Variable ${vars[0]} is unused in declaration."
        else """
            Variables
            
                ${vars.joinToString()}
            
            are unused in declaration.
        """.trimIndent()
    }

    fun unusedImport(imp: String): String = "Import $imp is unused in module."

    fun recordMissingLabels(labels: String): String = """
        Record is missing labels:
        
            $labels
    """.trimIndent()

    fun notARow(type: String) = """
        Type
        
            $type
        
        is a not a row type.
    """.trimIndent()

    fun overlappingInstances(type: String) = """
        Found more than one possible instance for type
        
            $type
        
        Make sure there's no overlapping instances in the context
        or pass the argument explicitly.
    """.trimIndent()

    fun noInstanceFound(type: String) = """
        Could not find instance for type
        
            $type
        
        Consider adding a type annotation
        or pass the argument explicitly.
    """.trimIndent()

    fun redundantMatches(pats: List<String>) = """
        A case expression contains redundant cases:
        
            ${pats.joinToString()}
    """.trimIndent()

    fun noAliasFound(alias: String) = "Could not find import alias $alias."

    fun equalsExpected(ctx: String) = "Expected `=` after $ctx."

    fun lparensExpected(ctx: String) = "Expected `(` after $ctx."
    fun rparensExpected(ctx: String) = "Expected `)` after $ctx."

    fun rbracketExpected(ctx: String) = "Expected `}` after $ctx."

    fun rsbracketExpected(ctx: String) = "Expected `]` after $ctx."

    fun pipeExpected(ctx: String) = "Expected `|` after $ctx."

    fun commaExpected(ctx: String) = "Expected `,` after $ctx."
}
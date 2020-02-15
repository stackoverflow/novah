package novah.frontend.typechecker

// primitive types
val primitives = mapOf(
    "Int" to monoCtor("Int"),
    "Long" to monoCtor("Long"),
    "Float" to monoCtor("Float"),
    "Double" to monoCtor("Double"),
    "Boolean" to monoCtor("Boolean"),
    "Char" to monoCtor("Char"),
    "String" to monoCtor("String")
)
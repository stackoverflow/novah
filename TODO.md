# TODO

- Add spans to types
- Support all Java primitives
- Type check type constructors (ex: List Int)
- Create a second set of typed expression AST to simplify codegen
- Make the type checker return typed expressions
- Revamp the error reporting, so it can return json as well as print to the console
- Add warnings to the compiler
- Add javadoc like capabilities to comments (using markdown probably)
- Store numbers as string in the token for formatting
- Type check the whole module __(PARTIALLY DONE)__
- Add span (position) to expressions __(DONE)__
- Make the parser return expressions with spans __(DONE)__
- Type check Do expressions __(DONE)__
- Add comments to the AST, so we can build a formatter __(DONE)__
- Create a canonical formatter for the language __(DONE)__
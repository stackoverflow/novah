# TODO

- [ ] Validate exports are actually valid
- [ ] Add spans to types
- [ ] Support all Java primitives
- [ ] Create a second set of typed expression AST to simplify codegen
- [ ] Make the type checker return typed expressions
- [ ] Revamp the error reporting, so it can return json as well as print to the console
- [ ] Add warnings to the compiler
- [ ] Add javadoc like capabilities to comments (using markdown probably)
- [X] Type check type constructors (ex: List Int)
- [X] Store numbers as string in the token for formatting
- [X] Type check the whole module
- [X] Add span (position) to expressions
- [X] Make the parser return expressions with spans
- [X] Type check Do expressions
- [X] Add comments to the AST, so we can build a formatter
- [X] Create a canonical formatter for the language
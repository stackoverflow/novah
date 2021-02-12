# TODO

- [X] Add warnings to the compiler (still not working properly)
- [ ] Have a context in the typechecker for better error reporting
- [ ] Order declarations by topology taking recursive functions in consideration and allowing mutually recursive
      functions in case they have type annotations (or maybe just use a fix operator?)
- [ ] Real support for operators
- [ ] Some better way to type check mutually recursive functions (fixpoint?)
- [ ] Report that an imported type is private instead of not found
- [ ] Desugar types to classes before codegen (?)
- [ ] Report unused variables
- [ ] Revamp the error reporting, so it can return json as well as print to the console
- [ ] Add primitive autoboxing to code generation
- [ ] Add spans to individual exports with error reporting
- [ ] Add spans to individual imports with error reporting
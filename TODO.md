# TODO

- [ ] Validate type definitions/aliases for wrong kind (checkWellFormed does it only for applications)
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
- [ ] Add primitive autoboxing to code generation (using java.util.function primitive versions)
- [ ] Add spans to individual imports with error reporting
- [ ] Properly handle records in check mode in the typechecker
- [ ] Show types qualified in type check errors
- [ ] Check if instance vars can be fully qualified
- [ ] Primitives to create java arrays
- [ ] Recursive instance search
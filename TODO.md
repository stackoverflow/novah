# TODO

- [ ] Implement a kind checker for types
- [X] Add warnings to the compiler (still not working properly)
- [ ] Order declarations by topology taking recursive functions in consideration and allowing mutually recursive
      functions in case they have type annotations (or maybe just use a fix operator?)
- [ ] Real support for operators
- [ ] Some better way to type check mutually recursive functions (fixpoint?)
- [ ] Report that an imported type is private instead of not found
- [ ] Desugar types to Class<?> before codegen (what to do with user defined types?)
- [ ] Add JSON support to the error reporter
- [ ] Add primitive autoboxing to code generation (using java.util.function primitive versions)
- [ ] Add spans to individual imports with error reporting
- [ ] Properly handle records in check mode in the typechecker (not trivial!)
- [ ] Primitives to create java arrays
- [ ] Recursive instance search
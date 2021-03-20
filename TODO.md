# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Desugar types to Class<?> before codegen (what to do with user defined types?)
- [ ] Add primitive autoboxing to code generation (using java.util.function primitive versions)
- [ ] Add spans to individual imports with error reporting
- [ ] Properly handle records in check mode in the typechecker (not trivial!)
- [ ] Store and report type aliases in types
- [ ] Check unsafeCast works at runtime
- [ ] 0-cost opaque types
- [ ] Syntax for record update/set (probably with type-level strings or macros?)
- [ ] Disallow duplicate labels in pattern matching
- [ ] Report on duplicate top level variables
- [ ] Rename operators and java keywords to be more java-friendly
- [ ] Fix pattern matching warnings (records)
- [ ] Check expression in pattern match is not evaluated 2+
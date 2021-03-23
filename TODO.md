# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Desugar types to Class<?> before codegen (what to do with user defined types?)
- [ ] Add primitive autoboxing to code generation (using java.util.function primitive versions)
- [ ] Add spans to individual imports with error reporting
- [ ] Check unsafeCast works at runtime
- [ ] 0-cost opaque types
- [ ] Syntax for record update (probably with type-level strings or macros?)
- [ ] Disallow duplicate labels in pattern matching
- [ ] Rename operators and java keywords to be more java-friendly
- [ ] Fix pattern matching warnings (records)
- [ ] Implement the novah class loader to load external files
# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Add primitive types to code generation when JEP 401/402 is done
- [ ] Add spans to individual imports with error reporting
- [ ] Check unsafeCast works at runtime
- [ ] 0-cost opaque types
- [ ] Syntax for record update (probably with type-level strings or macros?)
- [ ] Disallow duplicate labels in pattern matching
- [ ] Rename operators and java keywords to be more java-friendly (WIP)
- [ ] Fix pattern matching warnings (records)
- [ ] Implement the novah class loader to load external files
- [ ] Report on unused imports/foreign import types
- [ ] Fix instance search when the instance is not the first parameter of the App
- [ ] Fix double CHECKCAST on native function calls
- [ ] Use ropes instead of char arrays to process strings
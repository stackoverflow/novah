# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Add primitive types to code generation when JEP 401/402 is done
- [ ] 0-cost opaque types
- [ ] Disallow duplicate labels in pattern matching
- [ ] Rename operators and java keywords to be more java-friendly (WIP)
- [ ] Fix pattern matching warnings (records)
- [ ] Implement the novah class loader to load external files
- [ ] TCO
- [ ] Fix double CHECKCAST on native function calls
- [ ] Fix app implicit peeling in inference for cases like {{Type}} -> Return (warn?)
- [ ] Auto complete java method, constructors, etc
- [ ] Have the record label in the context for better error report in case of unify failure
- [ ] Hover over types
- [ ] Better error reporting in case of record missing labels
- [ ] Better error reporting for types: List Int <-> List String will complain that String != Int
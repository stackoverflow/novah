# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Add primitive types to code generation when JEP 401/402 is done
- [ ] 0-cost opaque types
- [ ] Disallow duplicate labels in pattern matching
- [ ] Rename operators and java keywords to be more java-friendly (WIP)
- [ ] Fix pattern matching warnings (records)
- [ ] TCO
- [ ] Fix double CHECKCAST on native function calls
- [ ] Fix app implicit peeling in inference for cases like {{Type}} -> Return (warn?)
- [ ] Auto complete foreign imports
- [ ] Some way to package novah source for distribution
- [ ] Validate operator size at desugar time to allow things like `Mod.:::`
- [ ] Test java field setter
- [ ] Don't create `main` method directly. Create a function and call it
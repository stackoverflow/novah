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
- [ ] Test java field setter
- [ ] Write tests for the core library functions
- [ ] Add String.replace* functions and `count fun`
- [ ] Make unused imports an error
- [ ] Have a `dev mode` for dev use and make all warnings errors in real builds
- [ ] Only recompile files that changed + dependencies
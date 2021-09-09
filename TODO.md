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
- [ ] Write tests for the core library functions
- [ ] Only recompile files that changed + dependencies
- [ ] Auto complete literal list, set in IDE: `[]#size()`
- [ ] Create computation expression for (lazy) async
- [ ] Typechecker cannot correlate a java class' variable with its methods/fields (add to caveats)
- [ ] Some way to package novah source for distribution
- [ ] Syntactic sugar for record update `{ .label -> function | record }`
- [ ] Syntactic sugar for list update `{ .label.[1] = value | record }`
- [ ] Syntactic sugar for set update `{ .label.#{1} = value | record }`

## LSP / IDE

- [ ] Don't auto import functions from same module in LSP
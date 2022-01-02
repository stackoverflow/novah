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
- [ ] Only recompile files that changed + dependencies
- [ ] Auto complete literal list, set in IDE: `[]#size()`
- [ ] Create computation expression for (lazy) async
- [ ] Typechecker cannot correlate a java class' variable with its methods/fields (add to caveats)
- [ ] How to handle `AutoCloseable` interface
- [ ] Add `Ord` requirement to `NumberOps`
- [ ] List patterns warn if used together (a fix the new pattern matching compiler)
- [ ] Add literal regex patterns: `#"\d+ - \w"`
- [ ] Pattern matched lambda parameters with same name as the function don't compile: `foo = [[]] |> List.map (\[foo] -> 4)`
- [ ] No warning on import unqualified function but using it qualified

## LSP / IDE

- [ ] Don't auto import functions from same module in LSP
# TODO

- [ ] Implement a kind checker for types
- [ ] Report that an imported type is private instead of not found
- [ ] Disallow duplicate labels in pattern matching
- [ ] Fix pattern matching warnings (records)
- [ ] Fix app implicit peeling in inference for cases like {{Type}} -> Return (warn?)
- [ ] Auto complete foreign imports
- [ ] Only recompile files that changed + dependencies
- [ ] How to handle `AutoCloseable` interface
- [ ] Add `Ord` requirement to `NumberOps`
- [ ] Pattern matched lambda parameters with same name as the variable don't compile: `foo = [[]] |> List.map (\[foo] -> 4)`
- [ ] Add `raw` attribute to canonical expressions and patterns where needed for showing
- [ ] Recursive type aliases don't proper report errors: `typealias Wrong a = Option (Wrong a)`

## LSP / IDE

- [ ] Don't auto import functions from same module in LSP
- [ ] Auto import doesn't work with wrong case
- [ ] Auto complete literal list, set in IDE: `[]#size()`
# 0.5.0

## Features

- removed `let ... in` syntax
- `in` can be used as an operator, and it will be desugared to `isIn`: ```1 in [1, 2, 3]```
- `!in` can be used as an operator, and it will be desugared to `notIn`: ```0 !in [1, 2, 3]```
- revamped test module, now uses the `test` attribute to mark functions as tests

    ```novah
    // before
    myTest : Unit
    myTest =
      test "My test" \_ ->
        1 `shouleBe` 1
    
    // after
    #[test: "My test"]
    myTest : Unit -> Unit
    myTest () =
      1 `shouleBe` 1
    ```

## Changes

- added tilde `~` to the list of allowed operators
- changed some core function names: `bitAnd` -> `&&&`, `bitOr` -> `|||`, `bitShiftLeft` -> `<<<`, `bitShiftRight` -> `>>>`
- removed `linkedList` module from the stdlib
- renamed `addFirst` to `::` and `addLast` to `+=` in the core module
- added `addFirst` and `addLast` functions to the list module
- added `Pattern` class to the `prim` namespace
- added `contains` and `containsChar` to the string module
- added `padLeft`, `padLeftWith`, `padRight` and `padRightWith` to the string module
- added `.!` function to the core module

## Bug Fixes

- list and set ranges now properly report spans
- computation expressions now properly report spans
- LSP: hover works for primitives and Unit now
- fixed bug in spans of desugared lets
- LSP: Fixed bug in autocompletion of Java fields and methods with an invalid Novah name
- LSP: Fixed bug in autocompletion of static java fields/methods of primitive types
- fixed bug in formatting of some number literals
- fixed bug in formatting of regex patterns
- fixed bug in formatting of try/catch/finally
- fixed bug in type test of primitives

# 0.4.1

## Features

- ability to add foreign imports to the repl

## Changes

- repl properly prints functions and the types of expressions
- repl doesn't differentiate between expressions and definitions anymore
- repl accepts redefinitions of previously defined variables
- index operator now only works for lists
- ! operator now works for strings instead of lists
- added `<primitiveArray>aget` functions
- changed `<-` operator to have the lowest precedence
- added `AutoCloseable` type class and `withOpen` function
- language formatter is usable now
- better indentation rules for binary operators, now they don't need indentation

    ```novah
    // before
    fun x =
      value
        |> function1
        |> function2
    
    // after
    fun x =
      value
      |> function1
      |> function2
    ```

## Bug Fixes

- Fixed bug in list/set parsing where indentation was not ignored

# 0.4.0

## Features

- Added a basic repl to the language (`novah repl`).

# 0.3.1

## Changes

- Native part of stdlib is cached locally for faster copying.

# 0.3.0

## Features

- Added new syntax for literal `BigInteger`s and `BigDecimal`s: `100000N`, `100000.30M`
- Added support for `BigInteger`s and `BigDecimal`s in pattern matching

## Changes

- Added `factorial` function to `Math` module
- Added `toStringList` function to `String` module
- Added `Contained` instance to `Map`
- Added `Show` instance to primitive arrays
- Added `<primitiveArray>FromList` functions to `Array` module
- Added `readLine` function to the `IO` module
- Added range support for `BigInteger` and `BigDecimal`
- Made `^` operator generic and added implementation for `Float64`, `BigInteger` and `BigDecimal`

## Bug Fixes

- Fixed bug in `novah run` command where the standard input was not being redirected

# 0.2.0

## Features

- Added new syntax for foreign methods and fields: obj#?method(...) and obj#-?field
  which will return Option types instead of raw types
- Added @ syntax to deref atoms
- Underscores can be used in numbers now and will be ignored: 1_000_000

## Optimizations

- The Option type is now native and compiles to plain (nullable) java objects, and boxed types in case of primitives

## Changes

- Foreign functions and fields accept Option values now if they are not primitives
- Added := and ::= to reset and swap atoms
- Map keys now require an Equals instance
- Added `novah.bigint` and `novah.bigdecimal` modules to stdlib
- Converted reserved Java names before code generation
- Properly box primitives before casting to non-primitive types
- Removed null from the language (use Option type)
- Added `printlnErr` and `printErr` to the core library
- Tests from `novah.test` will properly report which test failed before printing the error
- Added `withTime` function to core library
- Bumped kotlin to 1.6.21
- Bumped some library versions

## Bug fixes

- Fixed bug in code generation in return of longs and doubles

# 0.1.2

- Fixed unification of records with empty labels
- Fixed record type annotation spreading
- Fixed bug in import parsing
- Fixed bug in auto derive Equals

# 0.1.1

First release
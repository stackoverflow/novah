# 0.3.0

## Features

- Added new syntax for literal big ints and big decimals: `100000N`, `100000.30M`
- Added support for big ints and big decimals in pattern matching

## Changes

- Added factorial function to Math module
- Added toStringList function to String module
- Added Contained instance to Map
- Added Show instance to primitive arrays
- Added <primitiveArray>FromList functions to Array module

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
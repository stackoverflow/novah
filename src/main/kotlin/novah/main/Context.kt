package novah.main

import novah.frontend.matching.Ctor

/**
 * The context containing all the stateful data
 * of a compilation.
 */
class Context {
    val ctorCache = mutableMapOf<String, Ctor>()
}
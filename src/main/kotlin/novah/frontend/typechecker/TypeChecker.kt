package novah.frontend.typechecker

import novah.Polytype

data class Env(val env: HashMap<String, Polytype>) {

    operator fun get(name: String): Polytype? = env[name]

    operator fun set(name: String, type: Polytype) {
        env[name] = type
    }
}

class TypeChecker(val env: Env) {

    fun synthesise(): Unit {}
}
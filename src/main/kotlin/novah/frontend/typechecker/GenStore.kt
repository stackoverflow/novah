package novah.frontend.typechecker

sealed class Name {
    data class Raw(val name: String) : Name() {
        override fun toString(): String = name
    }

    data class Gen(val name: String, val index: Int) : Name() {
        override fun toString(): String = "$name$index"
    }

    fun rawName(): String = when (this) {
        is Raw -> name
        is Gen -> name
    }
}

fun String.raw() = Name.Raw(this)

class GenNameStore {

    private val map = mutableMapOf<String, Int>()

    fun fresh(name: String): Name {
        val i = map.getOrDefault(name, 0)
        map[name] = i + 1
        return Name.Gen(name, i)
    }

    fun fresh(name: Name): Name = when (name) {
        is Name.Raw -> fresh(name.name)
        is Name.Gen -> fresh(name.name)
    }

    fun reset() = map.clear()
}
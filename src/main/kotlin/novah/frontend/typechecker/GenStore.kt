package novah.frontend.typechecker

class GenNameStore {

    private val map = mutableMapOf<String, Int>()

    fun fresh(name: String): String {
        val i = map.getOrDefault(name, 0)
        map[name] = i + 1
        return "$$name$i"
    }

    fun reset() = map.clear()
}
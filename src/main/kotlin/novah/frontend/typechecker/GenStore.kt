package novah.frontend.typechecker

class GenNameStore {

    private val map = mutableMapOf<String, Int>()

    fun fresh(name: String): String {
        val oname = originalName(name)
        val i = map.getOrDefault(oname, 0)
        map[oname] = i + 1
        return "$oname$$i"
    }

    fun reset() = map.clear()

    companion object {
        fun originalName(name: String): String {
            return if (name.contains('$'))
                name.replaceAfter('$', "").dropLast(1)
            else name
        }
    }
}
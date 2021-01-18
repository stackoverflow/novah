package novah.data

// TODO: implement this class
class NovahClassLoader(private val classPath: String) : ClassLoader() {

    private val clparent = getPlatformClassLoader()

    override fun findClass(name: String?): Class<*> {
        return super.findClass(name)
    }

    override fun loadClass(name: String?): Class<*> {
        return clparent.loadClass(name)
    }

    fun safeLoadClass(name: String): Class<*>? {
        return try {
            loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
    }
}
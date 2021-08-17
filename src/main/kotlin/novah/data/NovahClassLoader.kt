/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.data

import novah.Core
import novah.collections.Record
import java.net.URL
import java.net.URLClassLoader
import java.util.*

class NovahClassLoader(classPath: String) : ClassLoader() {

    private val classLoader: URLClassLoader

    init {
        val sep = System.getProperty("path.separator")
        val urls = classPath.split(sep).map(::pathToUrl).toTypedArray()
        classLoader = URLClassLoader("Novah class loader", urls, getPlatformClassLoader())
    }

    override fun findClass(name: String): Class<*> {
        if (name == "novah.Core") return Core::class.java
        if (name == "novah.collections.Record") return Record::class.java
        if (name.startsWith("io.lacuna.bifurcan")) return Class.forName(name)
        return classLoader.loadClass(name)
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return super.loadClass(name, resolve)
    }

    /**
     * Attempts to find a class.
     * Returns null if didn't succeed.
     */
    fun safeFindClass(name: String): Class<*>? {
        when (name) {
            "byte[]" -> return ByteArray::class.java
            "short[]" -> return ShortArray::class.java
            "int[]" -> return IntArray::class.java
            "long[]" -> return LongArray::class.java
            "float[]" -> return FloatArray::class.java
            "double[]" -> return DoubleArray::class.java
            "char[]" -> return CharArray::class.java
            "boolean[]" -> return BooleanArray::class.java
        }
        return try {
            findClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    /**
     * Checks if class `name` is a Throwable.
     * The optional will be empty if it cannot load the class.
     */
    fun isException(name: String): Optional<Boolean> {
        return safeFindClass(name)?.let {
            Optional.of(Throwable::class.java.isAssignableFrom(it))
        } ?: Optional.empty()
    }

    companion object {
        fun pathToUrl(path: String): URL {
            return if (path.endsWith(".jar")) URL("jar:file:$path!")
            else URL("file:$path")
        }
    }
}
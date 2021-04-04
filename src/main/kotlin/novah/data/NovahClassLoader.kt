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
import java.util.*

// TODO: implement this class
class NovahClassLoader(private val classPath: String) : ClassLoader() {

    private val clparent = getPlatformClassLoader()

    override fun findClass(name: String?): Class<*> {
        return super.findClass(name)
    }

    override fun loadClass(name: String): Class<*> {
        if (name == "novah.Core") return Core::class.java
        if (name == "novah.collections.Record") return Record::class.java
        if (name.startsWith("io.lacuna.bifurcan")) return Class.forName(name)
        return clparent.loadClass(name)
    }

    /**
     * Attempts to load a class.
     * Returns null if didn't succeed.
     */
    fun safeLoadClass(name: String): Class<*>? {
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
            loadClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    /**
     * Checks if class `name` is a Throwable.
     * The optional will be empty if cannot load class.
     */
    fun isException(name: String): Optional<Boolean> {
        return safeLoadClass(name)?.let {
            Optional.of(Throwable::class.java.isAssignableFrom(it))
        } ?: Optional.empty()
    }
}
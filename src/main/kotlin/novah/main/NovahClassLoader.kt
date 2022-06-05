/**
 * Copyright 2022 Islon Scherer
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
package novah.main

import novah.Metadata
import novah.Core
import novah.Float32Ref
import novah.Float64Ref
import novah.Int64Ref
import novah.IntRef
import novah.function.Function
import novah.Ref
import novah.collections.ListValue
import novah.collections.Record
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.net.URLClassLoader

class NovahClassLoader(classpath: String?) : ClassLoader() {

    private val classLoader: ClassLoader

    init {
        classLoader = if (classpath != null) {
            val sep = System.getProperty("path.separator")
            val urls = classpath.split(sep).map(Companion::pathToUrl).toTypedArray()
            URLClassLoader("Novah class loader", urls, getPlatformClassLoader())
        } else {
            getPlatformClassLoader()
        }
    }

    override fun findClass(name: String): Class<*> {
        if (name == "novah.Core") return Core::class.java
        if (name == "novah.function.Function") return Function::class.java
        if (name == "novah.Ref") return Ref::class.java
        if (name == "novah.IntRef") return IntRef::class.java
        if (name == "novah.Int64Ref") return Int64Ref::class.java
        if (name == "novah.Float32Ref") return Float32Ref::class.java
        if (name == "novah.Float64Ref") return Float64Ref::class.java
        if (name == "novah.collections.Record") return Record::class.java
        if (name == "novah.collections.ListValue") return ListValue::class.java
        if (name == "novah.Metadata") return Metadata::class.java
        if (name.startsWith("io.lacuna.bifurcan")) return Class.forName(name)
        if (name.startsWith("novah.range.")) return Class.forName(name)
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
            "java.lang.Object[]" -> return Array::class.java
        }
        return try {
            findClass(name)
        } catch (_: ClassNotFoundException) {
            null
        }
    }

    /**
     * Checks if class `name` is a Throwable.
     * Returns false if the class is not found.
     */
    fun isException(name: String): Boolean {
        return safeFindClass(name)?.let {
            Throwable::class.java.isAssignableFrom(it)
        } ?: false
    }

    companion object {
        fun pathToUrl(path: String): URL {
            val f = File(path)

            try {
                return if (path.endsWith(".jar")) URL("jar:file:${f.absolutePath}!/")
                else {
                    val apath = if (f.absolutePath.endsWith("/")) f.absolutePath else "${f.absolutePath}/"
                    URL("file:$apath")
                }
            } catch (m: MalformedURLException) {
                System.err.println("Error while parsing classpath item `$path`")
                throw m
            }
        }
    }
}
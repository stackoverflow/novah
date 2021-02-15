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
package novah

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.zip.ZipInputStream

private class InternalError(msg: String) : RuntimeException(msg)

sealed class ListMatch<out E> {
    object Nil : ListMatch<Nothing>()
    data class HT<E>(val head: E, val tail: List<E>) : ListMatch<E>()
}

object Util {

    /**
     * Represents a compiler bug.
     * Should never happen.
     */
    fun internalError(message: String): Nothing {
        throw InternalError(message)
    }

    fun <E> List<E>.match(): ListMatch<E> {
        val head = firstOrNull()
        return if (head == null) return ListMatch.Nil
        else ListMatch.HT(head, drop(1))
    }

    /**
     * Unsafe for empty lists
     */
    fun <E> List<E>.headTail(): Pair<E, List<E>> = first() to drop(1)

    fun <E> List<E>.prepend(e: E): List<E> = listOf(e) + this

    fun <E> List<E>.hasDuplicates(): Boolean = toSet().size != size

    fun String.splitAt(index: Int): Pair<String, String> {
        return substring(0, index) to substring(index + 1)
    }

    fun <V, R> LinkedList<V>.map(fn: (V) -> R): LinkedList<R> {
        val new = LinkedList<R>()
        forEach { new.add(fn(it)) }
        return new
    }

    fun <V> linkedListOf(vararg vs: V): LinkedList<V> {
        val ll = LinkedList<V>()
        vs.forEach { ll.add(it) }
        return ll
    }

    /**
     * Like [joinToString] but don't append the prefix/suffix if
     * the list is empty.
     */
    fun <T> List<T>.joinToStr(
        separator: CharSequence = ", ",
        prefix: CharSequence = "",
        postfix: CharSequence = "",
        limit: Int = -1,
        truncated: CharSequence = "...",
        transform: ((T) -> CharSequence)? = null
    ): String {
        val pre = if (isEmpty()) "" else prefix
        val pos = if (isEmpty()) "" else postfix
        return joinTo(StringBuilder(), separator, pre, pos, limit, truncated, transform).toString()
    }

    /**
     * Unzips this input stream to the outputDir.
     */
    fun unzip(input: InputStream, outputDir: File) {
        val root = outputDir.normalize();
        input.use { inputSteam ->
            ZipInputStream(inputSteam).use { zis ->
                var entry = zis.nextEntry;
                while (entry != null) {
                    val path = root.resolve(entry.name).normalize();
                    if (!path.startsWith(root)) {
                        throw IOException("Invalid ZIP");
                    }
                    if (entry.isDirectory) {
                        path.mkdirs()
                    } else {
                        path.outputStream().use { os ->
                            val buffer = ByteArray(1024)
                            var len = zis.read(buffer)
                            while (len > 0) {
                                os.write(buffer, 0, len)
                                len = zis.read(buffer)
                            }
                        }
                    }
                    entry = zis.nextEntry;
                }
                zis.closeEntry();
            }
        }
    }
}
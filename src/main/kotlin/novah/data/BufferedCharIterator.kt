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

import java.io.Reader

/**
 * Char iterator backed by a reader.
 */
class BufferedCharIterator(private val reader: Reader) : Iterator<Char> {
    
    private var next: Int = reader.read()
    
    override fun hasNext(): Boolean = next != -1

    override fun next(): Char {
        if (next == -1) throw NoSuchElementException("Reader is already at end.")
        val chr = next.toChar()
        next = reader.read()
        return chr
    }
}
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
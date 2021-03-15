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
package novah.backend

import novah.ast.optimized.Decl
import novah.backend.GenUtil.OBJECT_CLASS
import org.objectweb.asm.ClassWriter

class NovahClassWriter(flags: Int) : ClassWriter(flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            super.getCommonSuperClass(type1, type2)
        } catch (e: Exception) {
            if (type1 == OBJECT_CLASS || type2 == OBJECT_CLASS) return OBJECT_CLASS
            if (superClassCache[type1] == type2) return type2
            if (superClassCache[type2] == type1) return type1
            throw e
        }
    }

    companion object {
        // caches all ctor -> type relations for later
        private val superClassCache = mutableMapOf<String, String>()

        fun addADTs(moduleName: String, adts: List<Decl.TypeDecl>) {
            for (adt in adts) {
                val superClass = "$moduleName/${adt.name}"
                for (ctor in adt.dataCtors) {
                    val ctorName = "$moduleName/${ctor.name}"
                    superClassCache[ctorName] = superClass
                }
            }
        }
    }
}
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

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object Reflection {

    fun novahToJava(type: String) = when (type) {
        "String" -> "java.lang.String"
        "Object" -> "java.lang.Object"
        "Boolean" -> "java.lang.Boolean"
        "Byte" -> "java.lang.Byte"
        "Short" -> "java.lang.Short"
        "Int" -> "java.lang.Integer"
        "Long" -> "java.lang.Long"
        "Float" -> "java.lang.Float"
        "Double" -> "java.lang.Double"
        "Char" -> "java.lang.Character"
        "Array" -> "java.lang.Object[]"
        "ByteArray" -> "byte[]"
        "ShortArray" -> "short[]"
        "IntArray" -> "int[]"
        "LongArray" -> "long[]"
        "FloatArray" -> "float[]"
        "DoubleArray" -> "double[]"
        "CharArray" -> "char[]"
        "BooleanArray" -> "boolean[]"
        "Vector" -> "io.lacuna.bifurcan.List"
        "Set" -> "io.lacuna.bifurcan.Set"
        else -> type
    }
    
    private fun checkParameter(javaType: String, par: String): Boolean = when (javaType) {
        "boolean" -> "java.lang.Boolean" == par
        "byte" -> "java.lang.Byte" == par
        "short" -> "java.lang.Short" == par
        "int" -> "java.lang.Integer" == par
        "long" -> "java.lang.Long" == par
        "float" -> "java.lang.Float" == par
        "double" -> "java.lang.Double" == par
        "char" -> "java.lang.Character" == par
        // better check if this is really safe
        "java.lang.Object" -> true
        else -> {
            if (javaType.endsWith("[]") && javaType.contains(".")) {
                "java.lang.Object[]" == par
            } else javaType == par
        }
    }
    
    fun findMethod(clazz: Class<*>, name: String, pars: List<String>): Method? {
        val novahPars = pars.map { novahToJava(it) }
        for (method in clazz.methods) {
            if (method.name != name) continue
            if (method.parameterCount != novahPars.size) continue
            val allPars = method.parameterTypes.toList().map { it.canonicalName }.zip(novahPars)
            if (allPars.all { checkParameter(it.first, it.second) }) {
                return method
            }
        }
        return null
    }
    
    fun findConstructor(clazz: Class<*>, pars: List<String>): Constructor<*>? {
        val novahPars = pars.map { novahToJava(it) }
        for (ctor in clazz.constructors) {
            if (ctor.parameterCount != novahPars.size) continue
            val allPars = ctor.parameterTypes.toList().map { it.canonicalName }.zip(novahPars)
            if (allPars.all { checkParameter(it.first, it.second) }) {
                return ctor
            }
        }
        return null
    }
    
    fun findField(clazz: Class<*>, name: String): Field? {
        return clazz.fields.find { it.name == name }
    }
    
    fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)
    
    fun isStatic(field: Field): Boolean = Modifier.isStatic(field.modifiers)
    
    fun isImutable(field: Field): Boolean = Modifier.isFinal(field.modifiers)
}
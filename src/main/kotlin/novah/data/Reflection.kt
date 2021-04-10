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
        "Int16" -> "java.lang.Short"
        "Int32" -> "java.lang.Integer"
        "Int64" -> "java.lang.Long"
        "Float32" -> "java.lang.Float"
        "Float64" -> "java.lang.Double"
        "Char" -> "java.lang.Character"
        "Array" -> "java.lang.Object[]"
        "ByteArray" -> "byte[]"
        "Int16Array" -> "short[]"
        "Int32Array" -> "int[]"
        "Int64Array" -> "long[]"
        "Float32Array" -> "float[]"
        "Float64Array" -> "double[]"
        "CharArray" -> "char[]"
        "BooleanArray" -> "boolean[]"
        "Vector" -> "io.lacuna.bifurcan.List"
        "Set" -> "io.lacuna.bifurcan.Set"
        "Function" -> "java.util.function.Function"
        else -> type
    }

    private enum class Match {
        YES, NO, MAYBE
    }

    private fun match(b: Boolean) = if (b) Match.YES else Match.NO

    private fun checkParameter(javaType: String, par: String): Match = when (javaType) {
        "boolean" -> match("java.lang.Boolean" == par)
        "byte" -> match("java.lang.Byte" == par)
        "short" -> match("java.lang.Short" == par)
        "int" -> match("java.lang.Integer" == par)
        "long" -> match("java.lang.Long" == par)
        "float" -> match("java.lang.Float" == par)
        "double" -> match("java.lang.Double" == par)
        "char" -> match("java.lang.Character" == par)
        // better check if this is really safe
        // we leave this to the end after all possibilities are exhausted
        "java.lang.Object" -> Match.MAYBE
        else -> {
            if (javaType.endsWith("[]") && javaType.contains(".")) {
                match("java.lang.Object[]" == par)
            } else match(javaType == par)
        }
    }

    fun findMethod(clazz: Class<*>, name: String, pars: List<String>): Method? {
        var maybe: Method? = null
        val novahPars = pars.map { novahToJava(it) }
        val methods = clazz.methods.filter { it.name == name && it.parameterCount == novahPars.size }
            .sortedBy { it.isBridge }
        for (method in methods) {
            val allPars = method.parameterTypes.toList().map { it.canonicalName }.zip(novahPars)
            val res = allPars.map { checkParameter(it.first, it.second) }
            if (res.all { it == Match.YES }) {
                return method
            }
            if (maybe == null && res.all { it != Match.NO }) maybe = method
        }
        return maybe
    }

    fun findConstructor(clazz: Class<*>, pars: List<String>): Constructor<*>? {
        var maybe: Constructor<*>? = null
        val novahPars = pars.map { novahToJava(it) }
        for (ctor in clazz.constructors) {
            if (ctor.parameterCount != novahPars.size) continue
            val allPars = ctor.parameterTypes.toList().map { it.canonicalName }.zip(novahPars)
            val res = allPars.map { checkParameter(it.first, it.second) }
            if (res.all { it == Match.YES }) {
                return ctor
            }
            if (res.all { it != Match.NO }) maybe = ctor
        }
        return maybe
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        return clazz.fields.find { it.name == name }
    }

    fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)

    fun isStatic(field: Field): Boolean = Modifier.isStatic(field.modifiers)

    fun isImutable(field: Field): Boolean = Modifier.isFinal(field.modifiers)
}
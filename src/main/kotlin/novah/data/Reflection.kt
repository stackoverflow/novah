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

import novah.Util
import novah.frontend.typechecker.*
import novah.frontend.typechecker.Type
import java.lang.reflect.*

object Reflection {

    val typeCache = mutableMapOf<String, Type>()

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
        "List" -> "io.lacuna.bifurcan.List"
        "Set" -> "io.lacuna.bifurcan.Set"
        "Function" -> "java.util.function.Function"
        else -> type
    }

    tailrec fun findJavaType(type: Type): String? = when (type) {
        is TRecord -> "novah.collections.Record"
        is TArrow -> "java.util.function.Function"
        is TConst -> findJavaType(type.name)
        is TApp -> findJavaType(type.type)
        else -> null
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

    fun findStaticMethods(clazz: Class<*>, name: String, argCount: Int): List<Method> {
        return clazz.methods.filter { it.name == name && it.parameterCount == argCount && isStatic(it) && !it.isBridge }
    }

    fun findNonStaticMethods(clazz: Class<*>, name: String, argCount: Int): List<Method> {
        return clazz.methods.filter { it.name == name && it.parameterCount == argCount && !isStatic(it) && !it.isBridge }
    }

    fun findConstructors(clazz: Class<*>, argCount: Int): List<Constructor<*>> {
        return clazz.constructors.filter { it.parameterCount == argCount }
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

    fun collectType(ty: java.lang.reflect.Type): Type {
        if (typeCache.containsKey(ty.typeName)) return typeCache[ty.typeName]!!
        val nty = when (ty) {
            is GenericArrayType -> TApp(TConst(primArray), listOf(collectType(ty.genericComponentType)))
            is TypeVariable<*> -> if (unbounded(ty)) Typechecker.newGenVar() else tObject
            is WildcardType -> {
                if (ty.lowerBounds.isNotEmpty()) tObject
                else if (ty.upperBounds.size == 1 && ty.upperBounds[0] is TypeVariable<*>) {
                    collectType(ty.upperBounds[0])
                } else tObject
            }
            is ParameterizedType -> {
                val arity = ty.actualTypeArguments.size
                val kind = if (arity == 0) Kind.Star else Kind.Constructor(arity)
                if (ty.rawType.typeName == "java.util.function.Function") {
                    TArrow(listOf(collectType(ty.actualTypeArguments[0])), collectType(ty.actualTypeArguments[1]))
                } else {
                    TApp(TConst(javaToNovah(ty.rawType.typeName), kind), ty.actualTypeArguments.map(::collectType))
                }
            }
            is Class<*> -> {
                if (ty.isArray) TApp(TConst(primArray), listOf(collectType(ty.componentType)))
                else {
                    val arity = ty.typeParameters.size
                    if (arity == 0) TConst(javaToNovah(ty.canonicalName))
                    else {
                        val type = TConst(javaToNovah(ty.canonicalName), Kind.Constructor(arity))
                        TApp(type, ty.typeParameters.map(::collectType))
                    }
                }
            }
            else -> Util.internalError("Got unknown subtype from Type: ${ty.javaClass}")
        }
        typeCache[ty.typeName] = nty
        return nty
    }

    private fun unbounded(ty: TypeVariable<*>): Boolean = ty.bounds.all { it.typeName == "java.lang.Object" }
}
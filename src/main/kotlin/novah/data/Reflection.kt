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

    val typeCache = mutableMapOf<java.lang.reflect.Type, Type>()

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

    fun findStaticMethods(clazz: Class<*>, name: String, argCount: Int): List<Method> {
        return clazz.methods
            .sortedBy { it.toString() }
            .filter { it.name == name && it.parameterCount == argCount && isStatic(it) && !it.isBridge }
    }

    fun findNonStaticMethods(clazz: Class<*>, name: String, argCount: Int): List<Method> {
        return clazz.methods
            .sortedBy { it.toString() }
            .filter { it.name == name && it.parameterCount == argCount && !isStatic(it) && !it.isBridge }
    }

    fun findConstructors(clazz: Class<*>, argCount: Int): List<Constructor<*>> {
        return clazz.constructors.sortedBy { it.toString() }.filter { it.parameterCount == argCount }
    }

    fun findField(clazz: Class<*>, name: String): Field? {
        return clazz.fields.find { it.name == name }
    }

    fun isStatic(method: Method): Boolean = Modifier.isStatic(method.modifiers)

    fun isPublic(method: Method): Boolean = Modifier.isPublic(method.modifiers)

    fun isStatic(field: Field): Boolean = Modifier.isStatic(field.modifiers)

    fun isImutable(field: Field): Boolean = Modifier.isFinal(field.modifiers)

    fun isPublic(field: Field): Boolean = Modifier.isPublic(field.modifiers)

    fun isPublic(constructor: Constructor<*>): Boolean = Modifier.isPublic(constructor.modifiers)

    fun collectType(tc: Typechecker, ty: java.lang.reflect.Type, level: Int? = null): Type {
        if (typeCache.containsKey(ty)) return typeCache[ty]!!
        val nty = when (ty) {
            is GenericArrayType -> TApp(TConst(primArray), listOf(collectType(tc, ty.genericComponentType, level)))
            is TypeVariable<*> -> {
                if (unbounded(ty)) {
                    if (level != null) tc.newVar(level)
                    else tc.newGenVar()
                } else tObject
            }
            is WildcardType -> {
                if (ty.lowerBounds.isNotEmpty()) tObject
                else if (ty.upperBounds.size == 1 && ty.upperBounds[0] is TypeVariable<*>) {
                    collectType(tc, ty.upperBounds[0], level)
                } else tObject
            }
            is ParameterizedType -> {
                val arity = ty.actualTypeArguments.size
                val kind = if (arity == 0) Kind.Star else Kind.Constructor(arity)
                if (ty.rawType.typeName == "java.util.function.Function") {
                    val args = listOf(collectType(tc, ty.actualTypeArguments[0], level))
                    TArrow(args, collectType(tc, ty.actualTypeArguments[1], level))
                } else {
                    val ctor = TConst(javaToNovah(ty.rawType.typeName), kind)
                    TApp(ctor, ty.actualTypeArguments.map { collectType(tc, it, level) })
                }
            }
            is Class<*> -> {
                if (ty.isArray) {
                    when (ty.componentType.name) {
                        "byte" -> tByteArray
                        "short" -> tInt16Array
                        "int" -> tInt32Array
                        "long" -> tInt64Array
                        "float" -> tFloat32Array
                        "double" -> tFloat64Array
                        "boolean" -> tBooleanArray
                        "char" -> tCharArray
                        else -> TApp(TConst(primArray), listOf(collectType(tc, ty.componentType, level)))
                    }
                } else {
                    val arity = ty.typeParameters.size
                    if (arity == 0) TConst(javaToNovah(ty.canonicalName))
                    else {
                        val type = TConst(javaToNovah(ty.canonicalName), Kind.Constructor(arity))
                        TApp(type, ty.typeParameters.map { collectType(tc, it, level) })
                    }
                }
            }
            else -> Util.internalError("Got unknown subtype from Type: ${ty.javaClass}")
        }
        typeCache[ty] = nty
        return nty
    }

    private fun unbounded(ty: TypeVariable<*>): Boolean = ty.bounds.all { it.typeName == "java.lang.Object" }
}
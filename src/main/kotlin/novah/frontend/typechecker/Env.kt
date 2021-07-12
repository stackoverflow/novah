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
package novah.frontend.typechecker

import io.lacuna.bifurcan.Map
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Span
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

data class InstanceEnv(val type: Type, val isVar: Boolean = false)

class Env private constructor(
    private val env: Map<String, Type>,
    private val types: Map<String, Type>,
    private val instances: Map<String, InstanceEnv>
) {

    fun extend(name: String, type: Type): Env {
        env.put(name, type)
        return this
    }

    fun lookup(name: String): Type? = env.get(name, null)

    fun remove(name: String) {
        env.remove(name)
    }

    fun fork() = Env(env.forked().linear(), types.forked().linear(), instances.forked().linear())

    fun extendType(name: String, type: Type): Env {
        types.put(name, type)
        return this
    }

    fun lookupType(name: String): Type? = types.get(name, null)

    fun extendInstance(name: String, type: Type, isVar: Boolean = false): Env {
        instances.put(name, InstanceEnv(type, isVar))
        return this
    }

    fun forEachInstance(action: (String, InstanceEnv) -> Unit) {
        instances.forEach { action(it.key(), it.value()) }
    }

    companion object {
        fun new() = Env(Map<String, Type>().linear(), Map.from(primTypes).linear(), Map<String, InstanceEnv>().linear())
    }
}

// all imports that should be automatically added to every module
const val PRIM = "prim"
const val CORE_MODULE = "novah.core"
const val ARRAY_MODULE = "novah.array"
const val JAVA_MODULE = "novah.java"
const val LIST_MODULE = "novah.list"
const val MATH_MODULE = "novah.math"
const val OPTION_MODULE = "novah.option"
const val SET_MODULE = "novah.set"
const val STRING_MODULE = "novah.string"
const val MAP_MODULE = "novah.map"
const val RESULT_MODULE = "novah.result"

const val TEST_MODULE = "novah.test"
const val COMPUTATION_MODULE = "novah.computation"

val primImport = Import.Raw(PRIM, Span.empty(), auto = true)
val coreImport = Import.Raw(CORE_MODULE, Span.empty(), auto = true)
val arrayImport = Import.Raw(ARRAY_MODULE, Span.empty(), "Array", auto = true)
val javaImport = Import.Raw(JAVA_MODULE, Span.empty(), "Java", auto = true)
val listImport = Import.Raw(LIST_MODULE, Span.empty(), "List", auto = true)
val mathImport = Import.Raw(MATH_MODULE, Span.empty(), "Math", auto = true)
val optionImport = Import.Raw(OPTION_MODULE, Span.empty(), "Option", auto = true)
val setImport = Import.Raw(SET_MODULE, Span.empty(), "Set", auto = true)
val stringImport = Import.Raw(STRING_MODULE, Span.empty(), "String", auto = true)
val mapImport = Import.Raw(MAP_MODULE, Span.empty(), "Map", auto = true)
val resultImport = Import.Raw(RESULT_MODULE, Span.empty(), "Result", auto = true)

const val primByte = "$PRIM.Byte"
const val primInt16 = "$PRIM.Int16"
const val primInt32 = "$PRIM.Int32"
const val primInt64 = "$PRIM.Int64"
const val primFloat32 = "$PRIM.Float32"
const val primFloat64 = "$PRIM.Float64"
const val primBoolean = "$PRIM.Boolean"
const val primChar = "$PRIM.Char"
const val primString = "$PRIM.String"
const val primUnit = "$PRIM.Unit"
const val primObject = "$PRIM.Object"
const val primList = "$PRIM.List"
const val primSet = "$PRIM.Set"
const val primArray = "$PRIM.Array"
const val primNullable = "$PRIM.Nullable"

val tByte = TConst(primByte)
val tInt16 = TConst(primInt16)
val tInt32 = TConst(primInt32)
val tInt64 = TConst(primInt64)
val tFloat32 = TConst(primFloat32)
val tFloat64 = TConst(primFloat64)
val tBoolean = TConst(primBoolean)
val tChar = TConst(primChar)
val tString = TConst(primString)
val tObject = TConst(primObject)
val tUnit = TConst(primUnit)
val tList = TApp(TConst(primList, Kind.Constructor(1)), listOf(tbound(-1)))
val tSet = TApp(TConst(primSet, Kind.Constructor(1)), listOf(tbound(-2)))
val tArray = TApp(TConst(primArray, Kind.Constructor(1)), listOf(tbound(-3)))
val tNullable = TApp(TConst(primNullable, Kind.Constructor(1)), listOf(tbound(-4)))

val tUnsafeCoerce = TArrow(listOf(tbound(-4)), tbound(-5))

val primTypes = mapOf(
    primByte to tByte,
    primInt16 to tInt16,
    primInt32 to tInt32,
    primInt64 to tInt64,
    primFloat32 to tFloat32,
    primFloat64 to tFloat64,
    primBoolean to tBoolean,
    primChar to tChar,
    primString to tString,
    primObject to tObject,
    primUnit to tUnit,
    primList to tList,
    primSet to tSet,
    primArray to tArray,
    primNullable to tNullable
)

fun isListOf(type: Type, of: Type) =
    type is TApp && type.type is TConst && type.type.name == primList && type.types[0] == of

fun isSetOf(type: Type, of: Type) =
    type is TApp && type.type is TConst && type.type.name == primSet && type.types[0] == of

fun javaToNovah(jname: String): String = when (jname) {
    "byte" -> primByte
    "short" -> primInt16
    "int" -> primInt32
    "long" -> primInt64
    "float" -> primFloat32
    "double" -> primFloat64
    "char" -> primChar
    "boolean" -> primBoolean
    "java.lang.String" -> primString
    "java.lang.Byte" -> primByte
    "java.lang.Short" -> primInt16
    "java.lang.Integer" -> primInt32
    "java.lang.Long" -> primInt64
    "java.lang.Float" -> primFloat32
    "java.lang.Double" -> primFloat64
    "java.lang.Character" -> primChar
    "java.lang.Boolean" -> primBoolean
    "java.lang.Object" -> primObject
    "io.lacuna.bifurcan.List" -> primList
    "io.lacuna.bifurcan.Set" -> primSet
    else -> {
        if (jname.endsWith("[]")) primArray
        else jname
    }
}

private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())
private fun tbound(x: Id) = TVar(TypeVar.Generic(x))

val primModuleEnv = ModuleEnv(
    mapOf(),
    mapOf(
        "Byte" to tdecl(tByte),
        "Int16" to tdecl(tInt16),
        "Int32" to tdecl(tInt32),
        "Int64" to tdecl(tInt64),
        "Float32" to tdecl(tFloat32),
        "Float64" to tdecl(tFloat64),
        "String" to tdecl(tString),
        "Char" to tdecl(tChar),
        "Boolean" to tdecl(tBoolean),
        "Unit" to tdecl(tUnit),
        "Object" to tdecl(tObject),
        "List" to tdecl(tList),
        "Set" to tdecl(tSet),
        "Array" to tdecl(tArray),
        "Nullable" to tdecl(tNullable)
    )
)
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
import novah.ast.canonical.Decl
import novah.ast.canonical.Module
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Comment
import novah.frontend.Span
import novah.frontend.Spanned
import novah.main.DeclRef
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
const val REGEX_MODULE = "novah.regex"
const val RESULT_MODULE = "novah.result"

const val TEST_MODULE = "novah.test"
const val COMPUTATION_MODULE = "novah.computation"

private val espan = Span.empty()
private fun spanned(name: String) = Spanned(espan, name)

val primImport = Import.Raw(spanned(PRIM), espan, auto = true)
val coreImport = Import.Raw(spanned(CORE_MODULE), espan, auto = true)
val arrayImport = Import.Raw(spanned(ARRAY_MODULE), espan, "Array", auto = true)
val javaImport = Import.Raw(spanned(JAVA_MODULE), espan, "Java", auto = true)
val listImport = Import.Raw(spanned(LIST_MODULE), espan, "List", auto = true)
val mathImport = Import.Raw(spanned(MATH_MODULE), espan, "Math", auto = true)
val optionImport = Import.Raw(spanned(OPTION_MODULE), espan, "Option", auto = true)
val setImport = Import.Raw(spanned(SET_MODULE), espan, "Set", auto = true)
val stringImport = Import.Raw(spanned(STRING_MODULE), espan, "String", auto = true)
val mapImport = Import.Raw(spanned(MAP_MODULE), espan, "Map", auto = true)
val regexImport = Import.Raw(spanned(REGEX_MODULE), espan, "Re", auto = true)
val resultImport = Import.Raw(spanned(RESULT_MODULE), espan, "Result", auto = true)

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
const val primByteArray = "$PRIM.ByteArray"
const val primInt16Array = "$PRIM.Int16Array"
const val primInt32Array = "$PRIM.Int32Array"
const val primInt64Array = "$PRIM.Int64Array"
const val primFloat32Array = "$PRIM.Float32Array"
const val primFloat64Array = "$PRIM.Float64Array"
const val primBooleanArray = "$PRIM.BooleanArray"
const val primCharArray = "$PRIM.CharArray"
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
val tList = tapp(primList, -1)
val tSet = tapp(primSet, -2)
val tArray = tapp(primArray, -3)
val tByteArray = TConst(primByteArray)
val tInt16Array = TConst(primInt16Array)
val tInt32Array = TConst(primInt32Array)
val tInt64Array = TConst(primInt64Array)
val tFloat32Array = TConst(primFloat32Array)
val tFloat64Array = TConst(primFloat64Array)
val tBooleanArray = TConst(primBooleanArray)
val tCharArray = TConst(primCharArray)
val tNullable = tapp(primNullable, -4)

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
    primByteArray to tByteArray,
    primInt16Array to tInt16Array,
    primInt32Array to tInt32Array,
    primInt64Array to tInt64Array,
    primFloat32Array to tFloat32Array,
    primFloat64Array to tFloat64Array,
    primBooleanArray to tBooleanArray,
    primCharArray to tCharArray,
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
    "void" -> primUnit
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
        if (jname.endsWith("[]")) {
            when (jname.substring(0, jname.length - 2)) {
                "byte" -> primByteArray
                "short" -> primInt16
                "int" -> primInt32Array
                "long" -> primInt64Array
                "float" -> primFloat32Array
                "double" -> primFloat64Array
                "boolean" -> primBooleanArray
                "char" -> primCharArray
                else -> primArray
            }
        }
        else jname
    }
}

fun findJavaType(novahType: String) = when (novahType) {
    primByte -> "java.lang.Byte"
    primInt16 -> "java.lang.Short"
    primInt32 -> "java.lang.Integer"
    primInt64 -> "java.lang.Long"
    primFloat32 -> "java.lang.Float"
    primFloat64 -> "java.lang.Double"
    primChar -> "java.lang.Character"
    primString -> "java.lang.String"
    primBoolean -> "java.lang.Boolean"
    primObject -> "java.lang.Object"
    primList -> "io.lacuna.bifurcan.List"
    primSet -> "io.lacuna.bifurcan.Set"
    primArray -> "java.lang.Object[]"
    primByteArray -> "byte[]"
    primInt16Array -> "short[]"
    primInt32Array -> "int[]"
    primInt64Array -> "long[]"
    primFloat32Array -> "float[]"
    primFloat64Array -> "double[]"
    primBooleanArray -> "boolean[]"
    primCharArray -> "char[]"
    else -> novahType
}

private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, false, emptyList(), null)
private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC, false, null)
private fun tbound(x: Id) = TVar(TypeVar.Generic(x))
private fun tapp(name: String, vararg ids: Id): TApp {
    val kind = if (ids.isEmpty()) Kind.Star else Kind.Constructor(ids.size)
    return TApp(TConst(name, kind), ids.toList().map { tbound(it) })
}

val primModuleEnv = ModuleEnv(
    mapOf("<-" to decl(TArrow(listOf(tbound(-10)), TArrow(listOf(tbound(-10)), tUnit)))),
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
        "ByteArray" to tdecl(tByteArray),
        "Int16Array" to tdecl(tInt16Array),
        "Int32Array" to tdecl(tInt32Array),
        "Int64Array" to tdecl(tInt64Array),
        "Float32Array" to tdecl(tFloat32Array),
        "Float64Array" to tdecl(tFloat64Array),
        "BooleanArray" to tdecl(tBooleanArray),
        "CharArray" to tdecl(tCharArray),
        "Nullable" to tdecl(tNullable)
    )
)

val primModule = Module(
    spanned(PRIM),
    PRIM,
    listOf(
        primType("Byte", "A 8 bits integer.\nEquivalent to `java.lang.Byte`."),
        primType("Int16", "A 16 bits integer.\nEquivalent to `java.lang.Short`."),
        primType("Int32", "A 32 bits integer.\nEquivalent to `java.lang.Integer`."),
        primType("Int64", "A 64 bits integer.\nEquivalent to `java.lang.Long`."),
        primType("Float32", "A 32 bits floating point number.\nEquivalent to `java.lang.Float`."),
        primType("Float64", "A 64 bits floating point number.\nEquivalent to `java.lang.Double`."),
        primType("Char", "A UTF-16 character.\nEquivalent to `java.lang.Character`."),
        primType("Boolean", "A value that can be either true or false.\nEquivalent to `java.lang.Boolean`."),
        primType("String", "A String of characters.\nEquivalent to `java.lang.String`."),
        primType(
            "Unit", """Represents the absence of a value.
            |Normally used to represent a function that takes no parameters or returns nothing""".trimMargin()
        ),
        primType("Object", "A platform reference value.\nEquivalent to `java.lang.Object`."),
        primType("Nullable", "Represents a foreign value that can be `null`.\nUsed for Java interop."),
        primType("Array", "A platform array.\nEquivalent to a Java array."),
        primType("ByteArray", "An array of primitive bytes.\nEquivalent to a Java byte array."),
        primType("Int16Array", "An array of primitive int16s.\nEquivalent to a Java short array."),
        primType("Int32Array", "An array of primitive ints.\nEquivalent to a Java int array."),
        primType("Int64Array", "An array of primitive int64s.\nEquivalent to a Java long array."),
        primType("Float32Array", "An array of primitive floats.\nEquivalent to a Java float array."),
        primType("Float64Array", "An array of primitive float64s.\nEquivalent to a Java double array."),
        primType("BooleanArray", "An array of primitive booleans.\nEquivalent to a Java boolean array."),
        primType("CharArray", "An array of primitive chars.\nEquivalent to a Java char array."),
        primType(
            "List", """A persistent, immutable list of values.
            |Can also be used as a vector, stack or queue.
            |Equivalent to `io.lacuna.bifurcan.List`.""".trimMargin()
        ),
        primType("Set", "A persistent, immutable set of values.\nEquivalent to `io.lacuna.bifurcan.Set`."),
    ),
    emptyMap(),
    emptyList(),
    emptyList(),
    Comment("The primitive module contains all primitive defined types.", Span.empty(), true)
)

private fun primType(name: String, comment: String) =
    Decl.TypeDecl(
        Spanned.empty(name),
        emptyList(),
        emptyList(),
        Span.empty(),
        Visibility.PUBLIC,
        false,
        Comment(comment, Span.empty(), true)
    )
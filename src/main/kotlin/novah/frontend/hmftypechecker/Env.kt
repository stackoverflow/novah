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
package novah.frontend.hmftypechecker

import io.lacuna.bifurcan.Map
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Span
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

class Env private constructor(
    private val env: Map<String, Type>,
    private val types: Map<String, Type>,
    private val instances: Map<String, Type>
) {

    fun extend(name: String, type: Type) {
        env.put(name, type)
    }

    fun lookup(name: String): Type? = env.get(name, null)
    
    fun remove(name: String) {
        env.remove(name)
    }

    fun fork() = Env(env.forked().linear(), types.forked().linear(), instances.forked().linear())

    fun extendType(name: String, type: Type) {
        types.put(name, type)
    }

    fun lookupType(name: String): Type? = types.get(name, null)

    fun extendInstance(name: String, type: Type) {
        instances.put(name, type)
    }

    fun lookupInstance(name: String): Type? = instances.get(name, null)

    fun forEachInstance(action: (String, Type) -> Unit) {
        instances.forEach { action(it.key(), it.value()) }
    }

    companion object {
        fun new() = Env(Map<String, Type>().linear(), Map.from(primTypes).linear(), Map<String, Type>().linear())
    }
}

const val PRIM = "prim"

const val primByte = "$PRIM.Byte"
const val primShort = "$PRIM.Short"
const val primInt = "$PRIM.Int"
const val primLong = "$PRIM.Long"
const val primFloat = "$PRIM.Float"
const val primDouble = "$PRIM.Double"
const val primBoolean = "$PRIM.Boolean"
const val primChar = "$PRIM.Char"
const val primString = "$PRIM.String"
const val primUnit = "$PRIM.Unit"
const val primObject = "$PRIM.Object"
const val primVector = "$PRIM.Vector"
const val primSet = "$PRIM.Set"
const val primArray = "$PRIM.Array"
const val primByteArray = "$PRIM.ByteArray"
const val primShortArray = "$PRIM.ShortArray"
const val primIntArray = "$PRIM.IntArray"
const val primLongArray = "$PRIM.LongArray"
const val primFloatArray = "$PRIM.FloatArray"
const val primDoubleArray = "$PRIM.DoubleArray"
const val primBooleanArray = "$PRIM.BooleanArray"
const val primCharArray = "$PRIM.CharArray"

val tByte = TConst(primByte)
val tShort = TConst(primShort)
val tInt = TConst(primInt)
val tLong = TConst(primLong)
val tFloat = TConst(primFloat)
val tDouble = TConst(primDouble)
val tBoolean = TConst(primBoolean)
val tChar = TConst(primChar)
val tString = TConst(primString)
val tObject = TConst(primObject)
val tUnit = TConst(primUnit)
val tVector = TForall(listOf(-10), TApp(TConst(primVector, Kind.Constructor(1)), listOf(tbound(-10))))
val tSet = TForall(listOf(-11), TApp(TConst(primSet, Kind.Constructor(1)), listOf(tbound(-11))))
val tArray = TForall(listOf(-12), TApp(TConst(primArray, Kind.Constructor(1)), listOf(tbound(-12))))
val tByteArray = TConst(primByteArray)
val tShortArray = TConst(primShortArray)
val tIntArray = TConst(primIntArray)
val tLongArray = TConst(primLongArray)
val tFloatArray = TConst(primFloatArray)
val tDoubleArray = TConst(primDoubleArray)
val tBooleanArray = TConst(primBooleanArray)
val tCharArray = TConst(primCharArray)

val primTypes = mapOf(
    primByte to tByte,
    primShort to tShort,
    primInt to tInt,
    primLong to tLong,
    primFloat to tFloat,
    primDouble to tDouble,
    primBoolean to tBoolean,
    primChar to tChar,
    primString to tString,
    primObject to tObject,
    primUnit to tUnit,
    primVector to tVector,
    primSet to tSet,
    primArray to tArray,
    primByteArray to tByteArray,
    primIntArray to tIntArray,
    primLongArray to tLongArray,
    primFloatArray to tFloatArray,
    primDoubleArray to tDoubleArray,
    primBooleanArray to tBooleanArray,
    primCharArray to tCharArray
)

fun javaToNovah(jname: String): String = when (jname) {
    "byte" -> primByte
    "short" -> primShort
    "int" -> primInt
    "long" -> primLong
    "float" -> primFloat
    "double" -> primDouble
    "char" -> primChar
    "boolean" -> primBoolean
    "java.lang.String" -> primString
    "java.lang.Byte" -> primByte
    "java.lang.Short" -> primShort
    "java.lang.Integer" -> primInt
    "java.lang.Long" -> primLong
    "java.lang.Float" -> primFloat
    "java.lang.Double" -> primDouble
    "java.lang.Character" -> primChar
    "java.lang.Boolean" -> primBoolean
    "java.lang.Object" -> primObject
    "io.lacuna.bifurcan.List" -> primVector
    "io.lacuna.bifurcan.Set" -> primSet
    else -> {
        if (jname.endsWith("[]")) {
            when (javaToNovah(jname.replace("[]", ""))) {
                "byte" -> primByteArray
                "short" -> primShortArray
                "int" -> primIntArray
                "long" -> primLongArray
                "float" -> primFloatArray
                "double" -> primDoubleArray
                "char" -> primCharArray
                "boolean" -> primBooleanArray
                else -> primArray
            }
        } else jname
    }
}

val primImport = Import.Raw("prim", Span.empty())

private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC, false)
private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

private fun tfun(a: Type, b: Type) = TArrow(listOf(a), b)
private fun tfall(v: Id, t: Type) = TForall(listOf(v), t)
private fun tbound(x: Id) = TVar(TypeVar.Bound(x))

val primModuleEnv = ModuleEnv(
    mapOf(
        // TODO: fix these negative numbers (probably by moving them to Core)
        "unsafeCast" to decl(TForall(listOf(-1, -2), tfun(tbound(-1), tbound(-2)))),
        "toString" to decl(tfall(-3, tfun(tbound(-3), tString))),
        "hashCode" to decl(tfall(-4, tfun(tbound(-4), tInt))),
        "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "==" to decl(tfall(-5, tfun(tbound(-5), tfun(tbound(-5), tBoolean)))),
        "println" to decl(tfall(-6, tfun(tbound(-6), tUnit))),
    ),
    mapOf(
        "Byte" to tdecl(tByte),
        "Short" to tdecl(tShort),
        "Int" to tdecl(tInt),
        "Long" to tdecl(tLong),
        "Float" to tdecl(tFloat),
        "Double" to tdecl(tDouble),
        "String" to tdecl(tString),
        "Char" to tdecl(tChar),
        "Boolean" to tdecl(tBoolean),
        "Unit" to tdecl(tUnit),
        "Object" to tdecl(tObject),
        "Vector" to tdecl(tVector),
        "Set" to tdecl(tSet),
        "Array" to tdecl(tArray),
        "ByteArray" to tdecl(tByteArray),
        "ShortArray" to tdecl(tShortArray),
        "IntArray" to tdecl(tIntArray),
        "LongArray" to tdecl(tLongArray),
        "FloatArray" to tdecl(tFloatArray),
        "DoubleArray" to tdecl(tDoubleArray),
        "CharArray" to tdecl(tCharArray),
        "BooleanArray" to tdecl(tBooleanArray),
    )
)
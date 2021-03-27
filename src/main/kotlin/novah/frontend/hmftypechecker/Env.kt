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
const val CORE_MODULE = "novah.core"

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
const val primVector = "$PRIM.Vector"
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
val tVector = TForall(listOf(-10), TApp(TConst(primVector, Kind.Constructor(1)), listOf(tbound(-10))))
val tSet = TForall(listOf(-11), TApp(TConst(primSet, Kind.Constructor(1)), listOf(tbound(-11))))
val tArray = TForall(listOf(-12), TApp(TConst(primArray, Kind.Constructor(1)), listOf(tbound(-12))))
val tByteArray = TConst(primByteArray)
val tInt16Array = TConst(primInt16Array)
val tInt32Array = TConst(primInt32Array)
val tInt64Array = TConst(primInt64Array)
val tFloat32Array = TConst(primFloat32Array)
val tFloat64Array = TConst(primFloat64Array)
val tBooleanArray = TConst(primBooleanArray)
val tCharArray = TConst(primCharArray)

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
    primVector to tVector,
    primSet to tSet,
    primArray to tArray,
    primByteArray to tByteArray,
    primInt16Array to tInt16Array,
    primInt32Array to tInt32Array,
    primInt64Array to tInt64Array,
    primFloat32Array to tFloat32Array,
    primFloat64Array to tFloat64Array,
    primBooleanArray to tBooleanArray,
    primCharArray to tCharArray
)

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
    "io.lacuna.bifurcan.List" -> primVector
    "io.lacuna.bifurcan.Set" -> primSet
    else -> {
        if (jname.endsWith("[]")) {
            when (jname.replace("[]", "")) {
                "byte" -> primByteArray
                "short" -> primInt16Array
                "int" -> primInt32Array
                "long" -> primInt64Array
                "float" -> primFloat32Array
                "double" -> primFloat64Array
                "char" -> primCharArray
                "boolean" -> primBooleanArray
                else -> primArray
            }
        } else jname
    }
}

val primImport = Import.Raw(PRIM, Span.empty())
val coreImport = Import.Raw(CORE_MODULE, Span.empty())

private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC, false)
private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

private fun tfun(a: Type, b: Type) = TArrow(listOf(a), b)
private fun tfall(v: Id, t: Type) = TForall(listOf(v), t)
private fun tbound(x: Id) = TVar(TypeVar.Bound(x))

val primModuleEnv = ModuleEnv(
    mapOf(
        // TODO: fix these negative numbers (probably by moving them to Core)
        "unsafeCast" to decl(TForall(listOf(-1, -2), tfun(tbound(-1), tbound(-2)))),
        "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "==" to decl(tfall(-3, tfun(tbound(-3), tfun(tbound(-3), tBoolean))))
    ),
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
        "Vector" to tdecl(tVector),
        "Set" to tdecl(tSet),
        "Array" to tdecl(tArray),
        "ByteArray" to tdecl(tByteArray),
        "Int16Array" to tdecl(tInt16Array),
        "Int32Array" to tdecl(tInt32Array),
        "Int64Array" to tdecl(tInt64Array),
        "Float32Array" to tdecl(tFloat32Array),
        "Float64Array" to tdecl(tFloat64Array),
        "CharArray" to tdecl(tCharArray),
        "BooleanArray" to tdecl(tBooleanArray),
    )
)
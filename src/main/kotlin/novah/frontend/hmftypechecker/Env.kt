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

import io.lacuna.bifurcan.IMap
import io.lacuna.bifurcan.LinearMap
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Span
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

class Env private constructor(private val env: IMap<String, Type>, private val types: IMap<String, Type>) {

    fun extend(name: String, type: Type) {
        env.put(name, type)
    }

    fun lookup(name: String): Type? = env.get(name, null)

    fun fork() = Env(env.forked().linear(), types.forked().linear())

    fun extendType(name: String, type: Type) {
        types.put(name, type)
    }

    fun lookupType(name: String): Type? = types.get(name, null)

    companion object {
        fun new() = Env(LinearMap(), LinearMap.from(primTypes))
    }
}

const val primByte = "prim.Byte"
const val primShort = "prim.Short"
const val primInt = "prim.Int"
const val primLong = "prim.Long"
const val primFloat = "prim.Float"
const val primDouble = "prim.Double"
const val primBoolean = "prim.Boolean"
const val primChar = "prim.Char"
const val primString = "prim.String"
const val primUnit = "prim.Unit"
const val primVector = "prim.Vector"
const val primSet = "prim.Set"

val tByte = TConst(primByte)
val tShort = TConst(primShort)
val tInt = TConst(primInt)
val tLong = TConst(primLong)
val tFloat = TConst(primFloat)
val tDouble = TConst(primDouble)
val tBoolean = TConst(primBoolean)
val tChar = TConst(primChar)
val tString = TConst(primString)
val tUnit = TConst(primUnit)
val tVector = TForall(listOf(-10), TApp(TConst(primVector, Kind.Constructor(1)), listOf(tbound(-10))))
val tSet = TForall(listOf(-11), TApp(TConst(primSet, Kind.Constructor(1)), listOf(tbound(-11))))

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
    primUnit to tUnit,
    primVector to tVector,
    primSet to tSet
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
    else -> jname
}

val primImport = Import.Raw("prim", Span.empty())

private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC)
private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

private fun tfun(a: Type, b: Type) = TArrow(listOf(a), b)
private fun tfall(v: Id, t: Type) = TForall(listOf(v), t)
private fun tbound(x: Id) = TVar(TypeVar.Bound(x))

val primModuleEnv = ModuleEnv(
    mapOf(
        "println" to decl(tfun(tString, tUnit)),
        // TODO: fix these negative numbers (probably by moving them to the stblib)
        "unsafeCast" to decl(TForall(listOf(-1, -2), tfun(tbound(-1), tbound(-2)))),
        "toString" to decl(tfall(-3, tfun(tbound(-3), tString))),
        "hashCode" to decl(tfall(-4, tfun(tbound(-4), tInt))),
        "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "==" to decl(tfall(-5, tfun(tbound(-5), tfun(tbound(-5), tBoolean))))
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
        "Vector" to tdecl(tVector),
        "Set" to tdecl(tSet)
    )
)
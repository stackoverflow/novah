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

import novah.ast.source.Visibility
import novah.ast.source.Import
import novah.frontend.Span
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

/**
 * Primitive types and functions that should be always
 * in the context
 */
object Prim {

    const val PRIM = "prim"

    const val byte = "$PRIM.Byte"
    const val short = "$PRIM.Short"
    const val int = "$PRIM.Int"
    const val long = "$PRIM.Long"
    const val float = "$PRIM.Float"
    const val double = "$PRIM.Double"
    const val string = "$PRIM.String"
    const val char = "$PRIM.Char"
    const val boolean = "$PRIM.Boolean"
    const val unit = "$PRIM.Unit"
    const val vector = "$PRIM.Vector"
    const val set = "$PRIM.Set"

    // primitives
    val tByte = tconst(byte)
    val tShort = tconst(short)
    val tInt = tconst(int)
    val tLong = tconst(long)
    val tFloat = tconst(float)
    val tDouble = tconst(double)
    val tString = tconst(string)
    val tChar = tconst(char)
    val tBoolean = tconst(boolean)

    val tUnit = tconst(unit)
    val tVector = tfall("a", TApp(TConst(vector), TVar("a")))
    val tSet = tfall("a", TApp(TConst(set), TVar("a")))

    fun javaToNovah(jname: String): String = when (jname) {
        "byte" -> byte
        "short" -> short
        "int" -> int
        "long" -> long
        "float" -> float
        "double" -> double
        "char" -> char
        "boolean" -> boolean
        "java.lang.String" -> string
        "java.lang.Byte" -> byte
        "java.lang.Short" -> short
        "java.lang.Integer" -> int
        "java.lang.Long" -> long
        "java.lang.Float" -> float
        "java.lang.Double" -> double
        "java.lang.Character" -> char
        else -> jname
    }

    /**
     * All primitive types and functions
     * that should be auto-added to every
     * module.
     */
    val primImport = Import.Raw(PRIM, Span.empty())

    private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC)
    private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

    val moduleEnv = ModuleEnv(
        mapOf(
            "println" to decl(tfun(tString, tUnit)),
            "toString" to decl(tfall("a", tfun(TVar("a"), tString))),
            "hashCode" to decl(tfall("a", tfun(TVar("a"), tInt))),
            "unsafeCast" to decl(tfall("a", tfall("b", tfun(TVar("a"), TVar("b"))))),
            "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
            "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
            "==" to decl(tfall("a", tfun(TVar("a"), tfun(TVar("a"), tBoolean))))
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

    private fun tconst(x: String) = TConst(x)
    private fun tfun(a: Type, b: Type) = TArrow(a, b)
    private fun tfall(v: String, t: Type) = TForall(v, t)
}
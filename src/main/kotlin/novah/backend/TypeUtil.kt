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
package novah.backend

import novah.ast.optimized.Type
import novah.backend.GenUtil.OBJECT_CLASS

object TypeUtil {

    const val BYTE_CLASS = "java/lang/Byte"
    const val SHORT_CLASS = "java/lang/Short"
    const val INTEGER_CLASS = "java/lang/Integer"
    const val LONG_CLASS = "java/lang/Long"
    const val FLOAT_CLASS = "java/lang/Float"
    const val DOUBLE_CLASS = "java/lang/Double"
    const val CHAR_CLASS = "java/lang/Character"
    const val BOOL_CLASS = "java/lang/Boolean"

    const val STRING_CLASS = "java/lang/String"
    const val RECORD_CLASS = "novah/collections/Record"
    const val VECTOR_CLASS = "io/lacuna/bifurcan/List"
    const val SET_CLASS = "io/lacuna/bifurcan/Set"

    private const val FUNCTION_CLASS = "java/util/function/Function"
    const val FUNCTION_TYPE = "Ljava/util/function/Function;"
    const val OBJECT_TYPE = "Ljava/lang/Object;"
    const val STRING_TYPE = "Ljava/lang/String;"
    const val RECORD_TYPE = "Lnovah/collections/Record;"
    const val VECTOR_TYPE = "Lio/lacuna/bifurcan/List;"
    const val SET_TYPE = "Lio/lacuna/bifurcan/Set;"

    fun toInternalClass(type: Type): String = when (type) {
        is Type.TVar -> if (type.isForall) OBJECT_CLASS else type.name
        is Type.TConstructor -> {
            if (type.name == "Array") "[" + toInternalClass(type.types[0])
            else type.name
        }
        is Type.TFun -> FUNCTION_CLASS
    }

    fun toInternalType(type: Type): String = when (type) {
        is Type.TVar -> if (type.isForall) OBJECT_TYPE else descriptor(type.name)
        is Type.TConstructor -> {
            if (type.name == "Array") "[" + toInternalType(type.types[0])
            else descriptor(type.name)
        }
        is Type.TFun -> FUNCTION_TYPE
    }

    fun toInternalMethodType(ret: Type, args: List<Type>): String {
        return args.joinToString("", prefix = "(", postfix = ")") { toInternalType(it) } + toInternalType(ret)
    }

    fun toInternalMethodType(tfun: Type.TFun): String {
        return "(${toInternalType(tfun.arg)})${toInternalType(tfun.ret)}"
    }

    fun buildClassSignature(tyVars: List<String>, superClass: String = OBJECT_CLASS): String? {
        if (tyVars.isEmpty()) return null

        val tvarStr = tyVars.joinToString("", prefix = "<", postfix = ">") {
            it.toUpperCase() + ":$OBJECT_TYPE"
        }

        val superVars = if (superClass != OBJECT_CLASS) {
            tyVars.joinToString("", prefix = "<", postfix = ">") {
                "T${it.toUpperCase()};"
            }
        } else ""

        return "${tvarStr}L$superClass$superVars;"
    }

    fun maybeBuildFieldSignature(type: Type): String? {
        return if (!type.isGeneric()) null
        else if (type is Type.TConstructor && type.name == "Array") null
        else buildFieldSignature(type)
    }

    fun buildFieldSignature(type: Type): String = when (type) {
        is Type.TVar -> if (type.isForall) "T${type.name};" else descriptor(type.name)
        is Type.TFun -> "Ljava/util/function/Function<" +
                buildFieldSignature(type.arg) +
                buildFieldSignature(type.ret) + ">;"
        is Type.TConstructor -> {
            if (type.types.isEmpty()) descriptor(type.name)
            else "L${type.name}<" + type.types.joinToString("") { buildFieldSignature(it) } + ">;"
        }
    }

    fun buildMethodSignature(ret: Type, args: List<Type>): String? {
        return if ((args + ret).any { it.isGeneric() }) {
            args.joinToString("", prefix = "(", postfix = ")") { buildFieldSignature(it) } + buildFieldSignature(ret)
        } else null
    }

    fun descriptor(t: String): String = "L$t;"

    fun buildFunctions(types: List<Type>): Type {
        val first = types.first()
        return if (types.size == 1) first
        else Type.TFun(first, buildFunctions(types.drop(1)))
    }
}
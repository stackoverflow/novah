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

import novah.backend.GenUtil.OBJECT_CLASS
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import java.util.*

object TypeUtil {

    const val BYTE_CLASS = "java/lang/Byte"
    const val SHORT_CLASS = "java/lang/Short"
    const val INTEGER_CLASS = "java/lang/Integer"
    const val LONG_CLASS = "java/lang/Long"
    const val FLOAT_CLASS = "java/lang/Float"
    const val DOUBLE_CLASS = "java/lang/Double"
    const val CHAR_CLASS = "java/lang/Character"
    const val BOOL_CLASS = "java/lang/Boolean"

    const val RECORD_CLASS = "novah/collections/Record"
    const val LIST_CLASS = "io/lacuna/bifurcan/List"
    const val SET_CLASS = "io/lacuna/bifurcan/Set"
    const val FUNCTION_CLASS = "java/util/function/Function"

    const val FUNCTION_DESC = "Ljava/util/function/Function;"
    const val OBJECT_DESC = "Ljava/lang/Object;"
    const val STRING_DESC = "Ljava/lang/String;"
    const val RECORD_DESC = "Lnovah/collections/Record;"
    const val LIST_DESC = "Lio/lacuna/bifurcan/List;"
    const val SET_DESC = "Lio/lacuna/bifurcan/Set;"

    fun buildClassSignature(tyVars: List<String>, superClass: String = OBJECT_CLASS): String? {
        if (tyVars.isEmpty()) return null

        val tvarStr = tyVars.joinToString("", prefix = "<", postfix = ">") {
            it.uppercase(Locale.getDefault()) + ":$OBJECT_DESC"
        }

        val superVars = if (superClass != OBJECT_CLASS) {
            tyVars.joinToString("", prefix = "<", postfix = ">") {
                "T${it.toUpperCase()};"
            }
        } else ""

        return "${tvarStr}L$superClass$superVars;"
    }

    fun descriptor(t: String): String = "L$t;"

    /**
     * Box a primitive type
     */
    fun box(ty: Type, mv: MethodVisitor) {
        val wrapper = primitiveWrappers[ty.className]
        val desc = getMethodDescriptor(getType(wrapper), ty)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, getInternalName(wrapper), "valueOf", desc, false)
    }

    /**
     * Unbox a primitive type
     */
    fun unbox(ty: Type, mv: MethodVisitor) {
        val name = ty.className
        val internal = getInternalName(primitiveWrappers[name])
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, "${name}Value", "()" + ty.descriptor, false)
    }
    
    fun Type.primitive(): Type? = when (this.className) {
        "java.lang.Byte" -> BYTE_TYPE
        "java.lang.Short" -> SHORT_TYPE
        "java.lang.Integer" -> INT_TYPE
        "java.lang.Long" -> LONG_TYPE
        "java.lang.Float" -> FLOAT_TYPE
        "java.lang.Double" -> DOUBLE_TYPE
        "java.lang.Boolean" -> BOOLEAN_TYPE
        "java.lang.Character" -> CHAR_TYPE
        else -> null
    }

    val lambdaMethodType: Type = getMethodType("(Ljava/lang/Object;)Ljava/lang/Object;")
    
    private val primitiveWrappers = mutableMapOf(
        "byte" to Byte::class.javaObjectType,
        "short" to Short::class.javaObjectType,
        "int" to Int::class.javaObjectType,
        "long" to Long::class.javaObjectType,
        "float" to Float::class.javaObjectType,
        "double" to Double::class.javaObjectType,
        "char" to Char::class.javaObjectType,
        "boolean" to Boolean::class.javaObjectType
    )
}
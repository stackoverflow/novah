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
import novah.function.*
import novah.function.Function
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.Type.*
import java.util.*

object TypeUtil {

    const val RECORD_CLASS = "novah/collections/Record"
    const val LIST_CLASS = "io/lacuna/bifurcan/List"
    const val SET_CLASS = "io/lacuna/bifurcan/Set"
    const val FUNCTION_CLASS = "novah/function/Function"
    const val UNIT_CLASS = "novah/Unit"
    const val JAVA_RECORD_CLASS = "java/lang/Record"

    const val FUNCTION_DESC = "Lnovah/function/Function;"
    const val OBJECT_DESC = "Ljava/lang/Object;"
    const val STRING_DESC = "Ljava/lang/String;"
    const val RECORD_DESC = "Lnovah/collections/Record;"
    const val LIST_DESC = "Lio/lacuna/bifurcan/List;"
    const val SET_DESC = "Lio/lacuna/bifurcan/Set;"

    val objectType: Type = getType(java.lang.Object::class.java)

    fun buildClassSignature(tyVars: List<String>, superClass: String = OBJECT_CLASS): String? {
        if (tyVars.isEmpty()) return null

        val tvarStr = tyVars.joinToString("", prefix = "<", postfix = ">") {
            it.uppercase(Locale.getDefault()) + ":$OBJECT_DESC"
        }

        val superVars = if (superClass != OBJECT_CLASS) {
            tyVars.joinToString("", prefix = "<", postfix = ">") {
                "T${it.uppercase()};"
            }
        } else ""

        return "${tvarStr}L$superClass$superVars;"
    }

    fun descriptor(t: String): String = "L$t;"

    /**
     * Box a primitive type
     */
    fun box(ty: Type, mv: MethodVisitor) {
        val wrapper = primitiveWrappers[ty.sort]
        val desc = getMethodDescriptor(getType(wrapper), ty)
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, getInternalName(wrapper), "valueOf", desc, false)
    }

    /**
     * Unbox a primitive type
     */
    fun unbox(ty: Type, mv: MethodVisitor) {
        val name = ty.className
        val internal = getInternalName(primitiveWrappers[ty.sort])
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internal, "${name}Value", "()" + ty.descriptor, false)
    }

    fun lambdaMethodName(arg: Type, ret: Type): String {
        val asort = arg.sort
        val rsort = ret.sort
        return when {
            rsort == 1 -> "applyBoolean"
            rsort == 2 -> "applyChar"
            rsort == 3 -> "applyByte"
            rsort == 4 -> "applyShort"
            rsort == 5 -> "applyInt"
            rsort == 6 -> "applyFloat"
            rsort == 7 -> "applyLong"
            rsort == 8 -> "applyDouble"
            asort == 1 -> "applyZ"
            asort == 2 -> "applyC"
            asort == 3 -> "applyB"
            asort == 4 -> "applyS"
            asort == 5 -> "applyI"
            asort == 6 -> "applyF"
            asort == 7 -> "applyJ"
            asort == 8 -> "applyD"
            else -> "apply"
        }
    }

    fun lambdaMethodDesc(arg: Type, ret: Type): String {
        val argT = if (arg.isPrimitive()) arg.descriptor else OBJECT_DESC
        val retT = if (ret.isPrimitive()) ret.descriptor else OBJECT_DESC
        return "($argT)$retT"
    }

    fun Type.isPrimitive(): Boolean = sort in 1..8

    fun Type.isDouble(): Boolean = sort == 8
    fun Type.isLong(): Boolean = sort == 7

    fun lambdaType(arg: Type, ret: Type): Type {
        val asort = arg.sort
        val rsort = ret.sort
        if (arg.isPrimitive() && ret.isPrimitive()) return primitiveLambdaTypes["$asort:$rsort"]!!
        if (arg.isPrimitive()) return objectReturningLambdas[asort]!!
        if (ret.isPrimitive()) return objectArgLambdas[rsort]!!
        return functionType
    }

    fun Type.isWrapper(): Boolean = descriptor in wrapperNames

    /**
     * Returns the wrapper type for this primitive (if it's a primitive).
     */
    fun Type.wrapper(): Type = primitiveWrapperTypes[sort] ?: this

    private val primitiveWrapperTypes = mapOf(
        1 to getType(Boolean::class.javaObjectType),
        2 to getType(Char::class.javaObjectType),
        3 to getType(Byte::class.javaObjectType),
        4 to getType(Short::class.javaObjectType),
        5 to getType(Int::class.javaObjectType),
        6 to getType(Float::class.javaObjectType),
        7 to getType(Long::class.javaObjectType),
        8 to getType(Double::class.javaObjectType),
    )

    private val wrapperNames = primitiveWrapperTypes.values.map { it.descriptor }.toSet()

    private val primitiveWrappers = mapOf(
        1 to Boolean::class.javaObjectType,
        2 to Char::class.javaObjectType,
        3 to Byte::class.javaObjectType,
        4 to Short::class.javaObjectType,
        5 to Int::class.javaObjectType,
        6 to Float::class.javaObjectType,
        7 to Long::class.javaObjectType,
        8 to Double::class.javaObjectType,
    )

    private val functionType = getType(Function::class.java)

    private val objectReturningLambdas = mapOf(
        1 to getType(FunctionBooleanObject::class.java),
        2 to getType(FunctionCharObject::class.java),
        3 to getType(FunctionByteObject::class.java),
        4 to getType(FunctionShortObject::class.java),
        5 to getType(FunctionIntObject::class.java),
        6 to getType(FunctionFloatObject::class.java),
        7 to getType(FunctionLongObject::class.java),
        8 to getType(FunctionDoubleObject::class.java)
    )

    private val objectArgLambdas = mapOf(
        1 to getType(FunctionObjectBoolean::class.java),
        2 to getType(FunctionObjectChar::class.java),
        3 to getType(FunctionObjectByte::class.java),
        4 to getType(FunctionObjectShort::class.java),
        5 to getType(FunctionObjectInt::class.java),
        6 to getType(FunctionObjectFloat::class.java),
        7 to getType(FunctionObjectLong::class.java),
        8 to getType(FunctionObjectDouble::class.java)
    )

    private val primitiveLambdaTypes = mapOf(
        "1:1" to getType(FunctionBooleanBoolean::class.java),
        "1:2" to getType(FunctionBooleanChar::class.java),
        "1:3" to getType(FunctionBooleanByte::class.java),
        "1:4" to getType(FunctionBooleanShort::class.java),
        "1:5" to getType(FunctionBooleanInt::class.java),
        "1:6" to getType(FunctionBooleanFloat::class.java),
        "1:7" to getType(FunctionBooleanLong::class.java),
        "1:8" to getType(FunctionBooleanDouble::class.java),
        "2:1" to getType(FunctionCharBoolean::class.java),
        "2:2" to getType(FunctionCharChar::class.java),
        "2:3" to getType(FunctionCharByte::class.java),
        "2:4" to getType(FunctionCharShort::class.java),
        "2:5" to getType(FunctionCharInt::class.java),
        "2:6" to getType(FunctionCharFloat::class.java),
        "2:7" to getType(FunctionCharLong::class.java),
        "2:8" to getType(FunctionCharDouble::class.java),
        "3:1" to getType(FunctionByteBoolean::class.java),
        "3:2" to getType(FunctionByteChar::class.java),
        "3:3" to getType(FunctionByteByte::class.java),
        "3:4" to getType(FunctionByteShort::class.java),
        "3:5" to getType(FunctionByteInt::class.java),
        "3:6" to getType(FunctionByteFloat::class.java),
        "3:7" to getType(FunctionByteLong::class.java),
        "3:8" to getType(FunctionByteDouble::class.java),
        "4:1" to getType(FunctionShortBoolean::class.java),
        "4:2" to getType(FunctionShortChar::class.java),
        "4:3" to getType(FunctionShortByte::class.java),
        "4:4" to getType(FunctionShortShort::class.java),
        "4:5" to getType(FunctionShortInt::class.java),
        "4:6" to getType(FunctionShortFloat::class.java),
        "4:7" to getType(FunctionShortLong::class.java),
        "4:8" to getType(FunctionShortDouble::class.java),
        "5:1" to getType(FunctionIntBoolean::class.java),
        "5:2" to getType(FunctionIntChar::class.java),
        "5:3" to getType(FunctionIntByte::class.java),
        "5:4" to getType(FunctionIntShort::class.java),
        "5:5" to getType(FunctionIntInt::class.java),
        "5:6" to getType(FunctionIntFloat::class.java),
        "5:7" to getType(FunctionIntLong::class.java),
        "5:8" to getType(FunctionIntDouble::class.java),
        "6:1" to getType(FunctionFloatBoolean::class.java),
        "6:2" to getType(FunctionFloatChar::class.java),
        "6:3" to getType(FunctionFloatByte::class.java),
        "6:4" to getType(FunctionFloatShort::class.java),
        "6:5" to getType(FunctionFloatInt::class.java),
        "6:6" to getType(FunctionFloatFloat::class.java),
        "6:7" to getType(FunctionFloatLong::class.java),
        "6:8" to getType(FunctionFloatDouble::class.java),
        "7:1" to getType(FunctionLongBoolean::class.java),
        "7:2" to getType(FunctionLongChar::class.java),
        "7:3" to getType(FunctionLongByte::class.java),
        "7:4" to getType(FunctionLongShort::class.java),
        "7:5" to getType(FunctionLongInt::class.java),
        "7:6" to getType(FunctionLongFloat::class.java),
        "7:7" to getType(FunctionLongLong::class.java),
        "7:8" to getType(FunctionLongDouble::class.java),
        "8:1" to getType(FunctionDoubleBoolean::class.java),
        "8:2" to getType(FunctionDoubleChar::class.java),
        "8:3" to getType(FunctionDoubleByte::class.java),
        "8:4" to getType(FunctionDoubleShort::class.java),
        "8:5" to getType(FunctionDoubleInt::class.java),
        "8:6" to getType(FunctionDoubleFloat::class.java),
        "8:7" to getType(FunctionDoubleLong::class.java),
        "8:8" to getType(FunctionDoubleDouble::class.java)
    )
}
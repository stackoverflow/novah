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

    const val FUNCTION_TYPE = "Ljava/util/function/Function;"
    private const val OBJECT_TYPE = "Ljava/lang/Object;"

    fun toInternalType(type: Type): String = when (type) {
        is Type.TVar -> if (type.isForall) OBJECT_TYPE else descriptor(type.name)
        is Type.TConstructor -> {
            if (type.name == "JArray") "[" + toInternalType(type.types[0])
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
        else if (type is Type.TConstructor && type.name == "JArray") null
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
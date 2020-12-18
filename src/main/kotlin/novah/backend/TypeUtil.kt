package novah.backend

import novah.ast.optimized.Type
import novah.backend.GenUtil.OBJECT_CLASS

object TypeUtil {

    private const val OBJECT_TYPE = "Ljava/lang/Object;"
    private const val FUNCTION_TYPE = "Ljava/util/function/Function;"

    fun toInternalType(type: Type): String = when (type) {
        is Type.TVar -> if (type.isForall) OBJECT_TYPE else descriptor(type.name)
        is Type.TConstructor -> descriptor(type.name)
        is Type.TFun -> FUNCTION_TYPE
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
        return if (type is Type.TVar && !type.isForall) null
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

    fun descriptor(t: String): String = "L$t;"
}
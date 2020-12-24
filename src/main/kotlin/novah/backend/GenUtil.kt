package novah.backend

import novah.ast.canonical.Visibility
import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.optimized.Type
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

object GenUtil {

    const val OBJECT_CLASS = "java/lang/Object"
    const val INIT = "<init>"
    const val STATIC_INIT = "<clinit>"
    const val INSTANCE = "INSTANCE"

    const val NOVAH_GENCLASS_VERSION = Opcodes.V1_8

    fun visibility(decl: Decl.DataDecl): Int =
        if (decl.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else 0

    fun visibility(ctor: DataConstructor): Int =
        if (ctor.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else 0

    fun visibility(decl: Decl.ValDecl): Int =
        if (decl.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE
}

class GenContext {

    private var localsCount = 0

    private val locals = mutableMapOf<String, InnerLocal>()

    fun nextLocal(): Int = localsCount++

    operator fun get(v: String): Int? = locals[v]?.num

    fun put(name: String, num: Int, startLabel: Label) {
        locals[name] = InnerLocal(num, startLabel)
    }

    fun putParameter(name: String, type: Type, startLabel: Label) {
        val num = localsCount++
        locals[name] = InnerLocal(num, startLabel, localVar = Expr.LocalVar(name, type))
    }

    fun setLocalVar(lv: Expr.LocalVar) {
        val local = locals[lv.name] ?: return
        local.localVar = lv
    }

    fun setEndLabel(name: String, endLabel: Label) {
        val local = locals[name] ?: return
        local.endLabel = endLabel
    }

    fun visitLocalVariables(mv: MethodVisitor) {
        locals.forEach { (_, l) ->
            if (l.endLabel != null && l.localVar != null) {
                val lv = l.localVar!!
                mv.visitLocalVariable(
                    lv.name,
                    TypeUtil.toInternalType(lv.type),
                    TypeUtil.maybeBuildFieldSignature(lv.type),
                    l.startLabel,
                    l.endLabel,
                    l.num
                )
            }
        }
    }

    private data class InnerLocal(
        val num: Int,
        val startLabel: Label,
        var endLabel: Label? = null,
        var localVar: Expr.LocalVar? = null
    )
}
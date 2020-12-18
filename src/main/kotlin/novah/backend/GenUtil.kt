package novah.backend

import novah.ast.canonical.Visibility
import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
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
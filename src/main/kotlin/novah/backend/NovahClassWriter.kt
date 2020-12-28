package novah.backend

import novah.backend.GenUtil.OBJECT_CLASS
import org.objectweb.asm.ClassWriter

class NovahClassWriter(flags: Int) : ClassWriter(flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            super.getCommonSuperClass(type1, type2)
        } catch (e: Exception) {
            if (type1 == OBJECT_CLASS || type2 == OBJECT_CLASS) return OBJECT_CLASS
            throw e
        }
    }
}
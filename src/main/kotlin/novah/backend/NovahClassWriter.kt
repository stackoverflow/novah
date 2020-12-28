package novah.backend

import novah.ast.optimized.Decl
import novah.backend.GenUtil.OBJECT_CLASS
import org.objectweb.asm.ClassWriter

class NovahClassWriter(flags: Int) : ClassWriter(flags) {
    override fun getCommonSuperClass(type1: String, type2: String): String {
        return try {
            super.getCommonSuperClass(type1, type2)
        } catch (e: Exception) {
            if (type1 == OBJECT_CLASS || type2 == OBJECT_CLASS) return OBJECT_CLASS
            if (superClassCache[type1] == type2) return type2
            if (superClassCache[type2] == type1) return type1
            throw e
        }
    }

    companion object {
        // caches all ctor -> type relations for later
        private val superClassCache = mutableMapOf<String, String>()

        fun addADTs(moduleName: String, adts: List<Decl.DataDecl>) {
            for (adt in adts) {
                val superClass = "$moduleName/${adt.name}"
                for (ctor in adt.dataCtors) {
                    val ctorName = "$moduleName/${ctor.name}"
                    superClassCache[ctorName] = superClass
                }
            }
        }
    }
}
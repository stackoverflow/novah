package novah.backend

import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
import novah.ast.optimized.Module
import novah.backend.GenUtil.INIT
import novah.backend.GenUtil.INSTANCE
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.buildFieldSignature
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*

/**
 * Generate bytecode for Algebraic Data Types.
 * Each ADT and constructor is a separate class
 */
class ADTGen(
    private val adt: Decl.DataDecl,
    private val ast: Module,
    private val onGenClass: (String, String, ByteArray) -> Unit
) {

    private val moduleName = ast.name
    private val adtClassName = "$moduleName/${adt.name}"

    fun run() {
        genType()
        for (ctor in adt.dataCtors) {
            genConstructor(ctor)
        }
    }

    private fun genType() {
        val sig = buildClassSignature(adt.tyVars)
        val vis = visibility(adt)

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            NOVAH_GENCLASS_VERSION,
            vis + ACC_ABSTRACT,
            adtClassName,
            sig,
            OBJECT_CLASS,
            arrayOf<String>()
        )
        cw.visitSource(ast.sourceName, null)

        genEmptyConstructor(cw, ACC_PRIVATE)
        cw.visitEnd()
        onGenClass(moduleName, adt.name, cw.toByteArray())
    }

    private fun genConstructor(ctor: DataConstructor) {
        val className = "$moduleName/${ctor.name}"
        val sig = buildClassSignature(adt.tyVars, adtClassName)
        val vis = visibility(ctor)

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            NOVAH_GENCLASS_VERSION,
            vis + ACC_FINAL,
            className,
            sig,
            adtClassName,
            emptyArray<String>()
        )
        cw.visitSource(ast.sourceName, null)

        if (ctor.args.isEmpty()) {
            genEmptyConstructor(cw, ACC_PRIVATE)

            // TODO: generate signature
            cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, INSTANCE, descriptor(className), null, null)

            genDefaultStaticInitializer(cw, className)
        } else {
            // fields
            ctor.args.forEachIndexed { i, type ->
                cw.visitField(
                    ACC_PUBLIC + ACC_FINAL,
                    "v${i + 1}",
                    toInternalType(type),
                    maybeBuildFieldSignature(type),
                    null
                )
            }

            // constructor
            val desc = ctor.args.joinToString("", prefix = "(", postfix = ")V") { toInternalType(it) }
            val signature = if (ctor.args.any { it.isGeneric() })
                ctor.args.joinToString("", prefix = "(", postfix = ")V") { buildFieldSignature(it) }
            else null
            val ct = cw.visitMethod(ACC_PUBLIC, INIT, desc, signature, emptyArray())
            ct.visitCode()
            ct.visitVarInsn(ALOAD, 0)
            ct.visitMethodInsn(INVOKESPECIAL, adtClassName, INIT, "()V", false)
            ctor.args.forEachIndexed { i, type ->
                val index = i + 1
                ct.visitVarInsn(ALOAD, 0)
                ct.visitVarInsn(ALOAD, index)
                ct.visitFieldInsn(PUTFIELD, className, "v$index", toInternalType(type))
            }
            ct.visitInsn(RETURN)
            ct.visitMaxs(0, 0)
            ct.visitEnd()
        }

        cw.visitEnd()
        onGenClass(moduleName, ctor.name, cw.toByteArray())
    }

    companion object {
        fun genEmptyConstructor(cw: ClassWriter, access: Int) {
            val init = cw.visitMethod(access, INIT, "()V", null, emptyArray())
            init.visitCode()
            init.visitVarInsn(ALOAD, 0)
            init.visitMethodInsn(INVOKESPECIAL, OBJECT_CLASS, INIT, "()V", false)
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
        }

        private fun genDefaultStaticInitializer(cw: ClassWriter, className: String) {
            val init = cw.visitMethod(ACC_STATIC, STATIC_INIT, "()V", null, emptyArray())
            init.visitCode()
            init.visitTypeInsn(NEW, className)
            init.visitInsn(DUP)
            init.visitMethodInsn(INVOKESPECIAL, className, INIT, "()V", false)
            init.visitVarInsn(ASTORE, 0)
            init.visitVarInsn(ALOAD, 0)
            init.visitFieldInsn(PUTSTATIC, className, INSTANCE, descriptor(className))
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
        }
    }
}
package novah.backend

import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
import novah.ast.optimized.Module
import novah.ast.optimized.Type
import novah.backend.GenUtil.INIT
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.lambdaHandle
import novah.backend.GenUtil.lambdaMethodType
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.FUNCTION_TYPE
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.buildFieldSignature
import novah.backend.TypeUtil.buildFunctions
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type as ASMType
import org.objectweb.asm.Opcodes.*
import java.util.*

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

    // true if this type has only one constructor with the same name as the type
    // in this case don't generate a superclass
    private val singleSameCtor = adt.dataCtors.size == 1 && adt.dataCtors.first().name == adt.name

    fun run() {
        if (!singleSameCtor) {
            genType()
        }
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
        val superClass = if (singleSameCtor) OBJECT_CLASS else adtClassName
        val sig = buildClassSignature(adt.tyVars, superClass)
        val vis = visibility(ctor)
        val args = ctor.args
        val ctorType = Type.TConstructor(className, ctor.args)

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            NOVAH_GENCLASS_VERSION,
            vis + ACC_FINAL,
            className,
            sig,
            superClass,
            emptyArray<String>()
        )
        cw.visitSource(ast.sourceName, null)

        if (args.isEmpty()) {
            genEmptyConstructor(cw, ACC_PRIVATE)

            val fieldSig = if (adt.tyVars.isNotEmpty()) {
                "L$className" + adt.tyVars.joinToString(
                    "",
                    prefix = "<",
                    postfix = ">"
                ) { "T${it.toUpperCase()};" } + ";"
            } else null
            cw.visitField(ACC_PUBLIC + ACC_FINAL + ACC_STATIC, INSTANCE, descriptor(className), fieldSig, null)

            genDefaultStaticInitializer(cw, className)
        } else {
            cw.visitInnerClass(
                "java/lang/invoke/MethodHandles\$Lookup",
                "java/lang/invoke/MethodHandles",
                "Lookup",
                ACC_PUBLIC + ACC_STATIC + ACC_FINAL
            )

            // fields
            args.forEachIndexed { i, type ->
                cw.visitField(
                    ACC_PUBLIC + ACC_FINAL,
                    "v${i + 1}",
                    toInternalType(type),
                    maybeBuildFieldSignature(type),
                    null
                )
            }

            // constructor
            val desc = args.joinToString("", prefix = "(", postfix = ")V") { toInternalType(it) }
            val signature = if (args.any { it.isGeneric() })
                args.joinToString("", prefix = "(", postfix = ")V") { buildFieldSignature(it) }
            else null
            val ct = cw.visitMethod(ACC_PUBLIC, INIT, desc, signature, emptyArray())
            ct.visitCode()
            ct.visitVarInsn(ALOAD, 0)
            ct.visitMethodInsn(INVOKESPECIAL, superClass, INIT, "()V", false)
            args.forEachIndexed { i, type ->
                val index = i + 1
                ct.visitVarInsn(ALOAD, 0)
                ct.visitVarInsn(ALOAD, index)
                ct.visitFieldInsn(PUTFIELD, className, "v$index", toInternalType(type))
            }
            ct.visitInsn(RETURN)
            ct.visitMaxs(0, 0)
            ct.visitEnd()

            // curried static lambda constructor field
            val ctorSig = buildFieldSignature(buildFunctions(args + ctorType))
            cw.visitField(ACC_PUBLIC + ACC_STATIC + ACC_FINAL, LAMBDA_CTOR, FUNCTION_TYPE, ctorSig, null)

            // generate the lambda synthetic methods
            val appliedTypes = mutableListOf<Type>()
            val toApplyTypes = LinkedList(args)
            repeat(args.size) {
                makeLambdaMethod(cw, className, appliedTypes, toApplyTypes, desc)
                appliedTypes += toApplyTypes.remove()
            }

            genFunctionalConstructor(cw, className, args)
        }

        cw.visitEnd()
        onGenClass(moduleName, ctor.name, cw.toByteArray())
    }

    companion object {
        const val INSTANCE = "INSTANCE"
        const val LAMBDA_CTOR = "CREATE"

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
            init.visitFieldInsn(PUTSTATIC, className, INSTANCE, descriptor(className))
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
        }

        /**
         * Same as the default constructor but curried.
         */
        private fun genFunctionalConstructor(cw: ClassWriter, className: String, args: List<Type>) {
            val init = cw.visitMethod(ACC_STATIC, STATIC_INIT, "()V", null, emptyArray())
            init.visitCode()
            createLambda(init, className, emptyList(), args)
            init.visitFieldInsn(PUTSTATIC, className, LAMBDA_CTOR, FUNCTION_TYPE)
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
        }

        private fun makeLambdaMethod(
            cw: ClassWriter,
            className: String,
            appliedTypes: List<Type>,
            toApplyTypes: List<Type>,
            ctorDesc: String
        ) {
            val totalApplied = appliedTypes.size
            val totalArgs = totalApplied + toApplyTypes.size
            val desc = appliedTypes.joinToString("") { toInternalType(it) }
            val ret = if (toApplyTypes.size > 1) FUNCTION_TYPE else descriptor(className)
            val nextArg = toApplyTypes.first()
            val arg = toInternalType(nextArg)
            val end = toApplyTypes.size == 1

            val lam = cw.visitMethod(
                ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC,
                "lambda\$static\$$totalApplied",
                "($desc$arg)$ret",
                null,
                emptyArray()
            )
            lam.visitCode()
            if (end) {
                lam.visitTypeInsn(NEW, className)
                lam.visitInsn(DUP)
                repeat(totalArgs) { lam.visitVarInsn(ALOAD, it) }
                lam.visitMethodInsn(INVOKESPECIAL, className, INIT, ctorDesc, false)
            } else {
                repeat(totalApplied + 1) { lam.visitVarInsn(ALOAD, it) }
                createLambda(lam, className, appliedTypes + nextArg, toApplyTypes.drop(1))
            }
            lam.visitInsn(ARETURN)
            lam.visitMaxs(0, 0)
            lam.visitEnd()
        }

        private fun createLambda(
            mw: MethodVisitor,
            className: String,
            appliedTypes: List<Type>,
            toApplyTypes: List<Type>
        ) {
            val totalApplied = appliedTypes.size
            val desc = appliedTypes.joinToString("") { toInternalType(it) }
            val ret = if (toApplyTypes.size > 1) FUNCTION_TYPE else descriptor(className)
            val nextArg = toApplyTypes.first()
            val arg = toInternalType(nextArg)

            val handle = Handle(
                H_INVOKESTATIC,
                className,
                "lambda\$static\$$totalApplied",
                "($desc$arg)$ret",
                false
            )
            mw.visitInvokeDynamicInsn(
                "apply",
                "(${desc})Ljava/util/function/Function;",
                lambdaHandle,
                lambdaMethodType,
                handle,
                ASMType.getMethodType("($arg)$ret")
            )
        }
    }
}
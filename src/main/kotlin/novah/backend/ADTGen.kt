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

import novah.ast.optimized.Clazz
import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
import novah.ast.optimized.Module
import novah.backend.GenUtil.INIT
import novah.backend.GenUtil.INSTANCE
import novah.backend.GenUtil.LAMBDA_CTOR
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.lambdaHandle
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.FUNCTION_DESC
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.lambdaMethodType
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.util.*

/**
 * Generate bytecode for Algebraic Data Types.
 * Each ADT and constructor is a separate class
 */
class ADTGen(
    private val adt: Decl.TypeDecl,
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

        cw.visitInnerClass(
            "java/lang/invoke/MethodHandles\$Lookup",
            "java/lang/invoke/MethodHandles",
            "Lookup",
            ACC_PUBLIC + ACC_STATIC + ACC_FINAL
        )

        genEmptyConstructor(cw, ACC_PUBLIC)

        genToString(cw, adtClassName, adt.name, emptyList())

        cw.visitEnd()
        onGenClass(moduleName, adt.name, cw.toByteArray())
    }

    private fun genConstructor(ctor: DataConstructor) {
        val className = "$moduleName/${ctor.name}"
        val superClass = if (singleSameCtor) OBJECT_CLASS else adtClassName
        val sig = buildClassSignature(adt.tyVars, superClass)
        val vis = visibility(ctor)
        val args = ctor.args

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            NOVAH_GENCLASS_VERSION,
            ACC_PUBLIC + ACC_FINAL,
            className,
            sig,
            superClass,
            emptyArray<String>()
        )
        cw.visitSource(ast.sourceName, null)

        cw.visitInnerClass(
            "java/lang/invoke/MethodHandles\$Lookup",
            "java/lang/invoke/MethodHandles",
            "Lookup",
            ACC_PUBLIC + ACC_STATIC + ACC_FINAL
        )

        if (args.isEmpty()) {
            genEmptyConstructor(cw, ACC_PRIVATE, superClass)

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
            // fields
            args.forEachIndexed { i, type ->
                cw.visitField(
                    ACC_PUBLIC + ACC_FINAL,
                    "v${i + 1}",
                    type.type.descriptor,
                    null,
                    null
                )
            }

            // constructor
            val desc = args.joinToString("", prefix = "(", postfix = ")V") { it.type.descriptor }
            val ct = cw.visitMethod(vis, INIT, desc, null, emptyArray())
            ct.visitCode()
            ct.visitVarInsn(ALOAD, 0)
            ct.visitMethodInsn(INVOKESPECIAL, superClass, INIT, "()V", false)
            args.forEachIndexed { i, type ->
                val index = i + 1
                ct.visitVarInsn(ALOAD, 0)
                ct.visitVarInsn(ALOAD, index)
                ct.visitFieldInsn(PUTFIELD, className, "v$index", type.type.descriptor)
            }
            ct.visitInsn(RETURN)
            ct.visitMaxs(0, 0)
            ct.visitEnd()

            // curried static lambda constructor field
            cw.visitField(vis + ACC_STATIC + ACC_FINAL, LAMBDA_CTOR, FUNCTION_DESC, null, null)

            // generate the lambda synthetic methods
            val appliedTypes = mutableListOf<Clazz>()
            val toApplyTypes = LinkedList(args)
            repeat(args.size) {
                makeLambdaMethod(cw, className, appliedTypes, toApplyTypes, desc)
                appliedTypes += toApplyTypes.remove()
            }

            genFunctionalConstructor(cw, className, args)
        }

        genToString(cw, className, ctor.name, args)
        if (args.isNotEmpty()) {
            genEquals(cw, className, args)
            genHashCode(cw, className, args)
        }

        cw.visitEnd()
        onGenClass(moduleName, ctor.name, cw.toByteArray())
    }

    companion object {

        fun genEmptyConstructor(cw: ClassWriter, access: Int, superClass: String = OBJECT_CLASS) {
            val init = cw.visitMethod(access, INIT, "()V", null, emptyArray())
            init.visitCode()
            init.visitVarInsn(ALOAD, 0)
            init.visitMethodInsn(INVOKESPECIAL, superClass, INIT, "()V", false)
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
        private fun genFunctionalConstructor(cw: ClassWriter, className: String, args: List<Clazz>) {
            val init = cw.visitMethod(ACC_STATIC, STATIC_INIT, "()V", null, emptyArray())
            init.visitCode()
            createLambda(init, className, emptyList(), args)
            init.visitFieldInsn(PUTSTATIC, className, LAMBDA_CTOR, FUNCTION_DESC)
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
        }

        private fun makeLambdaMethod(
            cw: ClassWriter,
            className: String,
            appliedTypes: List<Clazz>,
            toApplyTypes: List<Clazz>,
            ctorDesc: String
        ) {
            val totalApplied = appliedTypes.size
            val totalArgs = totalApplied + toApplyTypes.size
            val desc = appliedTypes.joinToString("") { it.type.descriptor }
            val ret = if (toApplyTypes.size > 1) FUNCTION_DESC else descriptor(className)
            val nextArg = toApplyTypes.first()
            val arg = nextArg.type.descriptor
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
            appliedTypes: List<Clazz>,
            toApplyTypes: List<Clazz>
        ) {
            val totalApplied = appliedTypes.size
            val desc = appliedTypes.joinToString("") { it.type.descriptor }
            val ret = if (toApplyTypes.size > 1) FUNCTION_DESC else descriptor(className)
            val nextArg = toApplyTypes.first()
            val arg = nextArg.type.descriptor

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
                Type.getMethodType("($arg)$ret")
            )
        }

        /**
         * Generates a default toString method for this class
         * which prints all the variables
         */
        private fun genToString(cw: ClassWriter, className: String, simpleName: String, fields: List<Clazz>) {
            val ts = cw.visitMethod(ACC_PUBLIC, "toString", "()Ljava/lang/String;", null, emptyArray())
            ts.visitCode()
            if (fields.isEmpty()) {
                ts.visitLdcInsn(simpleName)
            } else {
                var args = ""
                fields.forEachIndexed { index, type ->
                    ts.visitVarInsn(ALOAD, 0)
                    args += type.type.descriptor
                    ts.visitFieldInsn(GETFIELD, className, "v${index + 1}", type.type.descriptor)
                }
                val pars = fields.indices.joinToString(", ") { "\u0001" }
                val arg = "$simpleName($pars)"
                ts.visitInvokeDynamicInsn("makeConcatWithConstants", "($args)Ljava/lang/String;", toStringHandle, arg)
            }
            ts.visitInsn(ARETURN)
            ts.visitMaxs(0, 0)
            ts.visitEnd()
        }

        /**
         * Generates a default equals method for this class
         * which compares every field
         */
        private fun genEquals(cw: ClassWriter, className: String, fields: List<Clazz>) {
            val eq = cw.visitMethod(ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, emptyArray())
            eq.visitCode()
            val l0 = Label()
            eq.visitLabel(l0)

            // if they are the same, return true
            eq.visitVarInsn(ALOAD, 0)
            eq.visitVarInsn(ALOAD, 1)
            val l1 = Label()
            eq.visitJumpInsn(IF_ACMPNE, l1)
            eq.visitInsn(ICONST_1)
            eq.visitInsn(IRETURN)

            // if they are different classes or null, return false
            eq.visitLabel(l1)
            val l2 = Label()
            eq.visitVarInsn(ALOAD, 1)
            eq.visitJumpInsn(IFNULL, l2)
            eq.visitVarInsn(ALOAD, 0)
            eq.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
            eq.visitVarInsn(ALOAD, 1)
            eq.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false)
            val l3 = Label()
            eq.visitJumpInsn(IF_ACMPEQ, l3)
            eq.visitLabel(l2)
            eq.visitInsn(ICONST_0)
            eq.visitInsn(IRETURN)
            eq.visitLabel(l3)

            // check attributes are the same
            eq.visitVarInsn(ALOAD, 1)
            eq.visitTypeInsn(CHECKCAST, className)
            eq.visitVarInsn(ASTORE, 2)
            val l4 = Label()
            eq.visitLabel(l4)
            val l5 = Label()
            val l6 = Label()
            fields.forEachIndexed { i, field ->
                val ty = field.type
                eq.visitVarInsn(ALOAD, 0)
                eq.visitFieldInsn(GETFIELD, className, "v${i + 1}", ty.descriptor)
                eq.visitVarInsn(ALOAD, 2)
                eq.visitFieldInsn(GETFIELD, className, "v${i + 1}", ty.descriptor)
                eq.visitMethodInsn(INVOKEVIRTUAL, ty.internalName, "equals", "(Ljava/lang/Object;)Z", false)
                eq.visitJumpInsn(IFEQ, l5)
            }
            eq.visitInsn(ICONST_1)
            eq.visitJumpInsn(GOTO, l6)
            eq.visitLabel(l5)
            eq.visitInsn(ICONST_0)
            eq.visitLabel(l6)
            eq.visitInsn(IRETURN)

            val desc = descriptor(className)
            val l7 = Label()
            eq.visitLabel(l7)
            eq.visitLocalVariable("this", desc, null, l0, l7, 0)
            eq.visitLocalVariable("other", desc, null, l0, l7, 1)
            eq.visitLocalVariable("same", desc, null, l4, l7, 2)

            eq.visitMaxs(0, 0)
            eq.visitEnd()
        }

        /**
         * Generates a hashCode function for this class
         * based on all attributes.
         */
        private fun genHashCode(cw: ClassWriter, className: String, fields: List<Clazz>) {
            val mv = cw.visitMethod(ACC_PUBLIC, "hashCode", "()I", null, emptyArray())
            mv.visitCode()
            val l0 = Label()
            mv.visitLabel(l0)

            loadSmallNumber(fields.size, mv)
            mv.visitTypeInsn(ANEWARRAY, OBJECT_CLASS)
            fields.forEachIndexed { i, type ->
                mv.visitInsn(DUP)
                loadSmallNumber(i, mv)
                mv.visitVarInsn(ALOAD, 0)
                mv.visitFieldInsn(GETFIELD, className, "v${i + 1}", type.type.descriptor)
                mv.visitInsn(AASTORE)
            }
            mv.visitMethodInsn(INVOKESTATIC, "java/util/Objects", "hash", "([Ljava/lang/Object;)I", false)
            mv.visitInsn(IRETURN)

            val l1 = Label()
            mv.visitLabel(l1)
            mv.visitLocalVariable("this", descriptor(className), null, l0, l1, 0)

            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        private fun loadSmallNumber(i: Int, mv: MethodVisitor) {
            when (i) {
                0 -> mv.visitInsn(ICONST_0)
                1 -> mv.visitInsn(ICONST_1)
                2 -> mv.visitInsn(ICONST_2)
                3 -> mv.visitInsn(ICONST_3)
                4 -> mv.visitInsn(ICONST_4)
                5 -> mv.visitInsn(ICONST_5)
                else -> mv.visitIntInsn(SIPUSH, i)
            }
        }

        private val toStringHandle = Handle(
            H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            false
        )
    }
}
/**
 * Copyright 2022 Islon Scherer
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
import novah.backend.GenUtil.bootstrapHandle
import novah.backend.GenUtil.lambdaHandle
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.FUNCTION_DESC
import novah.backend.TypeUtil.JAVA_RECORD_CLASS
import novah.backend.TypeUtil.OBJECT_DESC
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.isDouble
import novah.backend.TypeUtil.isLong
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import java.util.*

/**
 * Generate bytecode for Algebraic Data Types.
 * Each ADT and constructor is a separate record class
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
            vis + ACC_ABSTRACT + ACC_INTERFACE,
            adtClassName,
            sig,
            OBJECT_CLASS,
            arrayOf<String>()
        )
        cw.visitSource(ast.sourceName, null)

        cw.visitEnd()
        onGenClass(moduleName, adt.name, cw.toByteArray())
    }

    private fun genConstructor(ctor: DataConstructor) {
        val className = "$moduleName/${ctor.name}"
        val superClass = JAVA_RECORD_CLASS
        val interfaces = if (singleSameCtor) emptyArray() else arrayOf(adtClassName)
        val sig = buildClassSignature(adt.tyVars, superClass)
        val vis = visibility(ctor)
        val args = ctor.args

        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        cw.visit(
            NOVAH_GENCLASS_VERSION,
            ACC_PUBLIC + ACC_FINAL + ACC_RECORD,
            className,
            sig,
            superClass,
            interfaces
        )
        cw.visitSource(ast.sourceName, null)

        cw.visitInnerClass(
            "java/lang/invoke/MethodHandles\$Lookup",
            "java/lang/invoke/MethodHandles",
            "Lookup",
            ACC_PUBLIC + ACC_STATIC + ACC_FINAL
        )

        args.forEachIndexed { i, clazz -> cw.visitRecordComponent("v${i + 1}", clazz.type.descriptor, null) }

        if (args.isEmpty()) {
            genEmptyConstructor(cw, ACC_PRIVATE, superClass)

            val fieldSig = if (adt.tyVars.isNotEmpty()) {
                "L$className" + adt.tyVars.joinToString(
                    "",
                    prefix = "<",
                    postfix = ">"
                ) { "T${it.uppercase()};" } + ";"
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
            val l0 = Label()
            ct.visitLabel(l0)
            ct.visitVarInsn(ALOAD, 0)
            ct.visitMethodInsn(INVOKESPECIAL, superClass, INIT, "()V", false)
            var index = 1
            args.forEachIndexed { i, type ->
                ct.visitVarInsn(ALOAD, 0)
                ct.visitVarInsn(type.type.getOpcode(ILOAD), index)
                ct.visitFieldInsn(PUTFIELD, className, "v${i + 1}", type.type.descriptor)
                if (type.type.isDouble() || type.type.isLong()) index += 2 else index++
            }
            ct.visitInsn(RETURN)
            val l1 = Label()
            ct.visitLabel(l1)
            ct.visitLocalVariable("this", descriptor(className), null, l0, l1, 0)
            args.forEachIndexed { i, clazz ->
                ct.visitLocalVariable("v${i + 1}", clazz.type.descriptor, null, l0, l1, i + 1)
            }
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

        genToString(cw, className, args)
        genEquals(cw, className, args)
        genHashCode(cw, className, args)

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
                var index = 0
                (appliedTypes + toApplyTypes).forEach { clazz ->
                    lam.visitVarInsn(clazz.type.getOpcode(ILOAD), index)
                    if (clazz.type.isDouble() || clazz.type.isLong()) index += 2 else index++
                }
                lam.visitMethodInsn(INVOKESPECIAL, className, INIT, ctorDesc, false)
            } else {
                (appliedTypes + nextArg).forEachIndexed { i, clazz ->
                    lam.visitVarInsn(clazz.type.getOpcode(ILOAD), i)
                }
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
            val argTy = nextArg.type
            val retTy = Type.getType(ret)
            val method = TypeUtil.lambdaMethodName(argTy, retTy)
            val actualLambdaType = TypeUtil.lambdaType(argTy, retTy).descriptor
            val lambdaType = Type.getMethodType(TypeUtil.lambdaMethodDesc(argTy, retTy))
            mw.visitInvokeDynamicInsn(
                method,
                "($desc)$actualLambdaType",
                lambdaHandle,
                lambdaType,
                handle,
                Type.getMethodType("($arg)$ret")
            )
        }

        /**
         * Generates a default toString method for this class
         * which prints all the variables
         */
        private fun genToString(cw: ClassWriter, className: String, fields: List<Clazz>) {
            val ts = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "toString", "()Ljava/lang/String;", null, emptyArray())
            ts.visitCode()
            val l0 = Label()
            ts.visitLabel(l0)

            ts.visitVarInsn(ALOAD, 0)
            val desc = descriptor(className)

            val fieldStr = (1..fields.size).joinToString(";") { "v$it" }
            val fieldhandles = fields.mapIndexed { i, field ->
                Handle(H_GETFIELD, className, "v${i + 1}", field.type.descriptor, false)
            }.toTypedArray()
            ts.visitInvokeDynamicInsn(
                "toString",
                "($desc)Ljava/lang/String;",
                bootstrapHandle,
                Type.getType(desc),
                fieldStr,
                *fieldhandles
            )
            ts.visitInsn(ARETURN)

            val l1 = Label()
            ts.visitLabel(l1)
            ts.visitLocalVariable("this", desc, null, l0, l1, 0)

            ts.visitMaxs(0, 0)
            ts.visitEnd()
        }

        /**
         * Generates a default equals method for this class
         * which compares every field
         */
        private fun genEquals(cw: ClassWriter, className: String, fields: List<Clazz>) {
            val eq = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "equals", "(Ljava/lang/Object;)Z", null, emptyArray())
            eq.visitCode()
            val l0 = Label()
            eq.visitLabel(l0)

            eq.visitVarInsn(ALOAD, 0)
            eq.visitVarInsn(ALOAD, 1)
            val desc = descriptor(className)

            val fieldStr = (1..fields.size).joinToString(";") { "v$it" }
            val fieldhandles = fields.mapIndexed { i, field ->
                Handle(H_GETFIELD, className, "v${i + 1}", field.type.descriptor, false)
            }.toTypedArray()
            eq.visitInvokeDynamicInsn(
                "equals",
                "($desc$OBJECT_DESC)Z",
                bootstrapHandle,
                Type.getType(desc),
                fieldStr,
                *fieldhandles
            )
            eq.visitInsn(IRETURN)

            val l1 = Label()
            eq.visitLabel(l1)
            eq.visitLocalVariable("this", desc, null, l0, l1, 0)
            eq.visitLocalVariable("o", OBJECT_DESC, null, l0, l1, 1)

            eq.visitMaxs(0, 0)
            eq.visitEnd()
        }

        /**
         * Generates a hashCode function for this class
         * based on all attributes.
         */
        private fun genHashCode(cw: ClassWriter, className: String, fields: List<Clazz>) {
            val mv = cw.visitMethod(ACC_PUBLIC + ACC_FINAL, "hashCode", "()I", null, emptyArray())
            mv.visitCode()
            val l0 = Label()
            mv.visitLabel(l0)

            mv.visitVarInsn(ALOAD, 0)
            val desc = descriptor(className)
            val fieldStr = (1..fields.size).joinToString(";") { "v$it" }
            val fieldhandles = fields.mapIndexed { i, field ->
                Handle(H_GETFIELD, className, "v${i + 1}", field.type.descriptor, false)
            }.toTypedArray()
            mv.visitInvokeDynamicInsn(
                "hashCode",
                "($desc)I",
                bootstrapHandle,
                Type.getType(desc),
                fieldStr,
                *fieldhandles
            )
            mv.visitInsn(IRETURN)

            val l1 = Label()
            mv.visitLabel(l1)
            mv.visitLocalVariable("this", descriptor(className), null, l0, l1, 0)

            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }
    }
}
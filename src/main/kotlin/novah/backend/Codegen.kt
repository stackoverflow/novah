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

import novah.Util.internalError
import novah.ast.optimized.*
import novah.backend.GenUtil.INIT
import novah.backend.GenUtil.INSTANCE
import novah.backend.GenUtil.LAMBDA_CTOR
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.lambdaHandle
import novah.backend.GenUtil.lambdaMethodType
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.BOOL_CLASS
import novah.backend.TypeUtil.BYTE_CLASS
import novah.backend.TypeUtil.CHAR_CLASS
import novah.backend.TypeUtil.DOUBLE_CLASS
import novah.backend.TypeUtil.FLOAT_CLASS
import novah.backend.TypeUtil.FUNCTION_TYPE
import novah.backend.TypeUtil.INTEGER_CLASS
import novah.backend.TypeUtil.LONG_CLASS
import novah.backend.TypeUtil.OBJECT_TYPE
import novah.backend.TypeUtil.RECORD_CLASS
import novah.backend.TypeUtil.RECORD_TYPE
import novah.backend.TypeUtil.SHORT_CLASS
import novah.backend.TypeUtil.STRING_CLASS
import novah.backend.TypeUtil.STRING_TYPE
import novah.backend.TypeUtil.buildMethodSignature
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.toInternalClass
import novah.backend.TypeUtil.toInternalMethodType
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type.*

/**
 * Takes a typed AST and generates JVM bytecode.
 * @param onGenClass callback called for every class generated
 */
class Codegen(private val ast: Module, private val onGenClass: (String, String, ByteArray) -> Unit) {

    private val className = "${ast.name}/Module"

    fun run() {
        val cw = NovahClassWriter(COMPUTE_FRAMES)
        cw.visit(NOVAH_GENCLASS_VERSION, ACC_PUBLIC + ACC_FINAL, className, null, OBJECT_CLASS, arrayOf<String>())
        cw.visitSource(ast.sourceName, null)

        if (ast.hasLambda)
            cw.visitInnerClass(
                "java/lang/invoke/MethodHandles\$Lookup",
                "java/lang/invoke/MethodHandles",
                "Lookup",
                ACC_PUBLIC + ACC_STATIC + ACC_FINAL
            )

        var main: Decl.ValDecl? = null
        val datas = mutableListOf<Decl.DataDecl>()
        val values = mutableListOf<Decl.ValDecl>()
        for (decl in ast.decls) {
            when (decl) {
                is Decl.DataDecl -> {
                    for (ctor in decl.dataCtors) {
                        ctorCache["${ast.name}/${ctor.name}"] = ctor
                    }
                    datas += decl
                }
                is Decl.ValDecl -> if (isMain(decl)) main = decl else values += decl
            }
        }
        NovahClassWriter.addADTs(ast.name, datas)

        for (data in datas) ADTGen(data, ast, onGenClass).run()

        for (decl in values) genFieldVal(cw, decl)

        // empty ctor
        ADTGen.genEmptyConstructor(cw, ACC_PRIVATE)

        // lambda methods
        val allValues = if (main != null) values + main else values
        for (v in allValues) {
            val lambdas = setupLambdas(v)
            for (l in lambdas) genLambdaMethod(l, cw)
        }

        if (main != null) genMain(main, cw, GenContext())

        // static ctor
        genStaticCtor(cw, values)

        cw.visitEnd()
        onGenClass(ast.name, "Module", cw.toByteArray())
    }

    private fun genFieldVal(cw: ClassWriter, decl: Decl.ValDecl) {
        // Strings can be inlined directly
        val value = if (decl.exp is Expr.StringE) decl.exp.v else null
        cw.visitField(
            ACC_FINAL + ACC_STATIC + visibility(decl),
            decl.name,
            toInternalType(decl.exp.type),
            maybeBuildFieldSignature(decl.exp.type),
            value
        )
    }

    private fun genStaticCtor(cw: ClassWriter, values: List<Decl.ValDecl>) {
        val ctx = GenContext()
        val init = cw.visitMethod(ACC_STATIC, STATIC_INIT, "()V", null, emptyArray())
        init.visitCode()
        // TODO: check variable dependencies and sort them in orders
        for (decl in values) genValDecl(decl, init, ctx)

        init.visitInsn(RETURN)
        init.visitLabel(Label())
        ctx.visitLocalVariables(init)
        init.visitMaxs(0, 0)
        init.visitEnd()
    }

    private fun genValDecl(decl: Decl.ValDecl, mv: MethodVisitor, ctx: GenContext) {
        if (decl.exp is Expr.StringE) return
        val l = Label()
        mv.visitLabel(l)
        mv.visitLineNumber(decl.span.startLine, l)
        genExpr(decl.exp, mv, ctx)
        mv.visitFieldInsn(PUTSTATIC, className, decl.name, toInternalType(decl.exp.type))
    }

    private fun genExpr(e: Expr, mv: MethodVisitor, ctx: GenContext) {
        when (e) {
            is Expr.ByteE -> {
                genByte(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, BYTE_CLASS, "valueOf", "(B)L$BYTE_CLASS;", false)
            }
            is Expr.ShortE -> {
                genShort(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, SHORT_CLASS, "valueOf", "(S)L$SHORT_CLASS;", false)
            }
            is Expr.IntE -> {
                genInt(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, INTEGER_CLASS, "valueOf", "(I)L$INTEGER_CLASS;", false)
            }
            is Expr.LongE -> {
                genLong(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, LONG_CLASS, "valueOf", "(J)L$LONG_CLASS;", false)
            }
            is Expr.FloatE -> {
                genFloat(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, FLOAT_CLASS, "valueOf", "(F)L$FLOAT_CLASS;", false)
            }
            is Expr.DoubleE -> {
                genDouble(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, DOUBLE_CLASS, "valueOf", "(D)L$DOUBLE_CLASS;", false)
            }
            is Expr.CharE -> {
                genChar(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, CHAR_CLASS, "valueOf", "(C)L$CHAR_CLASS;", false)
            }
            is Expr.Bool -> {
                genBool(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, BOOL_CLASS, "valueOf", "(Z)L$BOOL_CLASS;", false)
            }
            is Expr.StringE -> {
                mv.visitLdcInsn(e.v)
            }
            is Expr.LocalVar -> {
                val local = ctx[e.name] ?: internalError("unmapped local variable in code generation: $e")
                ctx.setLocalVar(e)
                mv.visitVarInsn(ALOAD, local)
            }
            is Expr.Var -> {
                mv.visitFieldInsn(GETSTATIC, e.className, e.name, toInternalType(e.type))
            }
            is Expr.Constructor -> {
                if (e.arity == 0) mv.visitFieldInsn(GETSTATIC, e.fullName, INSTANCE, descriptor(e.fullName))
                else mv.visitFieldInsn(GETSTATIC, e.fullName, LAMBDA_CTOR, FUNCTION_TYPE)
            }
            is Expr.CtorApp -> {
                val name = e.ctor.fullName
                mv.visitTypeInsn(NEW, name)
                mv.visitInsn(DUP)
                var type = ""
                ctorCache[name]!!.args.forEach { type += toInternalType(it) }
                e.args.forEach {
                    genExpr(it, mv, ctx)
                }
                mv.visitMethodInsn(INVOKESPECIAL, name, INIT, "($type)V", false)
            }
            is Expr.ConstructorAccess -> {
                genExpr(e.ctor, mv, ctx)
                mv.visitFieldInsn(GETFIELD, e.fullName, "v${e.field}", toInternalType(e.type))
            }
            is Expr.OperatorApp -> {
                when (e.name) {
                    "&&" -> genOperatorAnd(e, mv, ctx)
                    "||" -> genOperatorOr(e, mv, ctx)
                    "==" -> genOperatorEquals(e, mv, ctx)
                }
                mv.visitMethodInsn(INVOKESTATIC, BOOL_CLASS, "valueOf", "(Z)L$BOOL_CLASS;", false)
            }
            is Expr.If -> {
                val endLabel = Label()
                var elseLabel: Label? = null
                e.conds.forEach { (cond, then) ->
                    if (elseLabel != null) mv.visitLabel(elseLabel)
                    genExprForPrimitiveBool(cond, mv, ctx)
                    elseLabel = Label()
                    mv.visitJumpInsn(IFEQ, elseLabel)
                    genExpr(then, mv, ctx)
                    mv.visitJumpInsn(GOTO, endLabel)
                }
                mv.visitLabel(elseLabel)
                genExpr(e.elseCase, mv, ctx)
                mv.visitLabel(endLabel)
            }
            is Expr.Let -> {
                val num = ctx.nextLocal()
                val varStartLabel = Label()
                val varEndLabel = Label()
                genExpr(e.bindExpr, mv, ctx)
                mv.visitVarInsn(ASTORE, num)
                mv.visitLabel(varStartLabel)
                ctx.put(e.binder, num, varStartLabel)

                // TODO: check if we need to define the variable before generating the letdef
                // to allow recursion
                genExpr(e.body, mv, ctx)
                mv.visitLabel(varEndLabel)
                ctx.setEndLabel(e.binder, varEndLabel)
            }
            is Expr.Do -> {
                val lastIndex = e.exps.size - 1
                e.exps.forEachIndexed { index, expr ->
                    genExpr(expr, mv, ctx)
                    if (index != lastIndex) mv.visitInsn(POP)
                }
            }
            is Expr.App -> {
                resolvePrimitiveModuleApp(mv, e, ctx)
            }
            is Expr.Lambda -> {
                val localTypes = mutableListOf<Type>()
                // load all local variables captured by this closure in order to partially apply them
                for (localVar in e.locals) {
                    val local = ctx[localVar.name] ?: internalError("unmapped local variable in code generation: $e")
                    localTypes += localVar.type
                    mv.visitVarInsn(ALOAD, local)
                }
                val applyDesc = toInternalMethodType(e.type, localTypes)
                val lambdaType = e.type as Type.TFun
                val lambdaMethodDesc = toInternalMethodType(lambdaType.ret, localTypes + lambdaType.arg)
                val handle = Handle(
                    H_INVOKESTATIC,
                    className,
                    e.internalName,
                    lambdaMethodDesc,
                    false
                )
                val ldesc = getMethodType(toInternalMethodType(lambdaType))

                mv.visitInvokeDynamicInsn(
                    "apply",
                    applyDesc,
                    lambdaHandle,
                    lambdaMethodType,
                    handle,
                    ldesc
                )
            }
            is Expr.InstanceOf -> {
                genInstanceOf(e, mv, ctx)
                mv.visitMethodInsn(INVOKESTATIC, BOOL_CLASS, "valueOf", "(Z)L$BOOL_CLASS;", false)
            }
            is Expr.NativeStaticFieldGet -> {
                val f = e.field
                mv.visitFieldInsn(GETSTATIC, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                if (f.type.isPrimitive) box(f.type, mv)
            }
            is Expr.NativeFieldGet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                mv.visitFieldInsn(GETFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                if (f.type.isPrimitive) box(f.type, mv)
            }
            is Expr.NativeStaticFieldSet -> {
                val f = e.field
                genExprForNativeCall(e.par, f.type, mv, ctx)
                mv.visitFieldInsn(PUTSTATIC, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                mv.visitInsn(ACONST_NULL)
            }
            is Expr.NativeFieldSet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                genExprForNativeCall(e.par, f.type, mv, ctx)
                mv.visitFieldInsn(PUTFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                mv.visitInsn(ACONST_NULL)
            }
            is Expr.NativeStaticMethod -> {
                val m = e.method
                m.parameterTypes.forEachIndexed { i, type ->
                    genExprForNativeCall(e.pars[i], type, mv, ctx)
                }
                val desc = getMethodDescriptor(m)
                mv.visitMethodInsn(INVOKESTATIC, getInternalName(m.declaringClass), m.name, desc, false)
                if (m.returnType.isPrimitive) box(m.returnType, mv)
            }
            is Expr.NativeMethod -> {
                val m = e.method
                genExpr(e.thisPar, mv, ctx) // load `this`
                m.parameterTypes.forEachIndexed { i, type ->
                    genExprForNativeCall(e.pars[i], type, mv, ctx)
                }
                val (op, isInterface) = if (m.declaringClass.isInterface) INVOKEINTERFACE to true else INVOKEVIRTUAL to false
                mv.visitMethodInsn(op, getInternalName(m.declaringClass), m.name, getMethodDescriptor(m), isInterface)
                if (m.returnType.isPrimitive) box(m.returnType, mv)
            }
            is Expr.NativeCtor -> {
                val c = e.ctor
                val internal = getInternalName(c.declaringClass)
                mv.visitTypeInsn(NEW, internal)
                mv.visitInsn(DUP)
                c.parameterTypes.forEachIndexed { i, type ->
                    genExprForNativeCall(e.pars[i], type, mv, ctx)
                }
                mv.visitMethodInsn(INVOKESPECIAL, internal, INIT, getConstructorDescriptor(c), false)
            }
            is Expr.Unit -> {
                mv.visitInsn(ACONST_NULL)
            }
            is Expr.Throw -> {
                genExpr(e.expr, mv, ctx)
                mv.visitInsn(ATHROW)
            }
            is Expr.Cast -> {
                genExpr(e.expr, mv, ctx)
                mv.visitTypeInsn(CHECKCAST, toInternalClass(e.type))
            }
            is Expr.RecordEmpty -> {
                mv.visitTypeInsn(NEW, RECORD_CLASS)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, RECORD_CLASS, INIT, "()V", false)
            }
            is Expr.RecordSelect -> {
                genExpr(e.expr, mv, ctx)
                mv.visitLdcInsn(e.label)
                mv.visitInsn(ACONST_NULL)
                val desc = "($STRING_TYPE$OBJECT_TYPE)$OBJECT_TYPE"
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "get", desc, false)
                val type = toInternalClass(e.type)
                if (type != OBJECT_CLASS)
                    mv.visitTypeInsn(CHECKCAST, toInternalClass(e.type))
            }
            is Expr.RecordRestrict -> {
                genExpr(e.expr, mv, ctx)
                mv.visitLdcInsn(e.label)
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "dissoc", "($STRING_TYPE)$RECORD_TYPE", false)
            }
            is Expr.RecordExtend -> {
                genExpr(e.expr, mv, ctx)
                // make the map linear before adding the labels then fork it
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "toTransient", "()$RECORD_TYPE", false)
                e.labels.forEach { kv ->
                    mv.visitLdcInsn(kv.key())
                    // TODO: duplicated labels
                    genExpr(kv.value().first(), mv, ctx)
                    val desc = "($STRING_TYPE$OBJECT_TYPE)$RECORD_TYPE"
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "assoc", desc, false)
                }
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "persistent", "()$RECORD_TYPE", false)
            }
        }
    }

    private fun genExprForPrimitiveBool(e: Expr, mv: MethodVisitor, ctx: GenContext) = when (e) {
        is Expr.Bool -> genBool(e, mv)
        is Expr.InstanceOf -> genInstanceOf(e, mv, ctx)
        is Expr.OperatorApp -> when (e.name) {
            "&&" -> genOperatorAnd(e, mv, ctx)
            "||" -> genOperatorOr(e, mv, ctx)
            "==" -> genOperatorEquals(e, mv, ctx)
            else -> internalError("unknown boolean operator ${e.name}")
        }
        else -> {
            genExpr(e, mv, ctx)
            mv.visitMethodInsn(INVOKEVIRTUAL, BOOL_CLASS, "booleanValue", "()Z", false)
        }
    }

    private fun genExprForNativeCall(e: Expr, type: Class<*>, mv: MethodVisitor, ctx: GenContext) {
        if (!type.isPrimitive) {
            genExpr(e, mv, ctx)
            return
        }
        val name = type.canonicalName
        when {
            name == "byte" && e is Expr.ByteE -> genByte(e, mv)
            name == "short" && e is Expr.ShortE -> genShort(e, mv)
            name == "int" && e is Expr.IntE -> genInt(e, mv)
            name == "long" && e is Expr.LongE -> genLong(e, mv)
            name == "float" && e is Expr.FloatE -> genFloat(e, mv)
            name == "double" && e is Expr.DoubleE -> genDouble(e, mv)
            name == "char" && e is Expr.CharE -> genChar(e, mv)
            name == "boolean" && e is Expr.Bool -> genBool(e, mv)
            name == "boolean" && e is Expr.InstanceOf -> genInstanceOf(e, mv, ctx)
            else -> {
                genExpr(e, mv, ctx)
                unbox(type, mv)
            }
        }
    }

    private fun genByte(e: Expr.ByteE, mv: MethodVisitor) {
        when (val i = e.v.toInt()) {
            0 -> mv.visitInsn(ICONST_0)
            1 -> mv.visitInsn(ICONST_1)
            2 -> mv.visitInsn(ICONST_2)
            3 -> mv.visitInsn(ICONST_3)
            4 -> mv.visitInsn(ICONST_4)
            5 -> mv.visitInsn(ICONST_5)
            else -> mv.visitIntInsn(BIPUSH, i)
        }
    }

    private fun genShort(e: Expr.ShortE, mv: MethodVisitor) {
        val i = e.v.toInt()
        when {
            i == 0 -> mv.visitInsn(ICONST_0)
            i == 1 -> mv.visitInsn(ICONST_1)
            i == 2 -> mv.visitInsn(ICONST_2)
            i == 3 -> mv.visitInsn(ICONST_3)
            i == 4 -> mv.visitInsn(ICONST_4)
            i == 5 -> mv.visitInsn(ICONST_5)
            i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, i)
            else -> mv.visitIntInsn(SIPUSH, i)
        }
    }

    private fun genInt(e: Expr.IntE, mv: MethodVisitor) {
        val i = e.v
        when {
            i == 0 -> mv.visitInsn(ICONST_0)
            i == 1 -> mv.visitInsn(ICONST_1)
            i == 2 -> mv.visitInsn(ICONST_2)
            i == 3 -> mv.visitInsn(ICONST_3)
            i == 4 -> mv.visitInsn(ICONST_4)
            i == 5 -> mv.visitInsn(ICONST_5)
            i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, i)
            i >= Short.MIN_VALUE && i <= Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, i)
            else -> mv.visitLdcInsn(i)
        }
    }

    private fun genLong(e: Expr.LongE, mv: MethodVisitor) {
        val l = e.v
        when {
            l == 0L -> mv.visitInsn(ICONST_0)
            l == 1L -> mv.visitInsn(ICONST_1)
            l == 2L -> mv.visitInsn(ICONST_2)
            l == 3L -> mv.visitInsn(ICONST_3)
            l == 4L -> mv.visitInsn(ICONST_4)
            l == 5L -> mv.visitInsn(ICONST_5)
            l >= Byte.MIN_VALUE && l <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, l.toInt())
            l >= Short.MIN_VALUE && l <= Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, l.toInt())
            l >= Int.MIN_VALUE && l <= Int.MAX_VALUE -> mv.visitLdcInsn(l.toInt())
            else -> mv.visitLdcInsn(l)
        }
    }

    private fun genFloat(e: Expr.FloatE, mv: MethodVisitor) {
        when (e.v) {
            0.0F -> mv.visitInsn(FCONST_0)
            1.0F -> mv.visitInsn(FCONST_1)
            2.0F -> mv.visitInsn(FCONST_2)
            else -> mv.visitLdcInsn(e.v)
        }
    }

    private fun genDouble(e: Expr.DoubleE, mv: MethodVisitor) {
        when (e.v) {
            0.0 -> mv.visitInsn(DCONST_0)
            1.0 -> mv.visitInsn(DCONST_1)
            else -> mv.visitLdcInsn(e.v)
        }
    }

    private fun genChar(e: Expr.CharE, mv: MethodVisitor) {
        val i = e.v.toInt()
        when {
            i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, i)
            i >= Short.MIN_VALUE && i <= Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, i)
            else -> mv.visitLdcInsn(i)
        }
    }

    private fun genBool(e: Expr.Bool, mv: MethodVisitor): Unit = mv.visitInsn(if (e.v) ICONST_1 else ICONST_0)

    private fun genInstanceOf(e: Expr.InstanceOf, mv: MethodVisitor, ctx: GenContext) {
        genExpr(e.exp, mv, ctx)
        mv.visitTypeInsn(INSTANCEOF, toInternalClass(e.type))
    }

    private fun genOperatorAnd(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val fail = Label()
        val success = Label()
        e.operands.forEach { op ->
            genExprForPrimitiveBool(op, mv, ctx)
            mv.visitJumpInsn(IFEQ, fail)
        }
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, success)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(success)
    }

    private fun genOperatorOr(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val fail = Label()
        val success = Label()
        val end = Label()
        val last = e.operands.size - 1
        e.operands.forEachIndexed { i, op ->
            genExprForPrimitiveBool(op, mv, ctx)

            if (i == last)
                mv.visitJumpInsn(IFEQ, fail)
            else
                mv.visitJumpInsn(IFNE, success)
        }
        mv.visitLabel(success)
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorEquals(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        if (e.operands.size != 2) internalError("got wrong number of operators for == operator")
        val op1 = e.operands[0]
        val op2 = e.operands[1]
        genExprForPrimitiveBool(op1, mv, ctx)
        genExprForPrimitiveBool(op2, mv, ctx)
        mv.visitMethodInsn(
            INVOKEVIRTUAL,
            toInternalClass(op1.type),
            "equals",
            "($OBJECT_TYPE)Z",
            false
        )
    }

    /**
     * Box a primitive type
     */
    private fun box(primitiveType: Class<*>, mv: MethodVisitor) {
        val name = primitiveType.canonicalName
        val wrapper = primitiveWrappers[name]
        val desc = "(" + getDescriptor(primitiveType) + ")" + getDescriptor(wrapper)
        mv.visitMethodInsn(INVOKESTATIC, getInternalName(wrapper), "valueOf", desc, false)
    }

    /**
     * Unbox a primitive type
     */
    private fun unbox(primitiveType: Class<*>, mv: MethodVisitor) {
        val name = primitiveType.canonicalName
        val internal = getInternalName(primitiveWrappers[name])
        mv.visitMethodInsn(INVOKEVIRTUAL, internal, "${name}Value", "()" + getDescriptor(primitiveType), false)
    }

    private fun resolvePrimitiveModuleApp(mv: MethodVisitor, e: Expr.App, ctx: GenContext) {
        val fn = e.fn
        if (fn is Expr.Var && fn.fullname() == "prim/Module.println") {
            mv.visitFieldInsn(GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            genExpr(e.arg, mv, ctx)
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false)
            mv.visitInsn(ACONST_NULL)
        } else if (fn is Expr.Var && fn.fullname() == "prim/Module.toString") {
            genExpr(e.arg, mv, ctx)
            val retType = e.arg.type.getReturnTypeNameOr("java/lang/Object")
            mv.visitMethodInsn(INVOKEVIRTUAL, retType, "toString", "()Ljava/lang/String;", false)
        } else if (fn is Expr.Var && fn.fullname() == "prim/Module.hashCode") {
            genExpr(e.arg, mv, ctx)
            mv.visitMethodInsn(INVOKEVIRTUAL, toInternalClass(e.arg.type), "hashCode", "()I", false)
            mv.visitMethodInsn(INVOKESTATIC, INTEGER_CLASS, "valueOf", "(I)L$INTEGER_CLASS;", false)
        } else {
            genExpr(fn, mv, ctx)
            genExpr(e.arg, mv, ctx)
            val retClass = toInternalClass((fn.type as Type.TFun).ret)
            mv.visitMethodInsn(
                INVOKEINTERFACE,
                "java/util/function/Function",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true
            )
            if (retClass != OBJECT_CLASS) {
                mv.visitTypeInsn(CHECKCAST, retClass)
            }
        }
    }

    class LambdaContext(val lambda: Expr.Lambda, var ignores: List<String>, var locals: List<Expr.LocalVar>)

    private fun setupLambdas(value: Decl.ValDecl): List<Expr.Lambda> {
        var i = 0
        fun mkName() = "lambda$${value.name}$${i++}"

        val lambdas = mutableListOf<LambdaContext>()

        fun go(exp: Expr): Unit = when (exp) {
            is Expr.Lambda -> {
                val binder = exp.binder
                if (binder != null)
                    for (l in lambdas) l.ignores += binder

                exp.internalName = mkName()
                val ignores = if (binder != null) listOf(binder) else emptyList()
                lambdas += LambdaContext(exp, ignores, emptyList())
                go(exp.body)
            }
            is Expr.LocalVar -> {
                val name = exp.name
                for (l in lambdas) {
                    if (name !in l.ignores) l.locals += exp
                }
            }
            is Expr.Let -> {
                for (l in lambdas) {
                    l.ignores += exp.binder
                }
                go(exp.bindExpr)
                go(exp.body)
            }
            is Expr.App -> {
                go(exp.fn)
                go(exp.arg)
            }
            is Expr.If -> {
                for ((cond, then) in exp.conds) {
                    go(cond)
                    go(then)
                }
                go(exp.elseCase)
            }
            is Expr.Do -> {
                for (e in exp.exps) go(e)
            }
            is Expr.OperatorApp -> {
                for (e in exp.operands) go(e)
            }
            is Expr.InstanceOf -> go(exp.exp)
            is Expr.NativeFieldGet -> go(exp.thisPar)
            is Expr.NativeStaticFieldSet -> go(exp.par)
            is Expr.NativeFieldSet -> {
                go(exp.thisPar)
                go(exp.par)
            }
            is Expr.NativeStaticMethod -> {
                for (e in exp.pars) go(e)
            }
            is Expr.NativeMethod -> {
                go(exp.thisPar)
                for (e in exp.pars) go(e)
            }
            is Expr.NativeCtor -> {
                for (e in exp.pars) go(e)
            }
            is Expr.Throw -> go(exp.expr)
            is Expr.Cast -> go(exp.expr)
            else -> {
            }
        }

        val v = if (isMain(value)) (value.exp as Expr.Lambda).body else value.exp
        go(v)

        return lambdas.map { l ->
            l.lambda.locals = l.locals
            l.lambda
        }
    }

    private fun genLambdaMethod(l: Expr.Lambda, cw: ClassWriter) {
        val ftype = l.type as Type.TFun
        val args = mutableListOf<Expr.LocalVar>()
        args.addAll(l.locals)
        if (l.binder != null) args += Expr.LocalVar(l.binder, ftype.arg)

        val argTypes = l.locals.map { it.type } + ftype.arg
        val lam = cw.visitMethod(
            ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC,
            l.internalName,
            toInternalMethodType(ftype.ret, argTypes),
            buildMethodSignature(ftype.ret, argTypes),
            emptyArray()
        )
        lam.visitCode()

        val startL = Label()
        val ctx = GenContext()
        args.forEach { local ->
            val num = ctx.nextLocal()
            ctx.put(local.name, num, startL)
            ctx.setLocalVar(local)
        }
        lam.visitLabel(startL)
        genExpr(l.body, lam, ctx)
        lam.visitInsn(ARETURN)

        val endL = Label()
        lam.visitLabel(endL)
        args.forEach { ctx.setEndLabel(it.name, endL) }
        ctx.visitLocalVariables(lam)

        lam.visitMaxs(0, 0)
        lam.visitEnd()
    }

    private fun genMain(d: Decl.ValDecl, cw: ClassWriter, ctx: GenContext) {
        val e = d.exp
        val main = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "main", "([Ljava/lang/String;)V", null, emptyArray())
        main.visitCode()

        val startL = Label()
        ctx.putParameter("args", Type.TConstructor("JArray", listOf(Type.TVar(STRING_CLASS))), startL)
        main.visitLineNumber(d.span.startLine, startL)

        val exp = (e as Expr.Lambda).body
        genExpr(exp, main, ctx)
        main.visitInsn(POP)
        main.visitInsn(RETURN)

        val endL = Label()
        main.visitLabel(endL)
        ctx.setEndLabel("args", endL)
        ctx.visitLocalVariables(main)

        main.visitMaxs(0, 0)
        main.visitEnd()
    }

    private fun isMain(d: Decl.ValDecl): Boolean {
        if (d.name != "main") return false
        val typ = d.exp.type
        if (typ !is Type.TFun) return false
        val ret = typ.ret
        return ret is Type.TVar && ret.name == OBJECT_CLASS
    }

    companion object {
        private val ctorCache = mutableMapOf<String, DataConstructor>()

        private val primitiveWrappers = mutableMapOf(
            "byte" to Byte::class.javaObjectType,
            "short" to Short::class.javaObjectType,
            "int" to Int::class.javaObjectType,
            "long" to Long::class.javaObjectType,
            "float" to Float::class.javaObjectType,
            "double" to Double::class.javaObjectType,
            "char" to Char::class.javaObjectType,
            "boolean" to Boolean::class.javaObjectType
        )
    }
}
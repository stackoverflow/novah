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
import novah.ast.source.Visibility
import novah.backend.GenUtil.INIT
import novah.backend.GenUtil.INSTANCE
import novah.backend.GenUtil.LAMBDA_CTOR
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.lambdaHandle
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.BOOL_CLASS
import novah.backend.TypeUtil.BYTE_CLASS
import novah.backend.TypeUtil.CHAR_CLASS
import novah.backend.TypeUtil.DOUBLE_CLASS
import novah.backend.TypeUtil.FLOAT_CLASS
import novah.backend.TypeUtil.FUNCTION_CLASS
import novah.backend.TypeUtil.FUNCTION_DESC
import novah.backend.TypeUtil.INTEGER_CLASS
import novah.backend.TypeUtil.LONG_CLASS
import novah.backend.TypeUtil.OBJECT_DESC
import novah.backend.TypeUtil.RECORD_CLASS
import novah.backend.TypeUtil.RECORD_DESC
import novah.backend.TypeUtil.SET_CLASS
import novah.backend.TypeUtil.SET_DESC
import novah.backend.TypeUtil.SHORT_CLASS
import novah.backend.TypeUtil.STRING_DESC
import novah.backend.TypeUtil.VECTOR_CLASS
import novah.backend.TypeUtil.VECTOR_DESC
import novah.backend.TypeUtil.box
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.lambdaMethodType
import novah.backend.TypeUtil.primitive
import novah.backend.TypeUtil.unbox
import novah.data.forEachList
import novah.frontend.Span
import org.objectweb.asm.*
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
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
        val datas = mutableListOf<Decl.TypeDecl>()
        val values = mutableListOf<Decl.ValDecl>()
        for (decl in ast.decls) {
            when (decl) {
                is Decl.TypeDecl -> {
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
            decl.exp.type.type.descriptor,
            null,
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

    private var lineNumber = -1

    private fun genValDecl(decl: Decl.ValDecl, mv: MethodVisitor, ctx: GenContext) {
        if (decl.exp is Expr.StringE) return
        val l = Label()
        mv.visitLabel(l)
        lineNumber = decl.span.startLine
        mv.visitLineNumber(lineNumber, l)
        genExpr(decl.exp, mv, ctx)
        mv.visitFieldInsn(PUTSTATIC, className, decl.name, decl.exp.type.type.descriptor)
    }

    private fun genExpr(e: Expr, mv: MethodVisitor, ctx: GenContext) {
        val startLine = e.span.startLine
        if (startLine != lineNumber && startLine != -1) {
            lineNumber = startLine
            val lnLabel = Label()
            mv.visitLabel(lnLabel)
            mv.visitLineNumber(lineNumber, lnLabel)
        }
        when (e) {
            is Expr.ByteE -> {
                genByte(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, BYTE_CLASS, "valueOf", "(B)L$BYTE_CLASS;", false)
            }
            is Expr.Int16 -> {
                genShort(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, SHORT_CLASS, "valueOf", "(S)L$SHORT_CLASS;", false)
            }
            is Expr.Int32 -> {
                genInt(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, INTEGER_CLASS, "valueOf", "(I)L$INTEGER_CLASS;", false)
            }
            is Expr.Int64 -> {
                genLong(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, LONG_CLASS, "valueOf", "(J)L$LONG_CLASS;", false)
            }
            is Expr.Float32 -> {
                genFloat(e, mv)
                mv.visitMethodInsn(INVOKESTATIC, FLOAT_CLASS, "valueOf", "(F)L$FLOAT_CLASS;", false)
            }
            is Expr.Float64 -> {
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
                val local = ctx[e.name] ?: internalError("unmapped local variable (${e.name}) in code generation: $e")
                ctx.setLocalVar(e)
                mv.visitVarInsn(ALOAD, local)
            }
            is Expr.Var -> {
                mv.visitFieldInsn(GETSTATIC, e.className, e.name, e.type.type.descriptor)
            }
            is Expr.Constructor -> {
                if (e.arity == 0) mv.visitFieldInsn(GETSTATIC, e.fullName, INSTANCE, descriptor(e.fullName))
                else mv.visitFieldInsn(GETSTATIC, e.fullName, LAMBDA_CTOR, FUNCTION_DESC)
            }
            is Expr.App -> {
                val fn = e.fn
                genExpr(fn, mv, ctx)
                genExpr(e.arg, mv, ctx)

                mv.visitMethodInsn(
                    INVOKEINTERFACE,
                    "java/util/function/Function",
                    "apply",
                    "(Ljava/lang/Object;)Ljava/lang/Object;",
                    true
                )
                if (fn.type.pars.size > 1) {
                    val retType = fn.type.pars[1].type
                    if (retType.internalName != OBJECT_CLASS) {
                        mv.visitTypeInsn(CHECKCAST, retType.internalName)
                    }
                }
            }
            is Expr.CtorApp -> {
                val name = e.ctor.fullName
                mv.visitTypeInsn(NEW, name)
                mv.visitInsn(DUP)
                var type = ""
                val ctorArgs = ctorCache[name]!!.args
                if (ctorArgs.size != e.args.size) {
                    internalError("Got wrong arity for constructor $name: ${e.args.size}")
                }
                e.args.zip(ctorArgs).forEach { (exp, ty) ->
                    type += ty.type.descriptor
                    genExpr(exp, mv, ctx)
                }
                mv.visitMethodInsn(INVOKESPECIAL, name, INIT, "($type)V", false)
            }
            is Expr.ConstructorAccess -> {
                genExpr(e.ctor, mv, ctx)
                mv.visitFieldInsn(GETFIELD, e.fullName, "v${e.field}", e.type.type.descriptor)
            }
            is Expr.OperatorApp -> {
                when (e.name) {
                    "&&" -> genOperatorAnd(e, mv, ctx)
                    "||" -> genOperatorOr(e, mv, ctx)
                    "+" -> genNumericOperator(e.name, e, mv, ctx)
                    "-" -> genNumericOperator(e.name, e, mv, ctx)
                    "*" -> genNumericOperator(e.name, e, mv, ctx)
                    "/" -> genNumericOperator(e.name, e, mv, ctx)
                }
                val prim = e.operands[0].type.type.primitive()
                if (prim != null) box(prim, mv)
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
            is Expr.Lambda -> {
                val localTypes = mutableListOf<Type>()
                // load all local variables captured by this closure in order to partially apply them
                for (localVar in e.locals) {
                    val local = ctx[localVar.name]
                        ?: internalError("unmapped local variable (${localVar.name}) in code generation: $e")
                    localTypes += localVar.type.type
                    mv.visitVarInsn(ALOAD, local)
                }
                val applyDesc = getMethodDescriptor(e.type.type, *localTypes.toTypedArray())
                val args = localTypes + e.type.pars[0].type
                val lambdaMethodDesc = getMethodDescriptor(e.type.pars[1].type, *args.toTypedArray())
                val handle = Handle(
                    H_INVOKESTATIC,
                    className,
                    e.internalName,
                    lambdaMethodDesc,
                    false
                )
                val ldesc = getMethodType(e.type.pars[1].type, e.type.pars[0].type)

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
                if (f.type.isPrimitive) box(getType(f.type), mv)
                val type = e.type.type
                val ftype = getType(f.type)
                if (type != ftype)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.NativeFieldGet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                mv.visitFieldInsn(GETFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                if (f.type.isPrimitive) box(getType(f.type), mv)
                val type = e.type.type
                val ftype = getType(f.type)
                if (type != ftype)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.NativeStaticFieldSet -> {
                val f = e.field
                genExprForNativeCall(e.par, f.type, mv, ctx)
                mv.visitFieldInsn(PUTSTATIC, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                genUnit(mv)
            }
            is Expr.NativeFieldSet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                genExprForNativeCall(e.par, f.type, mv, ctx)
                mv.visitFieldInsn(PUTFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                genUnit(mv)
            }
            is Expr.NativeStaticMethod -> {
                val m = e.method
                m.parameterTypes.forEachIndexed { i, type ->
                    genExprForNativeCall(e.pars[i], type, mv, ctx)
                }
                val desc = getMethodDescriptor(m)
                mv.visitMethodInsn(INVOKESTATIC, getInternalName(m.declaringClass), m.name, desc, false)

                if (m.returnType == Void.TYPE) genUnit(mv)
                else {
                    if (m.returnType.isPrimitive) box(getType(m.returnType), mv)
                    val type = e.type.type
                    val mtype = getType(m.returnType)
                    if (type != mtype)
                        mv.visitTypeInsn(CHECKCAST, type.internalName)
                }
            }
            is Expr.NativeMethod -> {
                val m = e.method
                genExpr(e.thisPar, mv, ctx) // load `this`
                m.parameterTypes.forEachIndexed { i, type ->
                    genExprForNativeCall(e.pars[i], type, mv, ctx)
                }
                val (op, isInterface) = if (m.declaringClass.isInterface) INVOKEINTERFACE to true else INVOKEVIRTUAL to false
                mv.visitMethodInsn(op, getInternalName(m.declaringClass), m.name, getMethodDescriptor(m), isInterface)

                if (m.returnType == Void.TYPE) genUnit(mv)
                else {
                    if (m.returnType.isPrimitive) box(getType(m.returnType), mv)
                    val type = e.type.type
                    val mtype = getType(m.returnType)
                    if (type != mtype)
                        mv.visitTypeInsn(CHECKCAST, type.internalName)
                }
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
                genUnit(mv)
            }
            is Expr.Throw -> {
                genExpr(e.expr, mv, ctx)
                mv.visitInsn(ATHROW)
            }
            is Expr.Cast -> {
                genExpr(e.expr, mv, ctx)
                mv.visitTypeInsn(CHECKCAST, e.type.type.internalName)
            }
            is Expr.RecordEmpty -> {
                mv.visitTypeInsn(NEW, RECORD_CLASS)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, RECORD_CLASS, INIT, "()V", false)
            }
            is Expr.RecordSelect -> {
                genExpr(e.expr, mv, ctx)
                mv.visitLdcInsn(e.label)
                val desc = "($STRING_DESC)$OBJECT_DESC"
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "unsafeGet", desc, false)
                val type = e.type.type
                if (type.internalName != OBJECT_CLASS)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.RecordRestrict -> {
                genExpr(e.expr, mv, ctx)
                mv.visitLdcInsn(e.label)
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "dissoc", "($STRING_DESC)$RECORD_DESC", false)
            }
            is Expr.RecordExtend -> {
                val shouldLinearize = e.labels.size() > MAP_LINEAR_THRESHOLD
                genExpr(e.expr, mv, ctx)
                // make the map linear before adding the labels then fork it
                if (shouldLinearize) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "_linear", "()$RECORD_DESC", false)
                }
                e.labels.forEach { kv ->
                    // we need to reverse here because the head of the linked
                    // list should be the last key added
                    kv.value().reversed().forEach { value ->
                        mv.visitLdcInsn(kv.key())
                        genExpr(value, mv, ctx)
                        val desc = "($STRING_DESC$OBJECT_DESC)$RECORD_DESC"
                        mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "assoc", desc, false)
                    }
                }
                if (shouldLinearize) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "_forked", "()$RECORD_DESC", false)
                }
            }
            is Expr.VectorLiteral -> {
                if (e.exps.isEmpty()) {
                    mv.visitMethodInsn(INVOKESTATIC, VECTOR_CLASS, "empty", "()$VECTOR_DESC", false)
                } else {
                    genInt(intExp(e.exps.size), mv)
                    mv.visitTypeInsn(ANEWARRAY, e.exps[0].type.type.internalName)
                    e.exps.forEachIndexed { i, exp ->
                        mv.visitInsn(DUP)
                        genInt(intExp(i), mv)
                        genExpr(exp, mv, ctx)
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitMethodInsn(INVOKESTATIC, VECTOR_CLASS, "of", "([$OBJECT_DESC)$VECTOR_DESC", false)
                }
            }
            is Expr.SetLiteral -> {
                if (e.exps.isEmpty()) {
                    mv.visitMethodInsn(INVOKESTATIC, SET_CLASS, "empty", "()$SET_DESC", false)
                } else {
                    genInt(intExp(e.exps.size), mv)
                    mv.visitTypeInsn(ANEWARRAY, e.exps[0].type.type.internalName)
                    e.exps.forEachIndexed { i, exp ->
                        mv.visitInsn(DUP)
                        genInt(intExp(i), mv)
                        genExpr(exp, mv, ctx)
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitMethodInsn(INVOKESTATIC, SET_CLASS, "of", "([$OBJECT_DESC)$SET_DESC", false)
                }
            }
            is Expr.ArrayLiteral -> {
                genInt(intExp(e.exps.size), mv)
                mv.visitTypeInsn(ANEWARRAY, e.exps[0].type.type.internalName)
                e.exps.forEachIndexed { i, exp ->
                    mv.visitInsn(DUP)
                    genInt(intExp(i), mv)
                    genExpr(exp, mv, ctx)
                    mv.visitInsn(AASTORE)
                }
            }
            is Expr.While -> {
                val whileLb = Label()
                val endLb = Label()
                mv.visitLabel(whileLb)
                genExprForPrimitiveBool(e.cond, mv, ctx)
                mv.visitJumpInsn(IFEQ, endLb)

                e.exps.forEach { expr ->
                    genExpr(expr, mv, ctx)
                    mv.visitInsn(POP)
                }
                mv.visitJumpInsn(GOTO, whileLb)
                mv.visitLabel(endLb)
                genUnit(mv)
            }
            is Expr.TryCatch -> {
                val beginTry = Label()
                val endTry = Label()
                val end = Label()
                e.catches.forEach {
                    val ls = Label() to Label()
                    it.labels = ls
                    mv.visitTryCatchBlock(beginTry, endTry, ls.first, it.exception.type.internalName)
                }

                var finallyLb: Label? = null
                if (e.finallyExp != null) {
                    finallyLb = Label()
                    mv.visitTryCatchBlock(beginTry, endTry, finallyLb, null)
                    e.catches.forEach {
                        val (startl, endl) = it.labels!!
                        mv.visitTryCatchBlock(startl, endl, finallyLb, null)
                    }
                }

                val retVar = ctx.nextLocal()
                val excVar = ctx.nextLocal()
                mv.visitLabel(beginTry)
                genExpr(e.tryExpr, mv, ctx)
                mv.visitVarInsn(ASTORE, retVar)
                mv.visitLabel(endTry)
                if (finallyLb != null) genExpr(e.finallyExp!!, mv, ctx)
                mv.visitJumpInsn(GOTO, end)

                e.catches.forEachIndexed { i, catch ->
                    val (slabel, elabel) = catch.labels!!
                    mv.visitLabel(slabel)
                    if (catch.binder != null) ctx.put(catch.binder, excVar, slabel)
                    mv.visitVarInsn(ASTORE, excVar)
                    genExpr(catch.expr, mv, ctx)
                    mv.visitVarInsn(ASTORE, retVar)
                    mv.visitLabel(elabel)
                    if (finallyLb != null) genExpr(e.finallyExp!!, mv, ctx)
                    if (i != e.catches.lastIndex || finallyLb != null)
                        mv.visitJumpInsn(GOTO, end)
                }
                if (finallyLb != null) {
                    mv.visitLabel(finallyLb)
                    mv.visitVarInsn(ASTORE, excVar)
                    genExpr(e.finallyExp!!, mv, ctx)
                    mv.visitVarInsn(ALOAD, excVar)
                    mv.visitInsn(ATHROW)
                    mv.visitInsn(POP)
                }
                mv.visitLabel(end)
                mv.visitVarInsn(ALOAD, retVar)
            }
        }
    }
    
    private fun genUnit(mv: MethodVisitor) {
        mv.visitFieldInsn(GETSTATIC, "novah/Unit", INSTANCE, "Lnovah/Unit;")
    }

    private fun genExprForPrimitiveBool(e: Expr, mv: MethodVisitor, ctx: GenContext) = when (e) {
        is Expr.Bool -> genBool(e, mv)
        is Expr.InstanceOf -> genInstanceOf(e, mv, ctx)
        is Expr.OperatorApp -> when (e.name) {
            "&&" -> genOperatorAnd(e, mv, ctx)
            "||" -> genOperatorOr(e, mv, ctx)
            else -> {
                genExpr(e, mv, ctx)
                mv.visitMethodInsn(INVOKEVIRTUAL, BOOL_CLASS, "booleanValue", "()Z", false)
            }
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
            name == "short" && e is Expr.Int16 -> genShort(e, mv)
            name == "int" && e is Expr.Int32 -> genInt(e, mv)
            name == "long" && e is Expr.Int64 -> genLong(e, mv)
            name == "float" && e is Expr.Float32 -> genFloat(e, mv)
            name == "double" && e is Expr.Float64 -> genDouble(e, mv)
            name == "char" && e is Expr.CharE -> genChar(e, mv)
            name == "boolean" && e is Expr.Bool -> genBool(e, mv)
            name == "boolean" && e is Expr.InstanceOf -> genInstanceOf(e, mv, ctx)
            else -> {
                genExpr(e, mv, ctx)
                unbox(getType(type), mv)
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

    private fun genShort(e: Expr.Int16, mv: MethodVisitor) {
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

    private fun genInt(e: Expr.Int32, mv: MethodVisitor) {
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

    private fun genLong(e: Expr.Int64, mv: MethodVisitor) {
        mv.visitLdcInsn(e.v)
    }

    private fun genFloat(e: Expr.Float32, mv: MethodVisitor) {
        when (e.v) {
            0.0F -> mv.visitInsn(FCONST_0)
            1.0F -> mv.visitInsn(FCONST_1)
            2.0F -> mv.visitInsn(FCONST_2)
            else -> mv.visitLdcInsn(e.v)
        }
    }

    private fun genDouble(e: Expr.Float64, mv: MethodVisitor) {
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
        mv.visitTypeInsn(INSTANCEOF, e.type.type.internalName)
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
        val last = e.operands.lastIndex
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

    private fun genNumericOperator(op: String, e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        if (e.operands.size != 2) internalError("got wrong number of operators for operator $op")
        val op1 = e.operands[0]
        val op2 = e.operands[1]
        val t1 = op1.type.type
        val prim = t1.primitive()!!
        val operation = when (op) {
            "+" -> IADD
            "-" -> ISUB
            "*" -> IMUL
            "/" -> IDIV
            else -> internalError("unkonwn operation $op")
        }
        val clazz = when (prim.className) {
            "int" -> Int::class.java
            "double" -> Double::class.java
            "long" -> Long::class.java
            "float" -> Float::class.java
            else -> internalError("unknown type to $op operator: ${t1.className}")
        }
        genExprForNativeCall(op1, clazz, mv, ctx)
        genExprForNativeCall(op2, clazz, mv, ctx)
        mv.visitInsn(prim.getOpcode(operation))
    }

    class LambdaContext(val lambda: Expr.Lambda, var ignores: List<String>, var locals: List<Expr.LocalVar>)

    private fun setupLambdas(value: Decl.ValDecl): List<Expr.Lambda> {
        var i = 0
        fun mkName() = "lambda$${value.name}$${i++}"

        val lambdas = mutableListOf<LambdaContext>()

        fun go(exp: Expr): Unit = when (exp) {
            is Expr.Lambda -> {
                val binder = exp.binder
                for (l in lambdas) l.ignores += binder

                exp.internalName = mkName()
                val ignores = listOf(binder)
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
            is Expr.CtorApp -> {
                for (e in exp.args) go(e)
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
            is Expr.RecordExtend -> {
                exp.labels.forEachList { go(it) }
                go(exp.expr)
            }
            is Expr.RecordSelect -> go(exp.expr)
            is Expr.RecordRestrict -> go(exp.expr)
            is Expr.VectorLiteral -> for (e in exp.exps) go(e)
            is Expr.SetLiteral -> for (e in exp.exps) go(e)
            is Expr.ArrayLiteral -> for (e in exp.exps) go(e)
            is Expr.TryCatch -> {
                go(exp.tryExpr)
                if (exp.finallyExp != null) go(exp.finallyExp)
                for (c in exp.catches) {
                    if (c.binder != null) {
                        for (l in lambdas) {
                            l.ignores += c.binder
                        }
                    }
                    go(c.expr)
                }
            }
            is Expr.While -> {
                go(exp.cond)
                for (e in exp.exps) go(e)
            }
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
        val ftype = l.type.pars
        val args = mutableListOf<Expr.LocalVar>()
        args.addAll(l.locals)
        args += Expr.LocalVar(l.binder, ftype[0], l.span)

        val argTypes = l.locals.map { it.type } + ftype[0]
        val lam = cw.visitMethod(
            ACC_PRIVATE + ACC_STATIC + ACC_SYNTHETIC,
            l.internalName,
            getMethodDescriptor(
                ftype[1].type,
                *argTypes.map { it.type }.toTypedArray()
            ),
            null,
            emptyArray()
        )
        lam.visitCode()

        val lnum = Label()
        lam.visitLabel(lnum)
        lam.visitLineNumber(l.span.startLine, lnum)

        val startL = Label()
        val ctx = GenContext()
        args.forEach { local ->
            val num = ctx.nextLocal()
            ctx.put(local.name, num, startL)
            ctx.setLocalVar(local)
        }
        lam.visitLabel(startL)
        genExpr(l.body, lam, ctx)
        if (l.body.type.type.internalName != OBJECT_CLASS) {
            lam.visitTypeInsn(CHECKCAST, l.body.type.type.internalName)
        }
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

        val lam = e as Expr.Lambda
        val startL = Label()
        ctx.putParameter(lam.binder, arrayOfStringClazz, startL)
        main.visitLineNumber(d.span.startLine, startL)

        val exp = lam.body
        genExpr(exp, main, ctx)
        main.visitInsn(POP)
        main.visitInsn(RETURN)

        val endL = Label()
        main.visitLabel(endL)
        ctx.setEndLabel(lam.binder, endL)
        ctx.visitLocalVariables(main)

        main.visitMaxs(0, 0)
        main.visitEnd()
    }

    private fun isMain(d: Decl.ValDecl): Boolean {
        if (d.name != "main" || d.visibility == Visibility.PRIVATE) return false
        val typ = d.exp.type
        if (typ.type.internalName != FUNCTION_CLASS && typ.pars.size != 2) return false
        return typ.pars[0].type == ARRAY_TYPE && typ.pars[0].pars[0].type.descriptor == STRING_DESC
    }

    companion object {
        private const val MAP_LINEAR_THRESHOLD = 5

        private val ctorCache = mutableMapOf<String, DataConstructor>()

        private val arrayOfStringClazz = Clazz(getType(Array<String>::class.java))

        private val ARRAY_TYPE = getType(Array::class.java)

        private fun intExp(n: Int): Expr.Int32 = Expr.Int32(n, Clazz(INT_TYPE), Span.empty())
    }
}
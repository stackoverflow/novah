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
import novah.backend.TypeUtil.FUNCTION_CLASS
import novah.backend.TypeUtil.FUNCTION_DESC
import novah.backend.TypeUtil.LIST_CLASS
import novah.backend.TypeUtil.LIST_DESC
import novah.backend.TypeUtil.OBJECT_DESC
import novah.backend.TypeUtil.RECORD_CLASS
import novah.backend.TypeUtil.RECORD_DESC
import novah.backend.TypeUtil.SET_CLASS
import novah.backend.TypeUtil.SET_DESC
import novah.backend.TypeUtil.STRING_DESC
import novah.backend.TypeUtil.UNIT_CLASS
import novah.backend.TypeUtil.box
import novah.backend.TypeUtil.descriptor
import novah.backend.TypeUtil.isPrimitive
import novah.backend.TypeUtil.isWrapper
import novah.backend.TypeUtil.unbox
import novah.backend.TypeUtil.wrapper
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

    private val className = "${ast.name}/\$Module"

    fun run() {
        try {
            innerRun()
        } catch (e: Exception) {
            System.err.println("Error generating code for module ${ast.name} (${ast.sourceName}).")
            throw e
        }
    }

    private fun innerRun() {
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
                is Decl.ValDecl -> {
                    values += decl
                    if (main == null && isMain(decl)) main = decl
                }
            }
        }
        NovahClassWriter.addADTs(ast.name, datas)

        for (data in datas) ADTGen(data, ast, onGenClass).run()

        for (decl in values) genFieldVal(cw, decl)

        // empty ctor
        ADTGen.genEmptyConstructor(cw, ACC_PRIVATE)

        // lambda methods
        for (v in values) {
            val lambdas = setupLambdas(v)
            for (l in lambdas) genLambdaMethod(l, cw)
        }

        if (main != null) genMain(main, cw, GenContext())

        // static ctor
        genStaticCtor(cw, values)

        cw.visitEnd()
        onGenClass(ast.name, "\$Module", cw.toByteArray())
    }

    private fun genFieldVal(cw: ClassWriter, decl: Decl.ValDecl) {
        // Primitives and strings can be inlined directly
        val value = when (val e = decl.exp) {
            is Expr.StringE -> e.v
            is Expr.Int32 -> e.v
            is Expr.Bool -> e.v
            is Expr.CharE -> e.v
            is Expr.Int64 -> e.v
            is Expr.Float64 -> e.v
            is Expr.Float32 -> e.v
            is Expr.ByteE -> e.v
            is Expr.Int16 -> e.v
            else -> null
        }
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
        for (decl in values) genValDecl(decl, init, ctx)

        init.visitInsn(RETURN)
        init.visitLabel(Label())
        ctx.visitLocalVariables(init)
        init.visitMaxs(0, 0)
        init.visitEnd()
    }

    private var lineNumber = -1

    private fun genValDecl(decl: Decl.ValDecl, mv: MethodVisitor, ctx: GenContext) {
        if (decl.exp.isPrimitive() || decl.exp is Expr.StringE) return
        val l = Label()
        mv.visitLabel(l)
        lineNumber = decl.span.startLine
        mv.visitLineNumber(lineNumber, l)
        genExpr(decl.exp, mv, ctx)
        mv.visitFieldInsn(PUTSTATIC, className, decl.name, decl.exp.type.type.descriptor)
    }

    private fun genExpr(e: Expr, mv: MethodVisitor, ctx: GenContext, unused: Boolean = false) {
        val startLine = e.span.startLine
        if (startLine != lineNumber && startLine != -1) {
            lineNumber = startLine
            val lnLabel = Label()
            mv.visitLabel(lnLabel)
            mv.visitLineNumber(lineNumber, lnLabel)
        }
        when (e) {
            is Expr.ByteE -> genByte(e, mv)
            is Expr.Int16 -> genShort(e, mv)
            is Expr.Int32 -> genInt(e, mv)
            is Expr.Int64 -> genLong(e, mv)
            is Expr.Float32 -> genFloat(e, mv)
            is Expr.Float64 -> genDouble(e, mv)
            is Expr.CharE -> genChar(e, mv)
            is Expr.Bool -> genBool(e, mv)
            is Expr.StringE -> mv.visitLdcInsn(e.v)
            is Expr.Null -> mv.visitInsn(ACONST_NULL)
            is Expr.LocalVar -> {
                val local =
                    ctx[e.name] ?: internalError("unmapped local variable (${e.name}) in code generation <${ast.name}>")
                ctx.setLocalVar(e)
                mv.visitVarInsn(e.type.type.getOpcode(ILOAD), local)
            }
            is Expr.SetLocalVar -> {
                val local =
                    ctx[e.name] ?: internalError("unmapped local variable (${e.name}) in code generation <${ast.name}>")
                genExpr(e.exp, mv, ctx)
                mv.visitVarInsn(e.exp.type.type.getOpcode(ISTORE), local)
                // The stack will be empty here, so no pop needed.
                // This works because the Do case takes care of generating a default value
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

                val arg = fn.type.pars[0].type
                val ret = fn.type.pars[1].type
                val method = TypeUtil.lambdaMethodName(arg, ret)
                val desc = TypeUtil.lambdaMethodDesc(arg, ret)
                mv.visitMethodInsn(
                    INVOKEINTERFACE,
                    FUNCTION_CLASS,
                    method,
                    desc,
                    true
                )

                if (!ret.isPrimitive() && ret.internalName != OBJECT_CLASS) {
                    mv.visitTypeInsn(CHECKCAST, ret.internalName)
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
                    if (exp.type.type.isPrimitive() && !ty.type.isPrimitive()) box(exp.type.type, mv)
                }
                mv.visitMethodInsn(INVOKESPECIAL, name, INIT, "($type)V", false)
            }
            is Expr.ConstructorAccess -> {
                genExpr(e.ctor, mv, ctx)
                mv.visitFieldInsn(GETFIELD, e.fullName, "v${e.field}", e.type.type.descriptor)
            }
            is Expr.OperatorApp -> when (e.name) {
                "&&" -> genOperatorAnd(e, mv, ctx)
                "||" -> genOperatorOr(e, mv, ctx)
                "==" -> genOperatorEquals(e, mv, ctx)
                "!=" -> genOperatorNotEquals(e, mv, ctx)
                ">" -> genOperatorGreater(e, mv, ctx)
                ">=" -> genOperatorGreaterOrEquals(e, mv, ctx)
                "<" -> genOperatorSmaller(e, mv, ctx)
                "<=" -> genOperatorSmallerOrEquals(e, mv, ctx)
                "+", "-", "*", "/" -> genNumericOperator(e.name, e, mv, ctx)
                "=null" -> genOperatorNull(e, mv, ctx, isEquals = true)
                "!=null" -> genOperatorNull(e, mv, ctx, isEquals = false)
            }
            is Expr.If -> {
                val endLabel = Label()
                var elseLabel: Label? = null
                e.conds.forEach { (cond, then) ->
                    if (elseLabel != null) mv.visitLabel(elseLabel)
                    genExpr(cond, mv, ctx)
                    elseLabel = Label()
                    mv.visitJumpInsn(IFEQ, elseLabel)
                    genExpr(then, mv, ctx, unused)
                    mv.visitJumpInsn(GOTO, endLabel)
                }
                mv.visitLabel(elseLabel)
                genExpr(e.elseCase, mv, ctx, unused)
                mv.visitLabel(endLabel)
            }
            is Expr.Let -> {
                val binderTy = e.bindExpr.type.type
                val num = ctx.nextLocal(binderTy)
                val varStartLabel = Label()
                val varEndLabel = Label()
                genExpr(e.bindExpr, mv, ctx)
                mv.visitVarInsn(binderTy.getOpcode(ISTORE), num)
                mv.visitLabel(varStartLabel)
                ctx.put(e.binder, num, varStartLabel)

                genExpr(e.body, mv, ctx, unused)
                mv.visitLabel(varEndLabel)
                ctx.setEndLabel(e.binder, varEndLabel)
            }
            is Expr.Do -> {
                val lastIndex = e.exps.lastIndex
                val last = e.exps.last()
                e.exps.forEachIndexed { index, expr ->
                    val isUnused = index != lastIndex && expr !is Expr.SetLocalVar
                    genExpr(expr, mv, ctx, isUnused)
                    if (isUnused)
                        popStack(mv, expr.type.type)
                }
                // If the last expression is a set local var the stack will be empty
                if (last is Expr.SetLocalVar) {
                    genZeroValue(mv, e.type.type)
                }
            }
            is Expr.Lambda -> {
                val localTypes = mutableListOf<Type>()
                // load all local variables captured by this closure in order to partially apply them
                for (localVar in e.locals) {
                    val local = ctx[localVar.name]
                        ?: internalError("unmapped local variable (${localVar.name}) in code generation <${ast.name}>")
                    val type = localVar.type.type
                    localTypes += type
                    mv.visitVarInsn(type.getOpcode(ILOAD), local)
                    if (!type.isPrimitive() && type.internalName != OBJECT_CLASS)
                        mv.visitTypeInsn(CHECKCAST, type.internalName)
                }
                val args = localTypes + e.type.pars[0].type
                val lambdaMethodDesc = getMethodDescriptor(e.type.pars[1].type, *args.toTypedArray())
                val handle = Handle(
                    H_INVOKESTATIC,
                    className,
                    e.internalName,
                    lambdaMethodDesc,
                    false
                )

                val arg = e.type.pars[0].type
                val ret = e.type.pars[1].type
                val ldesc = getMethodType(ret, arg)

                val actualLambdaType = TypeUtil.lambdaType(arg, ret)
                val applyDesc = getMethodDescriptor(actualLambdaType, *localTypes.toTypedArray())
                val method = TypeUtil.lambdaMethodName(arg, ret)
                val lambdaType = getMethodType(TypeUtil.lambdaMethodDesc(arg, ret))
                mv.visitInvokeDynamicInsn(
                    method,
                    applyDesc,
                    lambdaHandle,
                    lambdaType,
                    handle,
                    ldesc
                )
            }
            is Expr.InstanceOf -> genInstanceOf(e, mv, ctx)
            is Expr.ClassConstant -> mv.visitLdcInsn(e.type.pars[0].type.wrapper())
            is Expr.NativeStaticFieldGet -> {
                val f = e.field
                mv.visitFieldInsn(GETSTATIC, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                val type = e.type.type
                val ftype = getType(f.type)
                if (type.isPrimitive() && !ftype.isPrimitive()) {
                    if (!ftype.isWrapper()) mv.visitTypeInsn(CHECKCAST, type.wrapper().internalName)
                    // don't unbox unused values
                    if (!unused) unbox(type, mv)
                    else {
                        mv.visitInsn(POP)
                        genZeroValue(mv, type)
                    }
                }
                if (!type.isPrimitive() && type != ftype)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.NativeFieldGet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                mv.visitFieldInsn(GETFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                val type = e.type.type
                val ftype = getType(f.type)
                if (type.isPrimitive() && !ftype.isPrimitive()) {
                    if (!ftype.isWrapper()) mv.visitTypeInsn(CHECKCAST, type.wrapper().internalName)
                    // don't unbox unused values
                    if (!unused) unbox(type, mv)
                    else {
                        mv.visitInsn(POP)
                        genZeroValue(mv, type)
                    }
                }
                if (!type.isPrimitive() && type != ftype)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.NativeStaticFieldSet -> {
                val f = e.field
                genExpr(e.par, mv, ctx)
                val type = e.par.type.type
                if (type.isPrimitive() && !f.type.isPrimitive) box(type, mv)
                mv.visitFieldInsn(PUTSTATIC, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                genUnit(mv)
            }
            is Expr.NativeFieldSet -> {
                val f = e.field
                genExpr(e.thisPar, mv, ctx)
                genExpr(e.par, mv, ctx)
                val type = e.par.type.type
                if (type.isPrimitive() && !f.type.isPrimitive) box(type, mv)
                mv.visitFieldInsn(PUTFIELD, getInternalName(f.declaringClass), f.name, getDescriptor(f.type))
                genUnit(mv)
            }
            is Expr.NativeStaticMethod -> {
                val m = e.method
                m.parameterTypes.forEachIndexed { i, type ->
                    val par = e.pars[i]
                    genExpr(par, mv, ctx)
                    if (par.type.type.isPrimitive() && !type.isPrimitive) box(par.type.type, mv)
                }
                val desc = getMethodDescriptor(m)
                val isInterface = m.declaringClass.isInterface
                mv.visitMethodInsn(INVOKESTATIC, getInternalName(m.declaringClass), m.name, desc, isInterface)

                if (m.returnType == Void.TYPE) genUnit(mv)
                else {
                    val type = e.type.type
                    val rtype = getType(m.returnType)
                    if (type.isPrimitive() && !m.returnType.isPrimitive) {
                        if (!rtype.isWrapper()) mv.visitTypeInsn(CHECKCAST, type.wrapper().internalName)
                        // don't unbox unused values
                        if (!unused) unbox(type, mv)
                        else {
                            mv.visitInsn(POP)
                            genZeroValue(mv, type)
                        }
                    }
                    if (!type.isPrimitive() && type != rtype)
                        mv.visitTypeInsn(CHECKCAST, type.internalName)
                }
            }
            is Expr.NativeMethod -> {
                val m = e.method
                genExpr(e.thisPar, mv, ctx) // load `this`
                // this is just for primitive wrappers so we can do `123#longValue()` for example.
                if (e.thisPar.type.type.isPrimitive()) box(e.thisPar.type.type, mv)

                m.parameterTypes.forEachIndexed { i, type ->
                    val par = e.pars[i]
                    genExpr(par, mv, ctx)
                    if (par.type.type.isPrimitive() && !type.isPrimitive) box(par.type.type, mv)
                }
                val (op, isInterface) = if (m.declaringClass.isInterface) INVOKEINTERFACE to true else INVOKEVIRTUAL to false
                mv.visitMethodInsn(op, getInternalName(m.declaringClass), m.name, getMethodDescriptor(m), isInterface)

                if (m.returnType == Void.TYPE) genUnit(mv)
                else {
                    val type = e.type.type
                    val mtype = getType(m.returnType)
                    if (type.isPrimitive() && !m.returnType.isPrimitive) {
                        if (!mtype.isWrapper()) mv.visitTypeInsn(CHECKCAST, type.wrapper().internalName)
                        // don't unbox unused values
                        if (!unused) unbox(type, mv)
                        else {
                            mv.visitInsn(POP)
                            genZeroValue(mv, type)
                        }
                    }
                    if (!type.isPrimitive() && type != mtype)
                        mv.visitTypeInsn(CHECKCAST, type.internalName)
                }
            }
            is Expr.NativeCtor -> {
                val c = e.ctor
                val internal = getInternalName(c.declaringClass)
                mv.visitTypeInsn(NEW, internal)
                mv.visitInsn(DUP)
                c.parameterTypes.forEachIndexed { i, type ->
                    val par = e.pars[i]
                    genExpr(par, mv, ctx)
                    if (par.type.type.isPrimitive() && !type.isPrimitive) box(par.type.type, mv)
                }
                mv.visitMethodInsn(INVOKESPECIAL, internal, INIT, getConstructorDescriptor(c), false)
            }
            is Expr.Unit -> genUnit(mv)
            is Expr.Throw -> {
                genExpr(e.expr, mv, ctx)
                mv.visitInsn(ATHROW)
            }
            is Expr.Cast -> {
                val ty = e.type.type
                val exprTy = e.expr.type.type
                genExpr(e.expr, mv, ctx)
                when {
                    ty == exprTy -> {}
                    ty.isPrimitive() && !exprTy.isPrimitive() -> {
                        mv.visitTypeInsn(CHECKCAST, ty.wrapper().internalName)
                        unbox(ty, mv)
                    }
                    exprTy.isPrimitive() && !ty.isPrimitive() -> {
                        box(exprTy, mv)
                        mv.visitTypeInsn(CHECKCAST, ty.internalName)
                    }
                    else -> {
                        mv.visitTypeInsn(CHECKCAST, ty.internalName)
                    }
                }
            }
            is Expr.RecordEmpty -> {
                mv.visitTypeInsn(NEW, RECORD_CLASS)
                mv.visitInsn(DUP)
                mv.visitMethodInsn(INVOKESPECIAL, RECORD_CLASS, INIT, "()V", false)
            }
            is Expr.RecordSelect -> {
                genExpr(e.expr, mv, ctx)
                if (e.expr.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
                mv.visitLdcInsn(e.label)
                val desc = "($STRING_DESC)$OBJECT_DESC"
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "unsafeGet", desc, false)
                val type = e.type.type
                if (type.isPrimitive()) {
                    mv.visitTypeInsn(CHECKCAST, type.wrapper().internalName)
                    unbox(type, mv)
                } else if (type.internalName != OBJECT_CLASS)
                    mv.visitTypeInsn(CHECKCAST, type.internalName)
            }
            is Expr.RecordRestrict -> {
                genExpr(e.expr, mv, ctx)
                if (e.expr.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
                mv.visitLdcInsn(e.label)
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "dissoc", "($STRING_DESC)$RECORD_DESC", false)
            }
            is Expr.RecordUpdate -> {
                genExpr(e.expr, mv, ctx)
                if (e.expr.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
                mv.visitLdcInsn(e.label)
                genExpr(e.value, mv, ctx)
                if (e.value.type.type.isPrimitive()) box(e.value.type.type, mv)
                if (e.isSet) {
                    val descriptor = "(${STRING_DESC}$OBJECT_DESC)$RECORD_DESC"
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "set", descriptor, false)
                } else {
                    val descriptor = "(${STRING_DESC}$FUNCTION_DESC)$RECORD_DESC"
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "update", descriptor, false)
                }
            }
            is Expr.RecordExtend -> {
                val shouldLinearize = e.labels.size() > MAP_LINEAR_THRESHOLD
                genExpr(e.expr, mv, ctx)
                if (e.expr.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
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
                        if (value.type.type.isPrimitive()) box(value.type.type, mv)
                        val desc = "($STRING_DESC$OBJECT_DESC)$RECORD_DESC"
                        mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "assoc", desc, false)
                    }
                }
                if (shouldLinearize) {
                    mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "_forked", "()$RECORD_DESC", false)
                }
            }
            is Expr.RecordMerge -> {
                genExpr(e.exp1, mv, ctx)
                if (e.exp1.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
                genExpr(e.exp2, mv, ctx)
                if (e.exp2.type.type.internalName != RECORD_CLASS)
                    mv.visitTypeInsn(CHECKCAST, RECORD_CLASS)
                val descriptor = "($RECORD_DESC)$RECORD_DESC"
                mv.visitMethodInsn(INVOKEVIRTUAL, RECORD_CLASS, "merge", descriptor, false)
            }
            is Expr.ListLiteral -> {
                if (e.exps.isEmpty()) {
                    mv.visitMethodInsn(INVOKESTATIC, LIST_CLASS, "empty", "()$LIST_DESC", false)
                } else {
                    val elemTy = e.exps[0].type.type
                    genInt(intExp(e.exps.size), mv)
                    mv.visitTypeInsn(ANEWARRAY, elemTy.wrapper().internalName)
                    e.exps.forEachIndexed { i, exp ->
                        mv.visitInsn(DUP)
                        genInt(intExp(i), mv)
                        genExpr(exp, mv, ctx)
                        if (elemTy.isPrimitive()) box(elemTy, mv)
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitMethodInsn(INVOKESTATIC, LIST_CLASS, "of", "([$OBJECT_DESC)$LIST_DESC", false)
                }
            }
            is Expr.SetLiteral -> {
                if (e.exps.isEmpty()) {
                    mv.visitMethodInsn(INVOKESTATIC, SET_CLASS, "empty", "()$SET_DESC", false)
                } else {
                    val elemTy = e.exps[0].type.type
                    genInt(intExp(e.exps.size), mv)
                    mv.visitTypeInsn(ANEWARRAY, elemTy.wrapper().internalName)
                    e.exps.forEachIndexed { i, exp ->
                        mv.visitInsn(DUP)
                        genInt(intExp(i), mv)
                        genExpr(exp, mv, ctx)
                        if (elemTy.isPrimitive()) box(elemTy, mv)
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitMethodInsn(INVOKESTATIC, SET_CLASS, "of", "([$OBJECT_DESC)$SET_DESC", false)
                }
            }
            is Expr.ArrayLiteral -> {
                val elemTy = e.exps[0].type.type
                genInt(intExp(e.exps.size), mv)
                mv.visitTypeInsn(ANEWARRAY, elemTy.wrapper().internalName)
                e.exps.forEachIndexed { i, exp ->
                    mv.visitInsn(DUP)
                    genInt(intExp(i), mv)
                    genExpr(exp, mv, ctx)
                    if (elemTy.isPrimitive()) box(elemTy, mv)
                    mv.visitInsn(AASTORE)
                }
            }
            is Expr.ArrayLength -> {
                genExpr(e.expr, mv, ctx)
                mv.visitInsn(ARRAYLENGTH)
            }
            is Expr.Return -> {
                genExpr(e.exp, mv, ctx)
                mv.visitInsn(e.exp.type.type.getOpcode(IRETURN))
            }
            is Expr.Unbox -> {
                genExpr(e.exp, mv, ctx)
                if (e.type.type.isPrimitive()) {
                    unbox(e.type.type, mv)
                }
            }
            is Expr.While -> {
                val whileLb = Label()
                val endLb = Label()
                mv.visitLabel(whileLb)
                genExpr(e.cond, mv, ctx)
                mv.visitJumpInsn(IFEQ, endLb)

                e.exps.forEach { expr ->
                    genExpr(expr, mv, ctx, unused = true)
                    popStack(mv, expr.type.type)
                }
                mv.visitJumpInsn(GOTO, whileLb)
                mv.visitLabel(endLb)

                genZeroValue(mv, e.type.type)
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

                val expTy = e.tryExpr.type.type
                val retVar = ctx.nextLocal(expTy)
                val excVar = ctx.nextLocal() // type here is Object
                mv.visitLabel(beginTry)
                genExpr(e.tryExpr, mv, ctx)
                mv.visitVarInsn(expTy.getOpcode(ISTORE), retVar)
                mv.visitLabel(endTry)
                if (finallyLb != null) {
                    genExpr(e.finallyExp!!, mv, ctx, unused = true)
                    popStack(mv, e.finallyExp.type.type)
                }
                mv.visitJumpInsn(GOTO, end)

                e.catches.forEachIndexed { i, catch ->
                    val (slabel, elabel) = catch.labels!!
                    mv.visitLabel(slabel)
                    if (catch.binder != null) ctx.put(catch.binder, excVar, slabel)
                    mv.visitVarInsn(ASTORE, excVar)
                    genExpr(catch.expr, mv, ctx)
                    mv.visitVarInsn(expTy.getOpcode(ISTORE), retVar)
                    mv.visitLabel(elabel)
                    if (finallyLb != null) {
                        genExpr(e.finallyExp!!, mv, ctx, unused = true)
                        popStack(mv, e.finallyExp.type.type)
                    }
                    if (i != e.catches.lastIndex || finallyLb != null)
                        mv.visitJumpInsn(GOTO, end)
                }
                if (finallyLb != null) {
                    mv.visitLabel(finallyLb)
                    mv.visitVarInsn(ASTORE, excVar)
                    genExpr(e.finallyExp!!, mv, ctx, unused = true)
                    popStack(mv, e.finallyExp.type.type)
                    mv.visitVarInsn(ALOAD, excVar)
                    mv.visitInsn(ATHROW)
                }
                mv.visitLabel(end)
                mv.visitVarInsn(expTy.getOpcode(ILOAD), retVar)
            }
        }
    }

    private fun genUnit(mv: MethodVisitor) {
        mv.visitFieldInsn(GETSTATIC, "novah/Unit", INSTANCE, "Lnovah/Unit;")
    }

    /**
     * Generates a 0-value for this type.
     */
    private fun genZeroValue(mv: MethodVisitor, ty: Type) {
        when (ty.sort) {
            1, 2, 3, 4, 5 -> mv.visitInsn(ICONST_0)
            6 -> mv.visitInsn(FCONST_0)
            7 -> mv.visitLdcInsn(0L)
            8 -> mv.visitInsn(DCONST_0)
            else -> genUnit(mv)
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
        val i = e.v.code
        when {
            i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, i)
            i >= Short.MIN_VALUE && i <= Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, i)
            else -> mv.visitLdcInsn(i)
        }
    }

    private fun genBool(e: Expr.Bool, mv: MethodVisitor): Unit = mv.visitInsn(if (e.v) ICONST_1 else ICONST_0)

    private fun genInstanceOf(e: Expr.InstanceOf, mv: MethodVisitor, ctx: GenContext) {
        genExpr(e.exp, mv, ctx)
        if (e.exp.isPrimitive()) box(e.exp.type.type, mv)
        mv.visitTypeInsn(INSTANCEOF, e.type.type.internalName)
    }

    private fun genOperatorAnd(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val fail = Label()
        val success = Label()
        e.operands.forEach { op ->
            genExpr(op, mv, ctx)
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
            genExpr(op, mv, ctx)

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

    private fun genOperatorGreater(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // int
            5 -> mv.visitJumpInsn(IF_ICMPLE, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFLE, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPL)
                mv.visitJumpInsn(IFLE, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPL)
                mv.visitJumpInsn(IFLE, fail)
            }
        }
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorGreaterOrEquals(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // int
            5 -> mv.visitJumpInsn(IF_ICMPLT, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFLT, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPL)
                mv.visitJumpInsn(IFLT, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPL)
                mv.visitJumpInsn(IFLT, fail)
            }
        }
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorSmaller(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // int
            5 -> mv.visitJumpInsn(IF_ICMPGE, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFGE, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPG)
                mv.visitJumpInsn(IFGE, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPG)
                mv.visitJumpInsn(IFGE, fail)
            }
        }
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorSmallerOrEquals(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // int
            5 -> mv.visitJumpInsn(IF_ICMPGT, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFGT, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPG)
                mv.visitJumpInsn(IFGT, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPG)
                mv.visitJumpInsn(IFGT, fail)
            }
        }
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorEquals(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // boolean, char, byte, short, int
            1, 2, 3, 4, 5 -> mv.visitJumpInsn(IF_ICMPNE, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFNE, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPL)
                mv.visitJumpInsn(IFNE, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPL)
                mv.visitJumpInsn(IFNE, fail)
            }
        }

        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorNotEquals(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        val type = e.operands[0].type.type
        genExpr(e.operands[0], mv, ctx)
        genExpr(e.operands[1], mv, ctx)

        val fail = Label()
        val end = Label()
        when (type.sort) {
            // boolean, char, byte, short, int
            1, 2, 3, 4, 5 -> mv.visitJumpInsn(IF_ICMPEQ, fail)
            // long
            7 -> {
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFEQ, fail)
            }
            // float
            6 -> {
                mv.visitInsn(FCMPL)
                mv.visitJumpInsn(IFEQ, fail)
            }
            // double
            8 -> {
                mv.visitInsn(DCMPL)
                mv.visitJumpInsn(IFEQ, fail)
            }
        }

        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(fail)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genOperatorNull(e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext, isEquals: Boolean) {
        val op = if (isEquals) IFNONNULL else IFNULL
        genExpr(e.operands[0], mv, ctx)

        val success = Label()
        val end = Label()
        mv.visitJumpInsn(op, success)
        mv.visitInsn(ICONST_1)
        mv.visitJumpInsn(GOTO, end)
        mv.visitLabel(success)
        mv.visitInsn(ICONST_0)
        mv.visitLabel(end)
    }

    private fun genNumericOperator(op: String, e: Expr.OperatorApp, mv: MethodVisitor, ctx: GenContext) {
        if (e.operands.size != 2) internalError("got wrong number of operators for operator $op")
        val op1 = e.operands[0]
        val op2 = e.operands[1]
        val t1 = op1.type.type
        val operation = when (op) {
            "+" -> IADD
            "-" -> ISUB
            "*" -> IMUL
            "/" -> IDIV
            else -> internalError("unknown numeric operation $op")
        }
        genExpr(op1, mv, ctx)
        genExpr(op2, mv, ctx)
        mv.visitInsn(t1.getOpcode(operation))
    }

    private fun popStack(mv: MethodVisitor, ty: Type) {
        if (ty.sort == 7 || ty.sort == 8) {
            mv.visitInsn(POP2)
        } else {
            mv.visitInsn(POP)
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
                go(exp.bindExpr)
                for (l in lambdas) {
                    l.ignores += exp.binder
                }
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
            is Expr.RecordUpdate -> {
                go(exp.expr)
                go(exp.value)
            }
            is Expr.RecordMerge -> {
                go(exp.exp1)
                go(exp.exp2)
            }
            is Expr.ListLiteral -> for (e in exp.exps) go(e)
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
            is Expr.ConstructorAccess -> go(exp.ctor)
            is Expr.ArrayLength -> go(exp.expr)
            is Expr.Return -> go(exp.exp)
            is Expr.Unbox -> go(exp.exp)
            is Expr.SetLocalVar -> go(exp.exp)
            is Expr.ByteE, is Expr.Int16, is Expr.Int32, is Expr.Int64, is Expr.Float32, is Expr.Float64,
            is Expr.StringE, is Expr.CharE, is Expr.Bool, is Expr.Constructor, is Expr.Null, is Expr.Var,
            is Expr.RecordEmpty, is Expr.Unit, is Expr.NativeStaticFieldGet, is Expr.ClassConstant -> {
            }
        }

        go(value.exp)

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
            val num = ctx.nextLocal(local.type.type)
            ctx.put(local.name, num, startL)
            ctx.setLocalVar(local)
        }
        lam.visitLabel(startL)
        genExpr(l.body, lam, ctx)
        val bodyTy = l.body.type.type
        if (!bodyTy.isPrimitive() && bodyTy.internalName != OBJECT_CLASS) {
            lam.visitTypeInsn(CHECKCAST, bodyTy.internalName)
        }
        lam.visitInsn(ftype[1].type.getOpcode(IRETURN))

        val endL = Label()
        lam.visitLabel(endL)
        args.forEach { ctx.setEndLabel(it.name, endL) }
        ctx.visitLocalVariables(lam)

        lam.visitMaxs(0, 0)
        lam.visitEnd()
    }

    private fun genMain(d: Decl.ValDecl, cw: ClassWriter, ctx: GenContext) {
        val main = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "main", "([Ljava/lang/String;)V", null, emptyArray())
        main.visitCode()

        val startL = Label()
        ctx.putParameter("args", arrayOfStringClazz, startL)
        main.visitLabel(startL)

        main.visitFieldInsn(GETSTATIC, className, "main", d.exp.type.type.descriptor)
        main.visitVarInsn(ALOAD, 0)

        main.visitMethodInsn(
            INVOKEINTERFACE,
            FUNCTION_CLASS,
            "apply",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true
        )

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
        if (d.name != "main" || d.visibility == Visibility.PRIVATE) return false
        val typ = d.exp.type
        if (typ.type.internalName != FUNCTION_CLASS && typ.pars.size != 2) return false
        return typ.pars[0].type.className == "java.lang.String[]"
                && typ.pars[1].type.internalName == UNIT_CLASS
    }

    companion object {
        private const val MAP_LINEAR_THRESHOLD = 5

        private val ctorCache = mutableMapOf<String, DataConstructor>()

        private val arrayOfStringClazz = Clazz(getType(Array<String>::class.java))

        private fun intExp(n: Int): Expr.Int32 = Expr.Int32(n, Clazz(INT_TYPE), Span.empty())
    }
}
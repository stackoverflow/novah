package novah.backend

import novah.Util.internalError
import novah.ast.optimized.*
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
import novah.backend.TypeUtil.INTEGER_CLASS
import novah.backend.TypeUtil.LONG_CLASS
import novah.backend.TypeUtil.SHORT_CLASS
import novah.backend.TypeUtil.STRING_CLASS
import novah.backend.TypeUtil.buildMethodSignature
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.toInternalMethodType
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Type as ASMType
import org.objectweb.asm.Opcodes.*

/**
 * Takes a typed AST and generates JVM bytecode.
 * @param onGenClass callback called for every class generated
 */
class Codegen(private val ast: Module, private val onGenClass: (String, String, ByteArray) -> Unit) {

    private val className = "${ast.name}/Module"

    fun run() {
        val cw = ClassWriter(COMPUTE_FRAMES)
        cw.visit(NOVAH_GENCLASS_VERSION, ACC_PUBLIC + ACC_FINAL, className, null, OBJECT_CLASS, arrayOf<String>())
        cw.visitSource(ast.sourceName, null)

        if (ast.hasLambda)
            cw.visitInnerClass("java/lang/invoke/MethodHandles\$Lookup", "java/lang/invoke/MethodHandles", "Lookup", ACC_PUBLIC + ACC_STATIC + ACC_FINAL)

        var main: Decl.ValDecl? = null
        val datas = mutableListOf<Decl.DataDecl>()
        val values = mutableListOf<Decl.ValDecl>()
        for (decl in ast.decls) {
            when (decl) {
                is Decl.DataDecl -> datas += decl
                is Decl.ValDecl -> if (isMain(decl)) main = decl else values += decl
            }
        }

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
        mv.visitLineNumber(decl.lineNumber, l)
        genExpr(decl.exp, mv, ctx)
        mv.visitFieldInsn(PUTSTATIC, className, decl.name, toInternalType(decl.exp.type))
    }

    private fun genExpr(e: Expr, mv: MethodVisitor, ctx: GenContext) {
        when (e) {
            is Expr.ByteE -> {
                when (val i = e.v.toInt()) {
                    0 -> mv.visitInsn(ICONST_0)
                    1 -> mv.visitInsn(ICONST_1)
                    2 -> mv.visitInsn(ICONST_2)
                    3 -> mv.visitInsn(ICONST_3)
                    4 -> mv.visitInsn(ICONST_4)
                    5 -> mv.visitInsn(ICONST_5)
                    else -> mv.visitIntInsn(BIPUSH, i)
                }
                mv.visitMethodInsn(INVOKESTATIC, BYTE_CLASS, "valueOf", "(B)L$BYTE_CLASS;", false)
            }
            is Expr.ShortE -> {
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
                mv.visitMethodInsn(INVOKESTATIC, SHORT_CLASS, "valueOf", "(S)L$SHORT_CLASS;", false)
            }
            is Expr.IntE -> {
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
                mv.visitMethodInsn(INVOKESTATIC, INTEGER_CLASS, "valueOf", "(I)L$INTEGER_CLASS;", false)
            }
            is Expr.LongE -> {
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
                mv.visitMethodInsn(INVOKESTATIC, LONG_CLASS, "valueOf", "(J)L$LONG_CLASS;", false)
            }
            is Expr.FloatE -> {
                when (e.v) {
                    0.0F -> mv.visitInsn(FCONST_0)
                    1.0F -> mv.visitInsn(FCONST_1)
                    2.0F -> mv.visitInsn(FCONST_2)
                    else -> mv.visitLdcInsn(e.v)
                }
                mv.visitMethodInsn(INVOKESTATIC, FLOAT_CLASS, "valueOf", "(F)L$FLOAT_CLASS;", false)
            }
            is Expr.DoubleE -> {
                when (e.v) {
                    0.0 -> mv.visitInsn(DCONST_0)
                    1.0 -> mv.visitInsn(DCONST_1)
                    else -> mv.visitLdcInsn(e.v)
                }
                mv.visitMethodInsn(INVOKESTATIC, DOUBLE_CLASS, "valueOf", "(D)L$DOUBLE_CLASS;", false)
            }
            is Expr.StringE -> {
                mv.visitLdcInsn(e.v)
            }
            is Expr.CharE -> {
                val i = e.v.toInt()
                when {
                    i >= Byte.MIN_VALUE && i <= Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, i)
                    i >= Short.MIN_VALUE && i <= Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, i)
                    else -> mv.visitLdcInsn(i)
                }
                mv.visitMethodInsn(INVOKESTATIC, CHAR_CLASS, "valueOf", "(C)L$CHAR_CLASS;", false)
            }
            is Expr.Bool -> {
                mv.visitInsn(if (e.v) ICONST_1 else ICONST_0)
                mv.visitMethodInsn(INVOKESTATIC, BOOL_CLASS, "valueOf", "(Z)L$BOOL_CLASS;", false)
            }
            is Expr.LocalVar -> {
                val local = ctx[e.name] ?: internalError("unmapped local variable in code generation: $e")
                ctx.setLocalVar(e)
                mv.visitVarInsn(ALOAD, local)
            }
            is Expr.Var -> {
                resolvePrimitiveModuleVar(mv, e)
            }
            is Expr.Constructor -> {
                // TODO
            }
            is Expr.If -> {
                genExpr(e.cond, mv, ctx)
                val elseLabel = Label()
                val endLabel = Label()
                mv.visitMethodInsn(INVOKEVIRTUAL, BOOL_CLASS, "booleanValue", "()Z", false)
                mv.visitJumpInsn(IFEQ, elseLabel)
                genExpr(e.thenCase, mv, ctx)
                mv.visitJumpInsn(GOTO, endLabel)
                mv.visitLabel(elseLabel)
                genExpr(e.elseCase, mv, ctx)
                mv.visitLabel(endLabel)
            }
            is Expr.Let -> {
                val num = ctx.nextLocal()
                val varStartLabel = Label()
                val varEndLabel = Label()
                genExpr(e.letDef.expr, mv, ctx)
                mv.visitVarInsn(ASTORE, num)
                mv.visitLabel(varStartLabel)

                // TODO: check if we need to define the variable before generating the letdef
                // to allow recursion
                ctx.put(e.letDef.binder, num, varStartLabel)
                genExpr(e.body, mv, ctx)
                mv.visitLabel(varEndLabel)
                ctx.setEndLabel(e.letDef.binder, varEndLabel)
            }
            is Expr.Do -> {
                e.exps.forEachIndexed { index, expr ->
                    if (index != 0) mv.visitInsn(POP)
                    genExpr(expr, mv, ctx)
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
                val ldesc = ASMType.getMethodType(toInternalMethodType(lambdaType))

                mv.visitInvokeDynamicInsn(
                    "apply",
                    applyDesc,
                    lambdaHandle,
                    lambdaMethodType,
                    handle,
                    ldesc
                )
            }
        }
    }

    private fun resolvePrimitiveModuleVar(mv: MethodVisitor, e: Expr.Var) {
        if (e.fullname() == "prim/Module.unit") {
            mv.visitInsn(ACONST_NULL)
        } else {
            mv.visitFieldInsn(GETSTATIC, e.className, e.name, toInternalType(e.type))
        }
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
        } else {
            genExpr(fn, mv, ctx)
            genExpr(e.arg, mv, ctx)
            mv.visitMethodInsn(
                INVOKEINTERFACE,
                "java/util/function/Function",
                "apply",
                "(Ljava/lang/Object;)Ljava/lang/Object;",
                true
            )
        }
    }

    data class LambdaContext(val lambda: Expr.Lambda, var ignores: List<String>, var locals: List<Expr.LocalVar>)

    private fun setupLambdas(value: Decl.ValDecl): List<Expr.Lambda> {
        var i = 0
        fun mkName() = "lambda$${value.name}$${i++}"

        val lambdas = mutableListOf<LambdaContext>()

        fun go(exp: Expr): Unit = when (exp) {
            is Expr.Lambda -> {
                val binder = exp.binder
                for (l in lambdas) l.ignores += binder

                exp.internalName = mkName()
                lambdas += LambdaContext(exp, listOf(binder), listOf())
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
                    l.ignores += exp.letDef.binder
                }
                go(exp.letDef.expr)
                go(exp.body)
            }
            is Expr.App -> {
                go(exp.fn)
                go(exp.arg)
            }
            is Expr.If -> {
                go(exp.cond)
                go(exp.thenCase)
                go(exp.elseCase)
            }
            is Expr.Do -> {
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
        val ftype = l.type as Type.TFun
        val args = l.locals + Expr.LocalVar(l.binder, ftype.arg)
        val argTypes = args.map { it.type }
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
        main.visitLineNumber(d.lineNumber, startL)

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
        return ret is Type.TVar && ret.name == "prim/Unit"
    }
}
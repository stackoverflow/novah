package novah.backend

import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.optimized.Module
import novah.ast.optimized.Type
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.STATIC_INIT
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.BOOL_CLASS
import novah.backend.TypeUtil.CHAR_CLASS
import novah.backend.TypeUtil.FLOAT_CLASS
import novah.backend.TypeUtil.INTEGER_CLASS
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * Takes a typed AST and generates JVM bytecode.
 */
class Codegen(private val ast: Module, private val onGenClass: (String, String, ByteArray) -> Unit) {

    private val className = "${ast.name}/Module"

    fun run() {
        val cw = ClassWriter(COMPUTE_FRAMES)
        cw.visit(NOVAH_GENCLASS_VERSION, ACC_PUBLIC + ACC_FINAL, className, null, OBJECT_CLASS, arrayOf<String>())
        cw.visitSource(ast.sourceName, null)
        for (decl in ast.decls) {
            genDecl(cw, decl)
        }
        // empty ctor
        ADTGen.genEmptyConstructor(cw, ACC_PRIVATE)

        // static ctor
        genStaticCtor(cw)

        cw.visitEnd()
        onGenClass(ast.name, "Module", cw.toByteArray())
    }

    private fun genDecl(cw: ClassWriter, decl: Decl) {
        when (decl) {
            is Decl.DataDecl -> ADTGen(decl, ast, onGenClass).run()
            is Decl.ValDecl -> if (!isMain(decl)) genFieldVal(cw, decl)
        }
    }

    private fun genFieldVal(cw: ClassWriter, decl: Decl.ValDecl) {
        // Strings can be inlined directly
        val value = if (decl.exp is Expr.StringE) decl.exp.s else null
        cw.visitField(
            ACC_FINAL + ACC_STATIC + visibility(decl),
            decl.name,
            toInternalType(decl.exp.type),
            maybeBuildFieldSignature(decl.exp.type),
            value
        )
    }

    private fun genStaticCtor(cw: ClassWriter) {
        val init = cw.visitMethod(ACC_STATIC, STATIC_INIT, "()V", null, emptyArray())
        init.visitCode()
        for (decl in ast.decls.filterIsInstance<Decl.ValDecl>()) {
            if (isMain(decl)) genMain(decl.exp, cw)
            else genValDecl(decl, init)
        }
        init.visitInsn(RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()
    }

    private fun genValDecl(decl: Decl.ValDecl, mv: MethodVisitor) {
        if (decl.exp is Expr.StringE) return
        val l = Label()
        mv.visitLabel(l)
        mv.visitLineNumber(decl.lineNumber, l)
        genExpr(decl.exp, mv)
        mv.visitFieldInsn(PUTSTATIC, className, decl.name, toInternalType(decl.exp.type))
    }

    // TODO: visit local variables
    private fun genExpr(e: Expr, mv: MethodVisitor) {
        when (e) {
            is Expr.IntE -> {
                mv.visitLdcInsn(e.i)
                mv.visitMethodInsn(INVOKESTATIC, INTEGER_CLASS, "valueOf", "(I)L$INTEGER_CLASS;", false)
            }
            is Expr.FloatE -> {
                mv.visitLdcInsn(e.f)
                mv.visitMethodInsn(INVOKESTATIC, FLOAT_CLASS, "valueOf", "(F)L$FLOAT_CLASS;", false)
            }
            is Expr.StringE -> {
                mv.visitLdcInsn(e.s)
            }
            is Expr.CharE -> {
                // TODO: optimize in case c is small (use byte, not LDC)
                mv.visitLdcInsn(e.c)
                mv.visitMethodInsn(INVOKESTATIC, CHAR_CLASS, "valueOf", "(C)L$CHAR_CLASS;", false)
            }
            is Expr.Bool -> {
                mv.visitInsn(if (e.b) ICONST_1 else ICONST_0)
                mv.visitMethodInsn(INVOKESTATIC, BOOL_CLASS, "valueOf", "(Z)L$BOOL_CLASS;", false)
            }
            is Expr.LocalVar -> {
                mv.visitVarInsn(ALOAD, e.num)
            }
            is Expr.Var -> {
                resolvePrimitiveModule(mv, e)
            }
            is Expr.If -> {
                genExpr(e.cond, mv)
                val elseL = Label()
                val thenL = Label()
                // TODO: check if thix unbox is needed
                mv.visitMethodInsn(INVOKEVIRTUAL, BOOL_CLASS, "booleanValue", "()Z", false)
                mv.visitJumpInsn(IFEQ, elseL)
                genExpr(e.thenCase, mv)
                mv.visitJumpInsn(GOTO, thenL)
                mv.visitLabel(elseL)
                genExpr(e.elseCase, mv)
                mv.visitLabel(thenL)
            }
            is Expr.Let -> {
                genExpr(e.letDef.expr, mv)
                mv.visitVarInsn(ASTORE, e.letDef.num)
                genExpr(e.body, mv)
            }
        }
    }

    private fun resolvePrimitiveModule(mv: MethodVisitor, e: Expr.Var) {
        if (e.fullname() == "prim/Module.unit") {
            mv.visitInsn(ACONST_NULL)
        } else {
            mv.visitFieldInsn(GETSTATIC, e.className, e.name, toInternalType(e.type))
        }
    }

    private fun genMain(e: Expr, cw: ClassWriter) {
        val main = cw.visitMethod(ACC_PUBLIC + ACC_STATIC, "main", "([Ljava/lang/String;)V", null, emptyArray())
        main.visitCode()
        val exp = (e as Expr.Lambda).body
        genExpr(exp, main)
        main.visitInsn(RETURN)
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
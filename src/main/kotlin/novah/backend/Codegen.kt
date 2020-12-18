package novah.backend

import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.optimized.Module
import novah.backend.GenUtil.NOVAH_GENCLASS_VERSION
import novah.backend.GenUtil.OBJECT_CLASS
import novah.backend.GenUtil.visibility
import novah.backend.TypeUtil.maybeBuildFieldSignature
import novah.backend.TypeUtil.toInternalType
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ClassWriter.COMPUTE_FRAMES
import org.objectweb.asm.Opcodes.*

/**
 * Takes a typed AST and generates JVM bytecode.
 */
class Codegen(private val ast: Module, private val onGenClass: (String, String, ByteArray) -> Unit) {

    fun run() {
        val moduleName = "${ast.name}/Module"
        val cw = ClassWriter(COMPUTE_FRAMES)
        cw.visit(NOVAH_GENCLASS_VERSION, ACC_PUBLIC + ACC_FINAL, moduleName, null, OBJECT_CLASS, arrayOf<String>())
        for (decl in ast.decls) {
            genDecl(cw, decl)
        }
        ADTGen.genEmptyConstructor(cw, ACC_PRIVATE)
        cw.visitEnd()
        onGenClass(ast.name, "Module", cw.toByteArray())
    }

    private fun genDecl(cw: ClassWriter, decl: Decl) {
        when (decl) {
            is Decl.DataDecl -> ADTGen(decl, ast.name, onGenClass).run()
            is Decl.ValDecl -> genValDecl(cw, decl)
        }
    }

    private fun genValDecl(cw: ClassWriter, decl: Decl.ValDecl) {
        cw.visitField(
            ACC_FINAL + ACC_STATIC + visibility(decl),
            decl.name,
            toInternalType(decl.exp.type),
            maybeBuildFieldSignature(decl.exp.type),
            null
        )
    }

    private fun genExpr(expr: Expr) {

    }
}
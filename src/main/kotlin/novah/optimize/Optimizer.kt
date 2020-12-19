package novah.optimize

import novah.Util.internalError
import novah.ast.optimized.*
import novah.frontend.typechecker.GenNameStore.Companion.originalName
import novah.ast.canonical.DataConstructor as CDataConstructor
import novah.ast.canonical.Decl.DataDecl as CDataDecl
import novah.ast.canonical.Decl.ValDecl as CValDecl
import novah.ast.canonical.Expr as CExpr
import novah.ast.canonical.LetDef as CLetDef
import novah.ast.canonical.Module as CModule
import novah.frontend.typechecker.Type as TType

/**
 * Converts the canonical AST to the
 * optimized version, ready for codeden
 */
class Optimizer(private val ast: CModule) {

    private var localsCount = 0

    fun convert() = ast.convert()

    private fun CModule.convert(): Module {
        val ds = mutableListOf<Decl>()
        for (d in decls) {
            if (d is CDataDecl) ds += d.convert()
            if (d is CValDecl) ds += d.convert()
        }
        return Module(internalize(name), sourceName, ds)
    }

    private fun CValDecl.convert(): Decl.ValDecl = Decl.ValDecl(name, exp.convert(), visibility, span.start.line)

    private fun CDataDecl.convert(): Decl.DataDecl =
        Decl.DataDecl(name, tyVars, dataCtors.map { it.convert() }, visibility, span.start.line)

    private fun CDataConstructor.convert(): DataConstructor =
        DataConstructor(name, args.map { it.convert() }, visibility)

    private fun CExpr.convert(locals: Map<String, Int> = mapOf()): Expr {
        val typ = if (type != null) {
            type!!.convert()
        } else {
            internalError("Received expression without type after type checking")
        }
        return when (this) {
            is CExpr.IntE -> Expr.IntE(i, typ)
            is CExpr.FloatE -> Expr.FloatE(f, typ)
            is CExpr.StringE -> Expr.StringE(s, typ)
            is CExpr.CharE -> Expr.CharE(c, typ)
            is CExpr.Bool -> Expr.Bool(b, typ)
            is CExpr.Var -> {
                val local = locals[name]
                if (local != null) Expr.LocalVar(name, local, typ)
                else {
                    val cname = internalize(if (moduleName != null) "$moduleName" else ast.name) + "/Module"
                    Expr.Var(name, cname, typ)
                }
            }
            is CExpr.Lambda -> Expr.Lambda(binder, body.convert(locals), typ)
            is CExpr.App -> Expr.App(fn.convert(locals), arg.convert(locals), typ)
            is CExpr.If -> Expr.If(cond.convert(locals), thenCase.convert(locals), elseCase.convert(locals), typ)
            is CExpr.Let -> {
                val num = localsCount++
                val local = letDef.name to num
                Expr.Let(letDef.convert(num, locals), body.convert(locals + local), typ)
            }
            is CExpr.Ann -> exp.convert(locals)
            is CExpr.Do -> Expr.Do(exps.map { it.convert(locals) }, typ)
            is CExpr.Match -> internalError("not yet implemented")
        }
    }

    private fun CLetDef.convert(num: Int, locals: Map<String, Int>): LetDef = LetDef(name, num, expr.convert(locals))

    private fun TType.convert(): Type = when (this) {
        is TType.TVar -> {
            if (name[0].isLowerCase()) Type.TVar(originalName(name).toUpperCase(), true)
            else Type.TVar(getPrimitiveTypeName(this))
        }
        is TType.TConstructor -> {
            val modName = module ?: ast.name
            Type.TConstructor(internalize("$modName.$name"), types.map { it.convert() })
        }
        is TType.TFun -> Type.TFun(arg.convert(), ret.convert())
        is TType.TForall -> type.convert()
        is TType.TMeta -> internalError("got TMeta after type checking")
    }

    // TODO: use primitive types and implement autoboxing
    private fun getPrimitiveTypeName(tvar: TType.TVar): String = when (tvar.name) {
        "Byte" -> "java/lang/Byte"
        "Short" -> "java/lang/Short"
        "Int" -> "java/lang/Integer"
        "Long" -> "java/lang/Long"
        "Float" -> "java/lang/Float"
        "Double" -> "java/lang/Double"
        "Boolean" -> "java/lang/Boolean"
        "Char" -> "java/lang/Character"
        "String" -> "java/lang/String"
        else -> {
            val modName = tvar.module ?: ast.name
            internalize("$modName.${tvar.name}")
        }
    }

    companion object {

        private fun internalize(name: String) = name.replace('.', '/')
    }
}
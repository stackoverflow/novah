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

    fun convert() = ast.convert()

    private fun CModule.convert(): Module {
        val ds = mutableListOf<Decl>()
        for (d in decls) {
            if (d is CDataDecl) ds += d.convert()
            if (d is CValDecl) ds += d.convert()
        }
        return Module(internalize(name), sourceName, ds)
    }

    private fun CValDecl.convert(): Decl.ValDecl = Decl.ValDecl(name, exp.convert(), visibility)

    private fun CDataDecl.convert(): Decl.DataDecl =
        Decl.DataDecl(name, tyVars, dataCtors.map { it.convert() }, visibility)

    private fun CDataConstructor.convert(): DataConstructor =
        DataConstructor(name, args.map { it.convert() }, visibility)

    private fun CExpr.convert(): Expr {
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
                val qname = if (moduleName != null) internalize("$moduleName") + ".$name" else name
                Expr.Var(qname, typ)
            }
            is CExpr.Lambda -> Expr.Lambda(binder, body.convert(), typ)
            is CExpr.App -> Expr.App(fn.convert(), arg.convert(), typ)
            is CExpr.If -> Expr.If(cond.convert(), thenCase.convert(), elseCase.convert(), typ)
            is CExpr.Let -> Expr.Let(letDef.convert(), body.convert(), typ)
            is CExpr.Ann -> exp.convert()
            is CExpr.Do -> Expr.Do(exps.map { it.convert() }, typ)
            is CExpr.Match -> internalError("not yet implemented")
        }
    }

    private fun CLetDef.convert(): LetDef = LetDef(name, expr.convert())

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
        "Char" -> "java/lang/Char"
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
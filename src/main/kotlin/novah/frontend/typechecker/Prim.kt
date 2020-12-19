package novah.frontend.typechecker

import novah.ast.source.DeclarationRef
import novah.ast.source.Import

/**
 * Primitive types and functions that should be always
 * in the context
 */
object Prim {

    private const val PRIM = "prim"

    // primitives
    val tInt = Type.TVar("$PRIM.Int")
    val tFloat = Type.TVar("$PRIM.Float")
    val tString = Type.TVar("$PRIM.String")
    val tChar = Type.TVar("$PRIM.Char")
    val tBoolean = Type.TVar("$PRIM.Boolean")

    val tUnit = Type.TVar("$PRIM.Unit")

    /**
     * All primitive types and functions
     * that should be auto-added to every
     * module.
     */
    val primImport = Import.Exposing(
        listOf(PRIM), listOf(
            DeclarationRef.RefType("Int"),
            DeclarationRef.RefType("Float"),
            DeclarationRef.RefType("String"),
            DeclarationRef.RefType("Char"),
            DeclarationRef.RefType("Boolean"),
            DeclarationRef.RefType("Unit"),
            DeclarationRef.RefVar("print"),
            DeclarationRef.RefVar("println"),
            DeclarationRef.RefVar("unit")
        )
    )


    fun addAll(ctx: Context) {
        ctx.addAll(primModule)
    }

    private val primModule = listOf(
        Elem.CTVar(tInt.name),
        Elem.CTVar(tFloat.name),
        Elem.CTVar(tString.name),
        Elem.CTVar(tChar.name),
        Elem.CTVar(tBoolean.name),
        Elem.CTVar(tUnit.name),
        Elem.CVar("$PRIM.print", Type.TFun(tString, tUnit)),
        Elem.CVar("$PRIM.println", Type.TFun(tString, tUnit)),
        Elem.CVar("$PRIM.unit", tUnit)
    )
}
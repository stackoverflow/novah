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
    val tByte = tvar("$PRIM.Byte")
    val tShort = tvar("$PRIM.Short")
    val tInt = tvar("$PRIM.Int")
    val tLong = tvar("$PRIM.Long")
    val tFloat = tvar("$PRIM.Float")
    val tDouble = tvar("$PRIM.Double")
    val tString = tvar("$PRIM.String")
    val tChar = tvar("$PRIM.Char")
    val tBoolean = tvar("$PRIM.Boolean")

    val tUnit = tvar("$PRIM.Unit")

    /**
     * All primitive types and functions
     * that should be auto-added to every
     * module.
     */
    val primImport = Import.Exposing(
        listOf(PRIM), listOf(
            DeclarationRef.RefType("Byte"),
            DeclarationRef.RefType("Short"),
            DeclarationRef.RefType("Int"),
            DeclarationRef.RefType("Long"),
            DeclarationRef.RefType("Float"),
            DeclarationRef.RefType("Double"),
            DeclarationRef.RefType("String"),
            DeclarationRef.RefType("Char"),
            DeclarationRef.RefType("Boolean"),
            DeclarationRef.RefType("Unit"),
            DeclarationRef.RefVar("println"),
            DeclarationRef.RefVar("toString"),
            DeclarationRef.RefVar("equals"),
            DeclarationRef.RefVar("unit")
        )
    )


    fun addAll(ctx: Context) {
        ctx.addAll(primModule)
    }

    private val primModule = listOf(
        Elem.CTVar(tByte.name),
        Elem.CTVar(tShort.name),
        Elem.CTVar(tInt.name),
        Elem.CTVar(tLong.name),
        Elem.CTVar(tFloat.name),
        Elem.CTVar(tDouble.name),
        Elem.CTVar(tString.name),
        Elem.CTVar(tChar.name),
        Elem.CTVar(tBoolean.name),
        Elem.CTVar(tUnit.name),
        Elem.CVar("$PRIM.println", tfun(tString, tUnit)),
        Elem.CVar("$PRIM.toString", tfall("a", tfun(tvar("a"), tString))),
        Elem.CVar("$PRIM.equals", tfall("a", tfun(tvar("a"), tfun(tvar("a"), tBoolean)))),
        Elem.CVar("$PRIM.unit", tUnit)
    )

    private fun tvar(x: String) = Type.TVar(x)
    private fun tfun(a: Type, b: Type) = Type.TFun(a, b)
    private fun tfall(v: String, t: Type) = Type.TForall(v, t)
}
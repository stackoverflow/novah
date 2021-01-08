package novah.frontend.typechecker

import novah.ast.canonical.Visibility
import novah.ast.source.Import
import novah.frontend.Span

/**
 * Primitive types and functions that should be always
 * in the context
 */
object Prim {

    const val PRIM = "prim"

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
    val primImport = Import.Raw(PRIM, Span.empty())

    private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC)
    private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

    val moduleEnv = ModuleEnv(
        mapOf(
            "println" to decl(tfun(tString, tUnit)),
            "toString" to decl(tfall("a", tfun(tvar("a"), tString))),
            "hashCode" to decl(tfall("a", tfun(tvar("a"), tInt))),
            "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
            "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
            "==" to decl(tfall("a", tfun(tvar("a"), tfun(tvar("a"), tBoolean)))),
            "unit" to decl(tUnit)
        ),
        mapOf(
            "Byte" to tdecl(tByte),
            "Short" to tdecl(tShort),
            "Int" to tdecl(tInt),
            "Long" to tdecl(tLong),
            "Float" to tdecl(tFloat),
            "Double" to tdecl(tDouble),
            "String" to tdecl(tString),
            "Char" to tdecl(tChar),
            "Boolean" to tdecl(tBoolean),
            "Unit" to tdecl(tUnit)
        )
    )

    private fun tvar(x: String) = Type.TVar(x.raw())
    private fun tfun(a: Type, b: Type) = Type.TFun(a, b)
    private fun tfall(v: String, t: Type) = Type.TForall(v.raw(), t)
}
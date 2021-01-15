package novah.frontend.typechecker

import novah.ast.canonical.Visibility
import novah.ast.source.Import
import novah.frontend.Span
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

/**
 * Primitive types and functions that should be always
 * in the context
 */
object Prim {

    const val PRIM = "prim"

    const val byte = "$PRIM.Byte"
    const val short = "$PRIM.Short"
    const val int = "$PRIM.Int"
    const val long = "$PRIM.Long"
    const val float = "$PRIM.Float"
    const val double = "$PRIM.Double"
    const val string = "$PRIM.String"
    const val char = "$PRIM.Char"
    const val boolean = "$PRIM.Boolean"
    const val unit = "$PRIM.Unit"
    
    // primitives
    val tByte = tvar(byte)
    val tShort = tvar(short)
    val tInt = tvar(int)
    val tLong = tvar(long)
    val tFloat = tvar(float)
    val tDouble = tvar(double)
    val tString = tvar(string)
    val tChar = tvar(char)
    val tBoolean = tvar(boolean)

    val tUnit = tvar(unit)
    
    fun javaToNovah(jname: String): String = when (jname) {
        "byte" -> byte
        "short" -> short
        "int" -> int
        "long" -> long
        "float" -> float
        "double" -> double
        "String" -> string
        "char" -> char
        "boolean" -> boolean
        else -> jname
    }

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
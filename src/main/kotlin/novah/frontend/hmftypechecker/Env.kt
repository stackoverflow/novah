package novah.frontend.hmftypechecker

import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Span
import novah.main.DeclRef
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

class Env(initial: Map<String, Type> = emptyMap(), types: Map<String, Type> = primTypes) {
    
    private val env = LinkedHashMap(initial)
    
    private val types = LinkedHashMap(types)
    
    fun extend(name: String, type: Type) {
        env[name] = type
    }
    
    fun lookup(name: String): Type? = env[name]
    
    fun makeExtension() = Env(env, types)
    
    fun extendType(name: String, type: Type) {
        types[name] = type
    }
    
    fun lookupType(name: String): Type? = types[name]
}

const val primByte = "prim.Byte"
const val primShort = "prim.Short"
const val primInt = "prim.Int"
const val primLong = "prim.Long"
const val primFloat = "prim.Float"
const val primDouble = "prim.Double"
const val primBoolean = "prim.Boolean"
const val primChar = "prim.Char"
const val primString = "prim.String"
const val primUnit = "prim.Unit"

val tByte = Type.TConst(primByte)
val tShort = Type.TConst(primShort)
val tInt = Type.TConst(primInt)
val tLong = Type.TConst(primLong)
val tFloat = Type.TConst(primFloat)
val tDouble = Type.TConst(primDouble)
val tBoolean = Type.TConst(primBoolean)
val tChar = Type.TConst(primChar)
val tString = Type.TConst(primString)
val tUnit = Type.TConst(primUnit)

val primTypes = mapOf<String, Type>(
    primByte to tByte,
    primShort to tShort,
    primInt to tInt,
    primLong to tLong,
    primFloat to tFloat,
    primDouble to tDouble,
    primBoolean to tBoolean,
    primChar to tChar,
    primString to tString,
    primUnit to tUnit
)

fun javaToNovah(jname: String): String = when (jname) {
    "byte" -> primByte
    "short" -> primShort
    "int" -> primInt
    "long" -> primLong
    "float" -> primFloat
    "double" -> primDouble
    "char" -> primChar
    "boolean" -> primBoolean
    "java.lang.String" -> primString
    "java.lang.Byte" -> primByte
    "java.lang.Short" -> primShort
    "java.lang.Integer" -> primInt
    "java.lang.Long" -> primLong
    "java.lang.Float" -> primFloat
    "java.lang.Double" -> primDouble
    "java.lang.Character" -> primChar
    else -> jname
}

val primImport = Import.Raw("prim", Span.empty())

private fun decl(type: Type) = DeclRef(type, Visibility.PUBLIC)
private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())

private fun tfun(a: Type, b: Type) = Type.TArrow(listOf(a), b)
private fun tfall(v: Id, t: Type) = Type.TForall(listOf(v), t)
private fun tbound(x: Id) = Type.TVar(TypeVar.Bound(x))

val moduleEnv = ModuleEnv(
    mapOf(
        "println" to decl(tfun(tString, tUnit)),
        // TODO: fix these negative numbers
        "toString" to decl(tfall(-1, tfun(tbound(-1), tString))),
        "hashCode" to decl(tfall(-2, tfun(tbound(-2), tInt))),
        "&&" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "||" to decl(tfun(tBoolean, tfun(tBoolean, tBoolean))),
        "==" to decl(tfall(-3, tfun(tbound(-3), tfun(tbound(-3), tBoolean))))
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
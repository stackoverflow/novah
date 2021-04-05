package novah.frontend.typechecker

import io.lacuna.bifurcan.Map
import novah.ast.source.Import
import novah.ast.source.Visibility
import novah.frontend.Span
import novah.main.ModuleEnv
import novah.main.TypeDeclRef

data class InstanceEnv(val type: Type, val isVar: Boolean = false)

class Env private constructor(
    private val env: Map<String, Type>,
    private val types: Map<String, Type>,
    private val instances: Map<String, InstanceEnv>
) {

    fun extend(name: String, type: Type): Env {
        env.put(name, type)
        return this
    }

    fun lookup(name: String): Type? = env.get(name, null)

    fun remove(name: String) {
        env.remove(name)
    }

    fun fork() = Env(env.forked().linear(), types.forked().linear(), instances.forked().linear())

    fun extendType(name: String, type: Type): Env {
        types.put(name, type)
        return this
    }

    fun lookupType(name: String): Type? = types.get(name, null)

    fun extendInstance(name: String, type: Type, isVar: Boolean = false): Env {
        instances.put(name, InstanceEnv(type, isVar))
        return this
    }

    fun forEachInstance(action: (String, InstanceEnv) -> Unit) {
        instances.forEach { action(it.key(), it.value()) }
    }

    companion object {
        fun new() = Env(Map<String, Type>().linear(), Map.from(primTypes).linear(), Map<String, InstanceEnv>().linear())
    }
}

const val PRIM = "prim"
const val CORE_MODULE = "novah.core"

const val primByte = "$PRIM.Byte"
const val primInt16 = "$PRIM.Int16"
const val primInt32 = "$PRIM.Int32"
const val primInt64 = "$PRIM.Int64"
const val primFloat32 = "$PRIM.Float32"
const val primFloat64 = "$PRIM.Float64"
const val primBoolean = "$PRIM.Boolean"
const val primChar = "$PRIM.Char"
const val primString = "$PRIM.String"
const val primUnit = "$PRIM.Unit"
const val primObject = "$PRIM.Object"
const val primVector = "$PRIM.Vector"
const val primSet = "$PRIM.Set"
const val primArray = "$PRIM.Array"
const val primByteArray = "$PRIM.ByteArray"
const val primInt16Array = "$PRIM.Int16Array"
const val primInt32Array = "$PRIM.Int32Array"
const val primInt64Array = "$PRIM.Int64Array"
const val primFloat32Array = "$PRIM.Float32Array"
const val primFloat64Array = "$PRIM.Float64Array"
const val primBooleanArray = "$PRIM.BooleanArray"
const val primCharArray = "$PRIM.CharArray"

val tByte = TConst(primByte)
val tInt16 = TConst(primInt16)
val tInt32 = TConst(primInt32)
val tInt64 = TConst(primInt64)
val tFloat32 = TConst(primFloat32)
val tFloat64 = TConst(primFloat64)
val tBoolean = TConst(primBoolean)
val tChar = TConst(primChar)
val tString = TConst(primString)
val tObject = TConst(primObject)
val tUnit = TConst(primUnit)
val tVector = TApp(TConst(primVector, Kind.Constructor(1)), listOf(tbound(-1)))
val tSet = TApp(TConst(primSet, Kind.Constructor(1)), listOf(tbound(-2)))
val tArray = TApp(TConst(primArray, Kind.Constructor(1)), listOf(tbound(-3)))
val tByteArray = TConst(primByteArray)
val tInt16Array = TConst(primInt16Array)
val tInt32Array = TConst(primInt32Array)
val tInt64Array = TConst(primInt64Array)
val tFloat32Array = TConst(primFloat32Array)
val tFloat64Array = TConst(primFloat64Array)
val tBooleanArray = TConst(primBooleanArray)
val tCharArray = TConst(primCharArray)

val tUnsafeCoerce = TArrow(listOf(tbound(-4)), tbound(-5))

val primTypes = mapOf(
    primByte to tByte,
    primInt16 to tInt16,
    primInt32 to tInt32,
    primInt64 to tInt64,
    primFloat32 to tFloat32,
    primFloat64 to tFloat64,
    primBoolean to tBoolean,
    primChar to tChar,
    primString to tString,
    primObject to tObject,
    primUnit to tUnit,
    primVector to tVector,
    primSet to tSet,
    primArray to tArray,
    primByteArray to tByteArray,
    primInt16Array to tInt16Array,
    primInt32Array to tInt32Array,
    primInt64Array to tInt64Array,
    primFloat32Array to tFloat32Array,
    primFloat64Array to tFloat64Array,
    primBooleanArray to tBooleanArray,
    primCharArray to tCharArray
)

fun isVectorOf(type: Type, of: Type) =
    type is TApp && type.type is TConst && type.type.name == primVector && type.types[0] == of

fun isSetOf(type: Type, of: Type) =
    type is TApp && type.type is TConst && type.type.name == primSet && type.types[0] == of

fun javaToNovah(jname: String): String = when (jname) {
    "byte" -> primByte
    "short" -> primInt16
    "int" -> primInt32
    "long" -> primInt64
    "float" -> primFloat32
    "double" -> primFloat64
    "char" -> primChar
    "boolean" -> primBoolean
    "java.lang.String" -> primString
    "java.lang.Byte" -> primByte
    "java.lang.Short" -> primInt16
    "java.lang.Integer" -> primInt32
    "java.lang.Long" -> primInt64
    "java.lang.Float" -> primFloat32
    "java.lang.Double" -> primFloat64
    "java.lang.Character" -> primChar
    "java.lang.Boolean" -> primBoolean
    "java.lang.Object" -> primObject
    "io.lacuna.bifurcan.List" -> primVector
    "io.lacuna.bifurcan.Set" -> primSet
    else -> {
        if (jname.endsWith("[]")) {
            when (jname.replace("[]", "")) {
                "byte" -> primByteArray
                "short" -> primInt16Array
                "int" -> primInt32Array
                "long" -> primInt64Array
                "float" -> primFloat32Array
                "double" -> primFloat64Array
                "char" -> primCharArray
                "boolean" -> primBooleanArray
                else -> primArray
            }
        } else jname
    }
}

val primImport = Import.Raw(PRIM, Span.empty())
val coreImport = Import.Raw(CORE_MODULE, Span.empty())

private fun tdecl(type: Type) = TypeDeclRef(type, Visibility.PUBLIC, emptyList())
private fun tbound(x: Id) = TVar(TypeVar.Generic(x))

val primModuleEnv = ModuleEnv(
    mapOf(),
    mapOf(
        "Byte" to tdecl(tByte),
        "Int16" to tdecl(tInt16),
        "Int32" to tdecl(tInt32),
        "Int64" to tdecl(tInt64),
        "Float32" to tdecl(tFloat32),
        "Float64" to tdecl(tFloat64),
        "String" to tdecl(tString),
        "Char" to tdecl(tChar),
        "Boolean" to tdecl(tBoolean),
        "Unit" to tdecl(tUnit),
        "Object" to tdecl(tObject),
        "Vector" to tdecl(tVector),
        "Set" to tdecl(tSet),
        "Array" to tdecl(tArray),
        "ByteArray" to tdecl(tByteArray),
        "Int16Array" to tdecl(tInt16Array),
        "Int32Array" to tdecl(tInt32Array),
        "Int64Array" to tdecl(tInt64Array),
        "Float32Array" to tdecl(tFloat32Array),
        "Float64Array" to tdecl(tFloat64Array),
        "CharArray" to tdecl(tCharArray),
        "BooleanArray" to tdecl(tBooleanArray),
    )
)
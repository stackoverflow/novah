package novah.frontend.typechecker

/**
 * Primitive types and functions that should be always
 * in the context
 */
object Prim {

    // primitives
    val tInt = Type.TVar("Int")
    val tFloat = Type.TVar("Float")
    val tString = Type.TVar("String")
    val tChar = Type.TVar("Char")
    val tBoolean = Type.TVar("Boolean")

    val tUnit = Type.TVar("Unit")

    private val primModule = listOf(
        Elem.CTVar(tInt.name),
        Elem.CTVar(tFloat.name),
        Elem.CTVar(tString.name),
        Elem.CTVar(tChar.name),
        Elem.CTVar(tBoolean.name),
        Elem.CTVar(tUnit.name),
        Elem.CVar("print", Type.TFun(tString, tUnit)),
        Elem.CVar("println", Type.TFun(tString, tUnit))
    )

    fun addAll(ctx: Context) {
        ctx.addAll(primModule)
    }
}
package novah.frontend.typechecker

import novah.ast.canonical.Expr
import novah.frontend.Span
import java.lang.RuntimeException
import java.util.ArrayDeque

class InferenceError(msg: String) : RuntimeException(msg)

fun inferError(msg: String, expr: Expr? = null): Nothing {
    val message = if (expr != null) "$msg at ${expr.span}" else msg
    throw InferenceError(message)
}

object InferContext {
    var context = Context()
    val store = GenNameStore()

    // primitives
    val tInt = Type.TVar("Int")
    val tFloat = Type.TVar("Float")
    val tString = Type.TVar("String")
    val tChar = Type.TVar("Char")
    val tBoolean = Type.TVar("Boolean")

    private val ctxStack = ArrayDeque<Context>()

    fun initDefaultContext() {
        context.add(Elem.CTVar("Int"))
        context.add(Elem.CTVar("Float"))
        context.add(Elem.CTVar("String"))
        context.add(Elem.CTVar("Char"))
        context.add(Elem.CTVar("Boolean"))
    }

    fun store() {
        ctxStack.push(context.clone())
    }

    fun restore() {
        context = if (ctxStack.isNotEmpty()) ctxStack.pop() else context
    }

    fun discard() {
        if (ctxStack.isNotEmpty()) ctxStack.pop()
    }

    fun _apply(type: Type, ctx: Context = context): Type {
        return when (type) {
            is Type.TVar -> type
            is Type.TMeta -> {
                val t = ctx.lookup<Elem.CTMeta>(type.name)
                if (t?.type != null) _apply(t.type, ctx) else type
            }
            is Type.TFun -> {
                val left = _apply(type.arg, ctx)
                val right = _apply(type.ret, ctx)
                if (type.arg == left && type.ret == right) {
                    type
                } else {
                    Type.TFun(left, right)
                }
            }
            is Type.TForall -> {
                val body = _apply(type.type, ctx)
                if (type.type == body) type else Type.TForall(type.name, body)
            }
            is Type.TConstructor -> {
                val body = type.types.map { _apply(it, ctx) }
                if (type.types == body) type else Type.TConstructor(type.name, body)
            }
        }
    }
}
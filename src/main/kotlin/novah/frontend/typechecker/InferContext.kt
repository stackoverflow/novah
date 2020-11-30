package novah.frontend.typechecker

import novah.frontend.Expr
import java.lang.RuntimeException
import java.util.ArrayDeque

class InferenceError(msg: String) : RuntimeException(msg)

fun inferError(msg: String, expr: Expr? = null): Nothing {
    val _msg = if (expr != null) "$msg at ${expr.span}" else msg
    throw InferenceError(_msg)
}

object InferContext {
    var context = Context()
    val store = GenNameStore()

    private val ctxStack = ArrayDeque<Context>()

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
            is Type.TInt -> type
            is Type.TFloat -> type
            is Type.TString -> type
            is Type.TChar -> type
            is Type.TBoolean -> type
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
        }
    }
}
package novah.frontend.typechecker

import novah.ast.canonical.Expr
import novah.frontend.Span
import java.lang.RuntimeException
import java.util.ArrayDeque

class InferenceError(msg: String) : RuntimeException(msg)

fun inferError(msg: String, expr: Expr? = null): Nothing = inferError(msg, expr?.span)

fun inferError(msg: String, span: Span?): Nothing {
    val message = if (span != null) "$msg at $span" else msg
    throw InferenceError(message)
}

class InferContext {
    var context = Context()
    private val store = GenNameStore()
    
    private val wellFormed = WellFormed(store, this)
    private val subsumption = Subsumption(wellFormed, store, this)
    val infer = Inference(subsumption, wellFormed, store, this)

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

    fun apply(type: Type, ctx: Context = context): Type {
        return when (type) {
            is Type.TVar -> type
            is Type.TMeta -> {
                val t = ctx.lookup<Elem.CTMeta>(type.name)
                if (t?.type != null) apply(t.type, ctx) else type
            }
            is Type.TFun -> {
                val left = apply(type.arg, ctx)
                val right = apply(type.ret, ctx)
                if (type.arg == left && type.ret == right) {
                    type
                } else {
                    Type.TFun(left, right)
                }
            }
            is Type.TForall -> {
                val body = apply(type.type, ctx)
                if (type.type == body) type else Type.TForall(type.name, body)
            }
            is Type.TConstructor -> {
                val body = type.types.map { apply(it, ctx) }
                if (type.types == body) type else Type.TConstructor(type.name, body)
            }
        }
    }
}
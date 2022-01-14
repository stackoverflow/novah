package novah.optimize

import novah.ast.canonical.Decl
import novah.ast.canonical.Expr
import novah.ast.canonical.Metadata
import novah.ast.source.Visibility
import novah.data.singletonPMap
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.error.Errors

object AutoDerive {

    private val classes = setOf("Show", "Equals")

    /**
     * Auto derive some type classes based on metadata.
     */
    fun derive(decl: Decl.TypeDecl, onError: (String, Span) -> Unit): List<Decl>? {
        val meta = decl.metadata ?: return null
        val deri = meta.getMeta(Metadata.DERIVE) ?: return null

        if (decl.tyVars.isNotEmpty()) {
            onError(Errors.DERIVE_UNKNOW, meta.data.span)
            return null
        }

        if (deri !is Expr.ListLiteral) {
            onError(Errors.DERIVE_LIST, deri.span)
            return null
        }
        val deriveExps = deri.exps
        if (deriveExps.isEmpty()) return null
        if (deriveExps[0] !is Expr.StringE) {
            onError(Errors.DERIVE_LIST, deri.span)
            return null
        }

        val derives = deriveExps.filterIsInstance<Expr.StringE>()
        return derives.mapNotNull {
            when (it.v) {
                "Show" -> makeShow(it.span, decl)
                "Equals" -> makeEquals(it.span, decl)
                else -> {
                    onError(Errors.invalidAutoDerive(it.v, classes), it.span)
                    null
                }
            }
        }
    }

    private fun makeShow(span: Span, decl: Decl.TypeDecl): Decl {
        val toStr = Expr.Var("toString", span, "novah.core")
        val show = Expr.Constructor("Show", span, "novah.core")
        return makeDecl("\$show${decl.name.value}", span, decl.visibility, Expr.App(show, toStr, span))
    }

    private fun makeEquals(span: Span, decl: Decl.TypeDecl): Decl {
        val equals = Expr.Var("equals", span, "novah.java")
        val eq = Expr.Constructor("Equals", span, "novah.core")
        return makeDecl("\$equals${decl.name.value}", span, decl.visibility, Expr.App(eq, equals, span))
    }

    private fun makeDecl(name: String, span: Span, vis: Visibility, exp: Expr): Decl {
        val labels = singletonPMap(Metadata.NO_WARN, Expr.Bool(true, span) as Expr)
        val meta = Metadata(Expr.RecordExtend(labels, Expr.RecordEmpty(span), span))
        return Decl.ValDecl(
            Spanned(span, name), exp, false, span, null, vis,
            isInstance = true,
            isOperator = false
        ).withMeta(meta)
    }
}
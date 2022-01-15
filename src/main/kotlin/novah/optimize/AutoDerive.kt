package novah.optimize

import novah.ast.source.*
import novah.frontend.Span
import novah.frontend.Spanned
import novah.frontend.error.Errors
import novah.ast.canonical.Metadata as MData

object AutoDerive {

    private val classes = setOf("Show", "Equals")

    /**
     * Auto derive some type classes based on metadata.
     */
    fun derive(decl: Decl.TypeDecl, onError: (String, Span) -> Unit): List<Decl>? {
        val meta = decl.metadata ?: return null
        val deri = meta.getMeta(MData.DERIVE) ?: return null

        if (deri !is Expr.RecordExtend) {
            onError(Errors.DERIVE_REC, deri.span)
            return null
        }
        val deriveExps = deri.labels
        if (deriveExps.isEmpty()) return null
        if (deriveExps.any { it.second !is Expr.StringE }) {
            onError(Errors.DERIVE_REC, deri.span)
            return null
        }

        return deriveExps.mapNotNull {
            val v = it.second as Expr.StringE
            if (Names.isValidNovahIdent(it.first)) {
                when (v.v) {
                    "Show" -> makeShow(it.first, v.span, decl)
                    "Equals" -> makeEquals(it.first, v.span, decl)
                    else -> {
                        onError(Errors.invalidAutoDerive(v.v, classes), v.span)
                        null
                    }
                }
            } else {
                onError(Errors.invalidIdent(it.first), deri.span)
                null
            }
        }
    }

    private fun makeShow(name: String, span: Span, decl: Decl.TypeDecl): Decl {
        val tyVars = decl.tyVars
        // function signature
        val retType = makeApp("Show", makeType(decl, span), span) as Type
        val tlist = tyVars.map { Type.TImplicit(makeApp("Show", Type.TConst(it, span = span), span), span) }
        val fullType = tlist.foldRight(retType) { acc, tApp -> Type.TFun(acc, tApp, span) }
        val sig = Signature(fullType, span)

        // lambda patterns
        val pars = if (tyVars.isEmpty()) emptyList<Pattern>()
        else List(tyVars.size) { i ->
            val ctor = Pattern.Ctor(Expr.Constructor("Show"), listOf(Pattern.Var(Expr.Var("\$sh$i"))), span)
            Pattern.ImplicitPattern(ctor, span)
        }

        val varMap = tyVars.mapIndexed { i, s -> s to i }.toMap()
        val cases = decl.dataCtors.map { ctor ->
            val vars = ctor.args
            val pat = Pattern.Ctor(Expr.Constructor(ctor.name.value), List(vars.size) { i ->
                Pattern.Var(Expr.Var("\$x$i"))
            }, span)
            Case(listOf(pat), makeShowFormat(ctor, varMap, span))
        }

        val match = Expr.Match(listOf(Expr.Var("\$x")), cases)
        val lambda = Expr.Lambda(listOf(varPattern("\$x")), match)
        val exp = Expr.App(Expr.Constructor("Show"), lambda).withSpan(span)
        return makeDecl(name, span, decl.visibility, pars, exp, sig)
    }

    private fun makeEquals(name: String, span: Span, decl: Decl.TypeDecl): Decl {
        val tyVars = decl.tyVars
        // function signature
        val retType = makeApp("Equals", makeType(decl, span), span) as Type
        val tlist = tyVars.map { Type.TImplicit(makeApp("Equals", Type.TConst(it, span = span), span), span) }
        val fullType = tlist.foldRight(retType) { acc, tApp -> Type.TFun(acc, tApp, span) }
        val sig = Signature(fullType, span)

        // lambda patterns
        val pars = if (tyVars.isEmpty()) emptyList<Pattern>()
        else List(tyVars.size) { i ->
            val ctor = Pattern.Ctor(Expr.Constructor("Equals"), listOf(Pattern.Var(Expr.Var("\$eq$i"))), span)
            Pattern.ImplicitPattern(ctor, span)
        }

        val varMap = tyVars.mapIndexed { i, s -> s to i }.toMap()
        val cases = decl.dataCtors.map { ctor ->
            val vars = ctor.args
            val pat1 = Pattern.Ctor(Expr.Constructor(ctor.name.value), List(vars.size) { i ->
                Pattern.Var(Expr.Var("\$x$i"))
            }, span)
            val pat2 = Pattern.Ctor(Expr.Constructor(ctor.name.value), List(vars.size) { i ->
                Pattern.Var(Expr.Var("\$y$i"))
            }, span)
            Case(listOf(pat1, pat2), makeComparison(ctor, varMap, span))
        } + Case(listOf(Pattern.Wildcard(span), Pattern.Wildcard(span)), Expr.Bool(false))

        val match = Expr.Match(listOf(Expr.Var("\$x"), Expr.Var("\$y")), cases)
        val lambda = Expr.Lambda(listOf(varPattern("\$x"), varPattern("\$y")), match)
        val exp = Expr.App(Expr.Constructor("Equals"), lambda).withSpan(span)
        return makeDecl(name, span, decl.visibility, pars, exp, sig)
    }

    private fun makeDecl(
        name: String,
        span: Span,
        vis: Visibility,
        pars: List<Pattern>,
        exp: Expr,
        sig: Signature
    ): Decl =
        Decl.ValDecl(Spanned(span, name), pars, exp, sig, vis, isInstance = true, isOperator = false).withSpan(span)

    private fun makeShowFormat(ctor: DataConstructor, varMap: Map<String, Int>, span: Span): Expr {
        val name = ctor.name.value
        if (ctor.args.isEmpty()) return Expr.StringE(name, name).withSpan(span)

        val vars = ctor.args.mapIndexed { i, it ->
            val fn = Expr.Var(if (it.isTypeVar()) "\$sh${varMap[it.simpleName()]}" else "show").withSpan(span)
            val x = Expr.Var("\$x$i").withSpan(span)
            Expr.App(fn, x).withSpan(span)
        }
        val format = List(ctor.args.size) { "%s" }.joinToString(" ", prefix = "($name ", postfix = ")")
        val fnCall = Expr.App(Expr.Var("format").withSpan(span), Expr.StringE(format, format)).withSpan(span)
        return Expr.App(fnCall, Expr.ListLiteral(vars).withSpan(span)).withSpan(span)
    }

    private fun makeComparison(ctor: DataConstructor, varMap: Map<String, Int>, span: Span): Expr {
        if (ctor.args.isEmpty()) return Expr.Bool(true).withSpan(span)

        return ctor.args.mapIndexed { i, it ->
            if (it.isTypeVar()) {
                val eq = Expr.Var("\$eq${varMap[it.simpleName()]}").withSpan(span)
                val ap1 = Expr.App(eq, Expr.Var("\$x$i").withSpan(span)).withSpan(span)
                Expr.App(ap1, Expr.Var("\$y$i").withSpan(span)).withSpan(span)
            } else {
                val op = Expr.Operator("==", false).withSpan(span)
                Expr.BinApp(op, Expr.Var("\$x$i").withSpan(span), Expr.Var("\$y$i").withSpan(span)).withSpan(span)
            }
        }.reduce { acc, expr ->
            Expr.BinApp(Expr.Operator("&&", false).withSpan(span), acc, expr).withSpan(span)
        }
    }

    private fun makeType(decl: Decl.TypeDecl, span: Span): Type {
        val fqn = Type.TConst(decl.name, span = span)
        return if (decl.tyVars.isEmpty()) fqn
        else Type.TApp(fqn, decl.tyVars.map { Type.TConst(it, span = span) }, span)
    }

    private fun makeApp(name: String, of: Type, span: Span) =
        Type.TApp(Type.TConst(name, span = span), listOf(of), span)

    private fun varPattern(name: String) = Pattern.Var(Expr.Var(name))
}
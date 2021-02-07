package novah.ast

import novah.Util.internalError
import novah.ast.canonical.*
import novah.ast.source.Decl.TypealiasDecl
import novah.ast.source.ForeignRef
import novah.ast.source.fullname
import novah.data.*
import novah.data.Reflection.isStatic
import novah.frontend.ParserError
import novah.frontend.Span
import novah.frontend.error.CompilerProblem
import novah.frontend.error.ProblemContext
import novah.frontend.hmftypechecker.Id
import novah.frontend.hmftypechecker.Kind
import novah.frontend.hmftypechecker.Type
import novah.frontend.hmftypechecker.Typechecker
import novah.frontend.validatePublicAliases
import novah.main.CompilationError
import kotlin.math.max
import novah.ast.source.Binder as SBinder
import novah.ast.source.Case as SCase
import novah.ast.source.DataConstructor as SDataConstructor
import novah.ast.source.Decl as SDecl
import novah.ast.source.Expr as SExpr
import novah.ast.source.FunparPattern as SFunparPattern
import novah.ast.source.LetDef as SLetDef
import novah.ast.source.LiteralPattern as SLiteralPattern
import novah.ast.source.Module as SModule
import novah.ast.source.Pattern as SPattern
import novah.ast.source.Type as SType
import novah.frontend.error.Errors as E

/**
 * Converts a source AST to the canonical spanned AST
 */
class Desugar(private val smod: SModule, private val tc: Typechecker) {

    private val imports = smod.resolvedImports
    private val moduleName = smod.name
    private var synonyms = emptyMap<String, TypealiasDecl>()

    fun desugar(): Result<Module, List<CompilerProblem>> {
        return try {
            synonyms = validateTypealiases()
            val decls = validateTopLevelValues(smod.decls.mapNotNull { it.desugar() })
            Ok(Module(moduleName, smod.sourceName, decls))
        } catch (pe: ParserError) {
            Err(listOf(CompilerProblem(pe.msg, ProblemContext.DESUGAR, pe.span, smod.sourceName, smod.name)))
        } catch (ce: CompilationError) {
            Err(ce.problems)
        }
    }

    private val declVars = mutableSetOf<String>()

    private fun SDecl.desugar(): Decl? = when (this) {
        is SDecl.DataDecl -> {
            validateDataConstructorNames(this)
            if (smod.foreignTypes[name] != null || imports[name] != null) {
                parserError(E.duplicatedType(name), span)
            }
            Decl.DataDecl(name, tyVars, dataCtors.map { it.desugar() }, span, visibility)
        }
        is SDecl.ValDecl -> {
            declVars.clear()
            var expr = nestLambdas(patterns.map { it.desugar() }, exp.desugar())

            // if the declaration has a type annotation, annotate it
            expr = if (type != null) {
                val ann = if (type is SType.TForall) replaceConstantsWithVars(type.names, type.type.desugar())
                else emptyList<Id>() to type.desugar()
                Expr.Ann(expr, ann, span)
            } else expr
            Decl.ValDecl(name, expr, name in declVars, span, type?.desugar(), visibility)
        }
        else -> null
    }

    private fun SDataConstructor.desugar(): DataConstructor =
        DataConstructor(name, args.map { it.desugar() }, visibility, span)

    private fun SExpr.desugar(locals: List<String> = listOf(), appFnDepth: Int = 0): Expr = when (this) {
        is SExpr.IntE -> Expr.IntE(v, span)
        is SExpr.LongE -> Expr.LongE(v, span)
        is SExpr.FloatE -> Expr.FloatE(v, span)
        is SExpr.DoubleE -> Expr.DoubleE(v, span)
        is SExpr.StringE -> Expr.StringE(v, span)
        is SExpr.CharE -> Expr.CharE(v, span)
        is SExpr.Bool -> Expr.Bool(v, span)
        is SExpr.Var -> {
            declVars += name
            if (name in locals) Expr.Var(name, span)
            else {
                val foreign = smod.foreignVars[name]
                if (foreign != null) {
                    // this var is a native call/field
                    val exp = when (foreign) {
                        is ForeignRef.FieldRef -> {
                            val isStatic = isStatic(foreign.field)
                            if (foreign.isSetter) {
                                Expr.NativeFieldSet(name, foreign.field, isStatic, span)
                            } else Expr.NativeFieldGet(name, foreign.field, isStatic, span)
                        }
                        is ForeignRef.MethodRef -> {
                            Expr.NativeMethod(name, foreign.method, isStatic(foreign.method), span)
                        }
                        is ForeignRef.CtorRef -> {
                            Expr.NativeConstructor(name, foreign.ctor, span)
                        }
                    }
                    validateNativeCall(exp, appFnDepth)
                    exp
                } else Expr.Var(name, span, imports[fullname()])
            }
        }
        is SExpr.Operator -> Expr.Var(name, span, imports[fullname()])
        is SExpr.Constructor -> Expr.Constructor(name, span, imports[fullname()])
        is SExpr.Lambda -> {
            val names = patterns.filterIsInstance<SFunparPattern.Bind>().map { it.binder.name }
            nestLambdas(patterns.map { it.desugar() }, body.desugar(locals + names))
        }
        is SExpr.App -> Expr.App(fn.desugar(locals, appFnDepth + 1), arg.desugar(locals), span)
        is SExpr.Parens -> exp.desugar(locals)
        is SExpr.If -> Expr.If(cond.desugar(locals), thenCase.desugar(locals), elseCase.desugar(locals), span)
        is SExpr.Let -> nestLets(letDefs, body.desugar(locals + letDefs.map { it.name.name }), locals)
        is SExpr.Match -> Expr.Match(exp.desugar(locals), cases.map { it.desugar(locals) }, span)
        is SExpr.Ann -> {
            val ann = if (type is SType.TForall) replaceConstantsWithVars(type.names, type.type.desugar())
            else emptyList<Id>() to type.desugar()
            Expr.Ann(exp.desugar(locals), ann, span)
        }
        is SExpr.Do -> {
            if (exps.last() is SExpr.DoLet) parserError(E.LET_DO_LAST, exps.last().span)
            val converted = convertDoLets(exps)
            Expr.Do(converted.map { it.desugar(locals) }, span)
        }
        is SExpr.Unit -> Expr.Unit(span)
        is SExpr.DoLet -> internalError("got `do-let` outside of do statement: $this")
    }

    private fun SCase.desugar(locals: List<String>): Case = Case(pattern.desugar(locals), exp.desugar())

    private fun SPattern.desugar(locals: List<String>): Pattern = when (this) {
        is SPattern.Wildcard -> Pattern.Wildcard(span)
        is SPattern.LiteralP -> Pattern.LiteralP(lit.desugar(locals), span)
        is SPattern.Var -> Pattern.Var(name, span)
        is SPattern.Ctor -> Pattern.Ctor(
            ctor.desugar(locals) as Expr.Constructor,
            fields.map { it.desugar(locals) },
            span
        )
        is SPattern.Parens -> pattern.desugar(locals)
    }

    private fun SLiteralPattern.desugar(locals: List<String>): LiteralPattern = when (this) {
        is SLiteralPattern.BoolLiteral -> LiteralPattern.BoolLiteral(e.desugar(locals) as Expr.Bool)
        is SLiteralPattern.CharLiteral -> LiteralPattern.CharLiteral(e.desugar(locals) as Expr.CharE)
        is SLiteralPattern.StringLiteral -> LiteralPattern.StringLiteral(e.desugar(locals) as Expr.StringE)
        is SLiteralPattern.IntLiteral -> LiteralPattern.IntLiteral(e.desugar(locals) as Expr.IntE)
        is SLiteralPattern.LongLiteral -> LiteralPattern.LongLiteral(e.desugar(locals) as Expr.LongE)
        is SLiteralPattern.FloatLiteral -> LiteralPattern.FloatLiteral(e.desugar(locals) as Expr.FloatE)
        is SLiteralPattern.DoubleLiteral -> LiteralPattern.DoubleLiteral(e.desugar(locals) as Expr.DoubleE)
    }

    private fun SLetDef.desugar(locals: List<String>): LetDef {
        fun go(binders: List<FunparPattern>, exp: Expr): Expr {
            return if (binders.size == 1) Expr.Lambda(binders[0], null, exp, exp.span)
            else Expr.Lambda(binders[0], null, go(binders.drop(1), exp), exp.span)
        }

        return if (patterns.isEmpty()) {
            LetDef(name.desugar(), expr.desugar(locals), false, type?.desugar())
        } else {
            val exp = expr.desugar(locals)
            val vars = collectVars(exp)
            val recursive = name.name in vars
            LetDef(name.desugar(), go(patterns.map { it.desugar() }, exp), recursive, type?.desugar())
        }
    }

    private fun SBinder.desugar(): Binder = Binder(name, span)

    private fun SFunparPattern.desugar(): FunparPattern = when (this) {
        is SFunparPattern.Ignored -> FunparPattern.Ignored(span)
        is SFunparPattern.Unit -> FunparPattern.Unit(span)
        is SFunparPattern.Bind -> FunparPattern.Bind(binder.desugar())
    }

    private fun SType.desugar(): Type {
        val ty = resolveAliases(this)
        return ty.goDesugar()
    }

    private fun SType.goDesugar(kindArity: Int = 0): Type = when (this) {
        is SType.TConst -> {
            val kind = if (kindArity == 0) Kind.Star else Kind.Constructor(kindArity)
            if (name[0].isLowerCase()) Type.TConst(name, kind).span(span)
            else {
                val varName = smod.foreignTypes[name] ?: (imports[fullname()] ?: moduleName) + ".$name"
                Type.TConst(varName, kind).span(span)
            }
        }
        is SType.TFun -> Type.TArrow(listOf(arg.goDesugar()), ret.goDesugar()).span(span)
        is SType.TForall -> {
            val (ids, ty) = replaceConstantsWithVars(names, type.goDesugar(kindArity))
            Type.TForall(ids, ty).span(span)
        }
        is SType.TParens -> type.goDesugar(kindArity)
        is SType.TApp -> Type.TApp(type.goDesugar(types.size), types.map { it.goDesugar() }).span(span)
    }

    /**
     * Normalizes a forall type:
     * forall c b a. a -> b
     * to
     * forall a b. a -> b
     */
    private fun replaceConstantsWithVars(vars: List<String>, type: Type): Pair<List<Id>, Type> {
        val map = mutableMapOf<String, Type.TVar?>()
        val ids = mutableListOf<Id>()
        vars.forEach { map[it] = null }

        fun go(t: Type): Type = when (t) {
            is Type.TConst -> {
                if (map.containsKey(t.name)) {
                    val vvar = map[t.name]
                    if (vvar != null) vvar
                    else {
                        val (id, nvar) = tc.newBoundVar()
                        ids += id
                        map[t.name] = nvar
                        nvar
                    }
                } else t
            }
            is Type.TVar -> t
            is Type.TApp -> {
                val newType = go(t.type)
                val pars = t.types.map(::go)
                Type.TApp(newType, pars).span(t.span)
            }
            is Type.TArrow -> {
                val pars = t.args.map(::go)
                val ret = go(t.ret)
                Type.TArrow(pars, ret).span(t.span)
            }
            is Type.TForall -> Type.TForall(t.ids, go(t.type)).span(t.span)
        }
        return ids to go(type)
    }

    private fun nestLambdas(binders: List<FunparPattern>, exp: Expr): Expr {
        return if (binders.isEmpty()) exp
        else Expr.Lambda(binders[0], null, nestLambdas(binders.drop(1), exp), exp.span)
    }

    private fun nestLets(defs: List<SLetDef>, exp: Expr, locals: List<String>): Expr {
        return if (defs.isEmpty()) exp
        else Expr.Let(defs[0].desugar(locals), nestLets(defs.drop(1), exp, locals), exp.span)
    }

    private fun collectVars(exp: Expr): List<String> =
        exp.everywhereAccumulating { e -> if (e is Expr.Var) listOf(e.name) else emptyList() }

    /**
     * Expand a type alias and make sure they are not recursive
     */
    private fun validateTypealiases(): Map<String, TypealiasDecl> {
        val typealiases = smod.decls.filterIsInstance<TypealiasDecl>()
        smod.resolvedTypealiases.forEach { ta ->
            if (ta.expanded == null) internalError("Got unexpanded imported typealias: $ta")
        }
        val map = (typealiases + smod.resolvedTypealiases).map { it.name to it }.toMap()

        fun expandAndcheck(name: String, ty: SType, span: Span): SType = when (ty) {
            is SType.TConst -> {
                if (ty.name == name) parserError(E.recursiveAlias(name), span)
                val pointer = map[ty.name]
                if (pointer != null) expandAndcheck(name, pointer.type, span)
                else ty
            }
            is SType.TFun -> ty.copy(expandAndcheck(name, ty.arg, span), expandAndcheck(name, ty.ret, span))
            is SType.TForall -> ty.copy(type = expandAndcheck(name, ty.type, span))
            is SType.TApp -> {
                val typ = expandAndcheck(name, ty.type, span)
                val tcons = if (typ is SType.TApp) typ.type else typ
                ty.copy(tcons, ty.types.map { expandAndcheck(name, it, span) })
            }
            is SType.TParens -> ty.copy(expandAndcheck(name, ty.type, span))
        }
        typealiases.forEach { ta ->
            ta.expanded = expandAndcheck(ta.name, ta.type, ta.span)
        }
        val errs = validatePublicAliases(smod)
        if (errs.isNotEmpty()) desugarErrors(errs)
        return map
    }

    /**
     * Resolve all type aliases in this type
     */
    private fun resolveAliases(ty: SType): SType {
        return when (ty) {
            is SType.TConst -> synonyms[ty.name]?.expanded ?: ty
            is SType.TParens -> ty.copy(type = resolveAliases(ty.type))
            is SType.TForall -> ty.copy(type = resolveAliases(ty.type))
            is SType.TFun -> ty.copy(arg = resolveAliases(ty.arg), ret = resolveAliases(ty.ret))
            is SType.TApp -> {
                val typ = ty.type as SType.TConst
                val syn = synonyms[typ.name]
                if (syn != null) {
                    val synTy = syn.expanded ?: internalError("Got unexpanded typealias: $syn")
                    syn.tyVars.zip(ty.types).fold(synTy) { oty, (tvar, type) ->
                        oty.substVar(tvar, resolveAliases(type))
                    }
                } else ty.copy(types = ty.types.map { resolveAliases(it) })
            }
        }
    }

    /**
     * Make sure variables are not co-dependent (form cycles)
     * and order them by dependency
     */
    private fun validateTopLevelValues(desugared: List<Decl>): List<Decl> {
        fun collectDependencies(exp: Expr): Set<String> {
            val deps = mutableSetOf<String>()
            exp.everywhere { e ->
                when (e) {
                    is Expr.Var -> deps += e.fullname()
                    is Expr.Constructor -> deps += e.fullname()
                    else -> {
                    }
                }
                e
            }
            return deps
        }

        tailrec fun isVariable(e: Expr): Boolean = when (e) {
            is Expr.Lambda -> false
            is Expr.Ann -> isVariable(e.exp)
            else -> true
        }

        val (decls, lambdas) = desugared.filterIsInstance<Decl.ValDecl>().partition { isVariable(it.exp) }
        val deps = decls.map { it.name to collectDependencies(it.exp) }.toMap()

        val dag = DAG<String, Decl.ValDecl>()
        val nodes = decls.map { it.name to DagNode(it.name, it) }.toMap()
        dag.addNodes(nodes.values)

        nodes.values.forEach { node ->
            val depset = deps[node.value]
            depset?.forEach { name -> nodes[name]?.link(node) }
        }
        val cycle = dag.findCycle()
        if (cycle != null) {
            val first = cycle.iterator().next()
            val vars = cycle.map { it.value }
            if (cycle.size == 1)
                parserError(E.cycleInValues(vars), first.data.span)
            else
                desugarErrors(cycle.map { makeError(E.cycleInValues(vars), it.data.span) })
        }

        val orderedDecl = dag.topoSort().map { it.data }

        return desugared.filterIsInstance<Decl.DataDecl>() + orderedDecl + lambdas
    }

    /**
     * To avoid confusion, constructors can't have the same name as
     * their type unless there's only one constructor for the type.
     */
    private fun validateDataConstructorNames(dd: SDecl.DataDecl) {
        if (dd.dataCtors.size > 1) {
            val typeName = dd.name
            dd.dataCtors.forEach { dc ->
                if (dc.name == typeName)
                    parserError(E.wrongConstructorName(typeName), dd.span)
            }
        }
    }

    /**
     * Converts the let statements inside a do into
     * let expressions where the body is every expression
     * that comes after.
     */
    private fun convertDoLets(exprs: List<SExpr>): List<SExpr> {
        return if (exprs.filterIsInstance<SExpr.DoLet>().isEmpty()) exprs
        else {
            val lets = mutableListOf<SLetDef>()
            var exp = exprs[0]
            var i = 0
            while (exp is SExpr.DoLet) {
                lets.addAll(exp.letDefs)
                exp = exprs[++i]
            }
            if (lets.isEmpty()) listOf(exprs[0]) + convertDoLets(exprs.drop(1))
            else {
                val rest = convertDoLets(exprs.subList(i, exprs.size))
                if (rest.size == 1) {
                    listOf(SExpr.Let(lets, rest[0]))
                } else {
                    listOf(SExpr.Let(lets, SExpr.Do(rest)))
                }
            }
        }
    }

    /**
     * Check if a native expression has the correct number
     * of parameters as they can't be partially applied.
     */
    private fun validateNativeCall(exp: Expr, depth: Int) {
        fun throwArgs(name: String, span: Span, ctx: String, should: Int, got: Int): Nothing {
            parserError(
                E.wrongArgsToNative(name, ctx, should, got),
                span
            )
        }
        when (exp) {
            is Expr.NativeFieldGet -> {
                if (!exp.isStatic && depth < 1) throwArgs(exp.name, exp.span, "getter", 1, depth)
            }
            is Expr.NativeFieldSet -> {
                val should = if (exp.isStatic) 1 else 2
                if (depth != should) throwArgs(exp.name, exp.span, "setter", should, depth)
            }
            is Expr.NativeMethod -> {
                var count = exp.method.parameterCount
                if (!exp.isStatic) count++
                if (depth != count) throwArgs(exp.name, exp.span, "method", count, depth)
            }
            is Expr.NativeConstructor -> {
                val count = max(exp.ctor.parameterCount, 1)
                if (depth != count) throwArgs(exp.name, exp.span, "constructor", count, depth)
            }
            else -> {
            }
        }
    }

    private fun desugarErrors(errs: List<CompilerProblem>): Nothing {
        throw CompilationError(errs)
    }

    private fun parserError(msg: String, span: Span): Nothing = throw ParserError(msg, span)

    private fun makeError(msg: String, span: Span): CompilerProblem {
        return CompilerProblem(msg, ProblemContext.DESUGAR, span, smod.sourceName, smod.name)
    }
}
/**
 * Copyright 2021 Islon Scherer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package novah.frontend.typechecker

import io.lacuna.bifurcan.IList
import io.lacuna.bifurcan.List

sealed class Elem(open val name: String)

data class CVar(override val name: String, val type: Type) : Elem(name)
data class CTVar(override val name: String) : Elem(name)
data class CTMeta(override val name: String, val type: Type? = null) : Elem(name)
//data class CMarker(override val name: String) : Elem(name)

// TODO: optimize with reverse indexes
class Context private constructor(private var ctx: IList<Elem>) {

    fun size() = ctx.size()
    fun mark() = ctx.size()

    /**
     * Fork this context into a new one.
     */
    fun fork() = Context(ctx)

    /**
     * Resets the context back to [other].
     * Should be used together with [fork].
     */
    fun resetTo(other: Context) {
        ctx = other.ctx
    }

    fun lookupInner(clazz: Class<out Elem>, name: String): Elem? {
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    fun indexOfInner(clazz: Class<out Elem>, name: String): Long {
        var i = 0L
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return i
            i++
        }
        return -1
    }

    inline fun <reified T : Elem> lookup(name: String): T? {
        return lookupInner(T::class.java, name) as? T
    }

    inline fun <reified T : Elem> contains(name: String): Boolean = lookup<T>(name) != null

    inline fun <reified T : Elem> indexOf(name: String): Long = indexOfInner(T::class.java, name)

    fun lookupShadowInner(clazz: Class<out Elem>, name: String): Elem? {
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) return e
        }
        return null
    }

    inline fun <reified R : Elem> lookupShadow(name: String): R? {
        return lookupShadowInner(R::class.java, name) as R?
    }

    inline fun <reified R : Elem> split(name: String): IList<Elem> = innerSplit(R::class.java, name)

    fun innerSplit(clazz: Class<out Elem>, name: String): IList<Elem> {
        var i = 0L
        for (e in ctx) {
            if (e.name == name && clazz.isInstance(e)) break
            i++
        }
        if (i <= 0) return List()
        val before = ctx.slice(0, i)
        val after = ctx.slice(i + 1, ctx.size())
        ctx = before
        return after
    }

    fun append(elem: Elem): Context = apply {
        ctx = ctx.addLast(elem)
    }

    fun append(vararg es: Elem): Context = apply {
        for (e in es) ctx = ctx.addLast(e)
    }

    fun concat(elems: IList<Elem>): Context = apply {
        ctx = ctx.concat(elems)
    }

    fun removeLast(): Elem {
        val last = ctx.last()
        ctx = ctx.removeLast()
        return last
    }

    fun replaceInner(clazz: Class<out Elem>, name: String, vararg els: Elem) {
        val right = innerSplit(clazz, name)
        for (e in els) ctx = ctx.addLast(e)
        ctx = ctx.concat(right)
    }

    inline fun <reified R : Elem> replace(name: String, vararg els: Elem) = replaceInner(R::class.java, name, *els)

    // instead of using a marker we use a index for optimization purposes
    fun leaveWithUnsolved(index: Long): kotlin.collections.List<String> {
        if (index >= ctx.size()) return emptyList()
        val ns = mutableListOf<String>()
        for (i in index until ctx.size()) {
            val n = ctx.nth(i)
            if (n is CTMeta && n.type == null) ns += n.name
        }
        ctx = ctx.slice(0, index)
        return ns
    }

    /**
     * Context application
     */
    fun apply(ty: Type): Type = ty.everywhere { t ->
        if (t is TMeta) {
            val solved = lookup<CTMeta>(t.name)
            if (solved?.type != null) apply(solved.type) else t
        } else t
    }

    fun isComplete(from: Long): Boolean {
        for (i in from until size()) {
            val elem = ctx.nth(i)
            if (elem is CTMeta && elem.type == null) return false
        }
        return true
    }

    companion object {
        fun new(): Context = Context(List())
        fun new(ctx: IList<Elem>) = Context(ctx)
    }
}
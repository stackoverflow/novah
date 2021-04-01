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
package novah.backend

import novah.ast.optimized.Clazz
import novah.ast.optimized.DataConstructor
import novah.ast.optimized.Decl
import novah.ast.optimized.Expr
import novah.ast.source.Visibility
import novah.frontend.Span
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

object GenUtil {

    const val OBJECT_CLASS = "java/lang/Object"
    const val INIT = "<init>"
    const val STATIC_INIT = "<clinit>"
    const val INSTANCE = "INSTANCE"
    const val LAMBDA_CTOR = "CREATE"

    const val NOVAH_GENCLASS_VERSION = Opcodes.V1_8

    fun visibility(decl: Decl.TypeDecl): Int =
        if (decl.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else 0

    fun visibility(ctor: DataConstructor): Int =
        if (ctor.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else 0

    fun visibility(decl: Decl.ValDecl): Int =
        if (decl.visibility == Visibility.PUBLIC) Opcodes.ACC_PUBLIC else Opcodes.ACC_PRIVATE

    // Handle used for the invokedynamic of lambdas
    val lambdaHandle = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
        false
    )
}

class GenContext {

    private var localsCount = 0

    private val locals = mutableMapOf<String, InnerLocal>()

    fun nextLocal(): Int = localsCount++

    operator fun get(v: String): Int? = locals[v]?.num

    fun put(name: String, num: Int, startLabel: Label) {
        locals[name] = InnerLocal(num, startLabel)
    }

    fun putParameter(name: String, type: Clazz, startLabel: Label) {
        val num = localsCount++
        locals[name] = InnerLocal(num, startLabel, localVar = Expr.LocalVar(name, type, Span.empty()))
    }

    fun setLocalVar(lv: Expr.LocalVar) {
        val local = locals[lv.name] ?: return
        local.localVar = lv
    }

    fun setEndLabel(name: String, endLabel: Label) {
        val local = locals[name] ?: return
        local.endLabel = endLabel
    }

    fun visitLocalVariables(mv: MethodVisitor) {
        locals.forEach { (_, l) ->
            if (l.endLabel != null && l.localVar != null) {
                val lv = l.localVar!!
                mv.visitLocalVariable(
                    lv.name,
                    lv.type.type.descriptor,
                    null,
                    l.startLabel,
                    l.endLabel,
                    l.num
                )
            }
        }
    }

    private data class InnerLocal(
        val num: Int,
        val startLabel: Label,
        var endLabel: Label? = null,
        var localVar: Expr.LocalVar? = null
    )
}
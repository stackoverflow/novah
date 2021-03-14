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

import io.kotest.core.spec.style.StringSpec
import io.kotest.data.blocking.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import novah.ast.optimized.Type
import novah.backend.TypeUtil.buildClassSignature
import novah.backend.TypeUtil.maybeBuildFieldSignature

class SignatureSpec : StringSpec({

    fun tvar(n: String) = Type.TVar(n)
    fun tfvar(n: String) = Type.TVar(n, true)
    fun tcons(n: String, t: Type) = Type.TConstructor(n, listOf(t))
    fun tfun(t1: Type, t2: Type) = Type.TFun(t1, t2)

    "class signatures are correctly generated" {

        val maybeSig = buildClassSignature(listOf("a"))
        val justSig = buildClassSignature(listOf("a"), "novah/Maybe")

        val daySig = buildClassSignature(listOf())
        val weekdaySig = buildClassSignature(listOf(), "novah/Day")

        val resultSig = buildClassSignature(listOf("k", "e"))
        val okSig = buildClassSignature(listOf("k", "e"), "novah/Result")

        maybeSig shouldBe "<A:Ljava/lang/Object;>Ljava/lang/Object;"
        justSig shouldBe "<A:Ljava/lang/Object;>Lnovah/Maybe<TA;>;"
        daySig shouldBe null
        weekdaySig shouldBe null
        resultSig shouldBe "<K:Ljava/lang/Object;E:Ljava/lang/Object;>Ljava/lang/Object;"
        okSig shouldBe "<K:Ljava/lang/Object;E:Ljava/lang/Object;>Lnovah/Result<TK;TE;>;"
    }

    "field signatures are correctly generated" {

        val funListString = tfun(
            tcons("java/util/List", tvar("java/lang/String")),
            tcons("java/util/List", tvar("java/lang/String"))
        )

        forAll(
            row(tvar("java/lang/Integer"), null),
            row(tvar("java/lang/String"), null),
            row(tcons("Array", tvar("java/lang/String")), null),
            row(tcons("java/util/List", tfvar("A")), "Ljava/util/List<TA;>;"),
            row(
                funListString,
                "Ljava/util/function/Function<Ljava/util/List<Ljava/lang/String;>;Ljava/util/List<Ljava/lang/String;>;>;"
            ),
            row(tfvar("T"), "TT;"),
            row(tfun(tfvar("T"), tfvar("T")), "Ljava/util/function/Function<TT;TT;>;"),
            row(
                tfun(tfvar("T"), tcons("java/util/List", tfvar("T"))),
                "Ljava/util/function/Function<TT;Ljava/util/List<TT;>;>;"
            )
        ) { type, expected ->
            maybeBuildFieldSignature(type) shouldBe expected
        }
    }
})
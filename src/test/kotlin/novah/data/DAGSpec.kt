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
package novah.data

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class DAGSpec : StringSpec({

    "check a DAG doesn't have cycles" {
        val dag = DAG<Int, Nothing?>()

        val n1 = DagNode(1, null)
        val n2 = DagNode(2, null)
        val n3 = DagNode(3, null)
        val n4 = DagNode(4, null)
        val n5 = DagNode(5, null)
        val n6 = DagNode(6, null)

        n1.link(n2)
        n1.link(n3)
        n2.link(n3)
        n4.link(n1)
        n4.link(n5)
        n5.link(n6)

        dag.addNodes(listOf(n1, n2, n3, n4, n5, n6))

        dag.findCycle() shouldBe null
    }

    "check a DAG has one cycle" {
        val dag = DAG<Int, Nothing?>()

        val n1 = DagNode(1, null)
        val n2 = DagNode(2, null)
        val n3 = DagNode(3, null)
        val n4 = DagNode(4, null)
        val n5 = DagNode(5, null)
        val n6 = DagNode(6, null)

        n1.link(n2)
        n1.link(n3)
        n2.link(n3)
        n4.link(n1)
        n4.link(n5)
        n5.link(n6)
        n6.link(n4)

        dag.addNodes(listOf(n1, n2, n3, n4, n5, n6))

        dag.findCycle() shouldBe setOf(n4, n5, n6)
    }

    "check a DAG has another cycle" {
        val dag = DAG<Int, Nothing?>()

        val n1 = DagNode(1, null)
        val n2 = DagNode(2, null)
        val n4 = DagNode(4, null)
        val n5 = DagNode(5, null)
        val n6 = DagNode(6, null)

        n1.link(n2)
        n2.link(n6)
        n4.link(n1)
        n4.link(n5)
        n6.link(n4)

        dag.addNodes(listOf(n1, n2, n4, n5, n6))

        dag.findCycle() shouldBe setOf(n1, n2, n6, n4)
    }

    "sort a DAG topologically" {
        val dag = DAG<Int, Nothing?>()

        val n1 = DagNode(1, null)
        val n2 = DagNode(2, null)
        val n3 = DagNode(3, null)
        val n4 = DagNode(4, null)
        val n5 = DagNode(5, null)
        val n6 = DagNode(6, null)

        n1.link(n2)
        n1.link(n3)
        n2.link(n3)
        n4.link(n1)
        n4.link(n5)
        n5.link(n6)

        dag.addNodes(listOf(n1, n2, n4, n5, n6))

        dag.topoSort().toList() shouldBe listOf(n4, n5, n6, n1, n2, n3)
    }
})
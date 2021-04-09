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

import java.util.*
import kotlin.collections.HashSet

/**
 * A Direct Acyclic Graph.
 * It doesn't actually check for cycles while adding nodes and links.
 */
class DAG<T, D> {
    private val nodes = mutableListOf<DagNode<T, D>>()

    fun addNodes(ns: Collection<DagNode<T, D>>) {
        nodes.addAll(ns)
    }

    fun size(): Int = nodes.size

    /**
     * Check if this graph has a cycle
     * and return the first if any.
     */
    fun findCycle(): Set<DagNode<T, D>>? {
        val whiteSet = HashSet<DagNode<T, D>>(nodes)
        val graySet = HashSet<DagNode<T, D>>()
        val blackSet = HashSet<DagNode<T, D>>()
        val parentage = mutableMapOf<T, DagNode<T, D>?>()

        // depth-first search
        fun dfs(current: DagNode<T, D>, parent: DagNode<T, D>? = null): DagNode<T, D>? {
            whiteSet.remove(current)
            graySet.add(current)
            parentage[current.value] = parent

            for (neighbor in current.getNeighbors()) {
                if (blackSet.contains(neighbor)) continue

                // found cycle
                if (graySet.contains(neighbor)) return current
                val res = dfs(neighbor, current)
                if (res != null) return res
            }

            graySet.remove(current)
            blackSet.add(current)
            return null
        }

        while (whiteSet.size > 0) {
            val current = whiteSet.iterator().next()
            val cycled = dfs(current)
            if (cycled != null){
                return reportCycle(cycled, parentage)
            }
        }
        return null
    }

    /**
     * Return a topological sorted representation
     * of this graph.
     */
    fun topoSort(): Deque<DagNode<T, D>> {
        val visited = HashSet<T>()
        val stack = ArrayDeque<DagNode<T, D>>(nodes.size)

        fun helper(node: DagNode<T, D>) {
            visited += node.value

            for (neighbor in node.getNeighbors()) {
                if (visited.contains(neighbor.value)) continue
                helper(neighbor)
            }
            stack.push(node)
        }

        for (node in nodes.reversed()) {
            if (visited.contains(node.value)) continue
            helper(node)
        }

        return stack
    }

    private fun reportCycle(node: DagNode<T, D>, parentage: Map<T, DagNode<T, D>?>): Set<DagNode<T, D>> {
        val cycle = HashSet<DagNode<T, D>>()
        cycle += node
        var parent = parentage[node.value]
        while (parent != null) {
            cycle += parent
            parent = parentage[parent.value]
        }
        return cycle
    }
}

/**
 * A node in the DAG.
 * `value` has to be unique for every node.
 */
class DagNode<T, D>(val value: T, val data: D) {
    private val neighbors = mutableListOf<DagNode<T, D>>()

    fun link(other: DagNode<T, D>) {
        neighbors += other
    }

    fun getNeighbors(): List<DagNode<T, D>> = neighbors

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val onode = other as? DagNode<*, *> ?: return false
        return value == onode.value
    }

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "Node($value)"
}
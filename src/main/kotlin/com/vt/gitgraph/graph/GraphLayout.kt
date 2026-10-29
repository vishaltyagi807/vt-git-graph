package com.vt.gitgraph.graph

import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.GraphEdge
import com.vt.gitgraph.model.GraphModel
import com.vt.gitgraph.model.GraphRow

object GraphLayout {
    private class Lane(val hash: String, val color: Int, val dashed: Boolean)

    private class Pending(val from: Int, val slot: Int, val color: Int, val dashed: Boolean)

    fun compute(commits: List<Commit>, dropMissingParents: Boolean): GraphModel {
        val known = HashSet<String>(commits.size * 2)
        commits.forEach { known.add(it.hash) }

        val lanes = ArrayList<Lane?>()
        val rows = ArrayList<GraphRow>(commits.size)
        var pending = emptyList<Pending>()
        var previousDown = emptyList<GraphEdge>()
        var nextColor = 0
        var laneCount = 0

        for ((index, commit) in commits.withIndex()) {
            var column = lanes.indexOfFirst { it?.hash == commit.hash }
            val color: Int
            if (column >= 0) {
                color = lanes[column]!!.color
            } else {
                color = nextColor++
                column = firstFree(lanes, 0)
                lanes[column] = Lane(commit.hash, color, false)
            }

            val resolved = pending.map { edge ->
                val target = if (lanes[edge.slot]?.hash == commit.hash) column else edge.slot
                GraphEdge(edge.from, target, edge.color, edge.dashed)
            }
            if (index > 0) {
                previousDown = resolved
                rows[index - 1] = rows[index - 1].copy(down = resolved)
            }

            for (i in lanes.indices) {
                if (lanes[i]?.hash == commit.hash) lanes[i] = null
            }

            val next = ArrayList<Pending>()
            for (i in lanes.indices) {
                val lane = lanes[i] ?: continue
                next.add(Pending(i, i, lane.color, lane.dashed))
            }

            val dashed = commit.kind != CommitKind.NORMAL
            val parents = if (dropMissingParents) commit.parents.filter { it in known } else commit.parents
            parents.forEachIndexed { parentIndex, parent ->
                val existing = lanes.indexOfFirst { it?.hash == parent }
                if (existing > column && lanes[column] == null) {
                    val lane = lanes[existing]!!
                    next.removeAll { it.from == existing && it.slot == existing }
                    next.add(Pending(existing, column, lane.color, lane.dashed))
                    lanes[existing] = null
                    lanes[column] = Lane(parent, color, dashed || lane.dashed)
                    next.add(Pending(column, column, color, dashed))
                } else if (existing >= 0) {
                    val lane = lanes[existing]!!
                    next.add(Pending(column, existing, lane.color, dashed || lane.dashed))
                } else {
                    val slot = if (parentIndex == 0 && lanes[column] == null) column else firstFree(lanes, column + 1)
                    val laneColor = if (parentIndex == 0) color else nextColor++
                    lanes[slot] = Lane(parent, laneColor, dashed)
                    next.add(Pending(column, slot, laneColor, dashed))
                }
            }

            rows.add(GraphRow(column, color, previousDown.takeIf { index > 0 }.orEmpty(), emptyList()))
            pending = next
            laneCount = maxOf(laneCount, lanes.size)
        }

        if (rows.isNotEmpty()) {
            val tail = pending.map { GraphEdge(it.from, it.slot, it.color, it.dashed) }
            rows[rows.lastIndex] = rows.last().copy(down = tail)
        }
        return GraphModel(rows, laneCount)
    }

    private fun firstFree(lanes: ArrayList<Lane?>, startAt: Int): Int {
        for (i in startAt until lanes.size) {
            if (lanes[i] == null) return i
        }
        while (lanes.size < startAt) lanes.add(null)
        lanes.add(null)
        return lanes.lastIndex
    }
}

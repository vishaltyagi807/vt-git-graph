package com.vt.gitgraph

import com.vt.gitgraph.graph.GraphLayout
import com.vt.gitgraph.model.Commit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphLayoutTest {
    private fun commit(hash: String, vararg parents: String) =
        Commit(hash, parents.toList(), "a", "a@x", 0, "a", "a@x", 0, hash)

    @Test
    fun linearHistoryStaysInOneLane() {
        val model = GraphLayout.compute(listOf(commit("c", "b"), commit("b", "a"), commit("a")), false)
        assertEquals(1, model.laneCount)
        assertTrue(model.rows.all { it.column == 0 })
        assertEquals(listOf(0 to 0), model.rows[0].down.map { it.from to it.to })
        assertTrue(model.rows[2].down.isEmpty())
    }

    @Test
    fun mergeOpensSecondLaneAndClosesAtBranchPoint() {
        val model = GraphLayout.compute(
            listOf(commit("m", "x", "f"), commit("f", "b"), commit("x", "b"), commit("b")),
            false,
        )
        assertEquals(2, model.laneCount)
        assertEquals(0, model.rows[0].column)
        assertEquals(1, model.rows[1].column)
        assertEquals(setOf(0 to 0, 0 to 1), model.rows[0].down.map { it.from to it.to }.toSet())
        assertEquals(0, model.rows[3].column)
        assertTrue(model.rows[2].down.any { it.from == 1 && it.to == 0 })
    }

    @Test
    fun edgesLinkConsecutiveRowsConsistently() {
        val model = GraphLayout.compute(
            listOf(commit("t", "a"), commit("u", "a"), commit("a", "r"), commit("r")),
            false,
        )
        for (i in 1 until model.rows.size) {
            assertEquals(model.rows[i - 1].down, model.rows[i].up)
        }
    }

    @Test
    fun missingParentRunsOffTheBottomUnlessDropped() {
        val truncated = GraphLayout.compute(listOf(commit("c", "gone")), false)
        assertEquals(listOf(0 to 0), truncated.rows[0].down.map { it.from to it.to })
        val filtered = GraphLayout.compute(listOf(commit("c", "gone")), true)
        assertTrue(filtered.rows[0].down.isEmpty())
    }

    @Test
    fun freedLaneIsReused() {
        val model = GraphLayout.compute(
            listOf(commit("m", "a", "f"), commit("f", "a"), commit("a", "z"), commit("n", "z"), commit("z")),
            false,
        )
        assertEquals(2, model.laneCount)
    }
}

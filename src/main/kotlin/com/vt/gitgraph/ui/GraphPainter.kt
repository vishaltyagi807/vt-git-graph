package com.vt.gitgraph.ui

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.GraphEdge
import com.vt.gitgraph.model.GraphRow
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D

object GraphPainter {
    fun laneWidth(): Int = JBUI.scale(16)

    fun padding(): Int = JBUI.scale(12)

    fun laneX(column: Int): Float = (padding() + column * laneWidth()).toFloat()

    fun preferredWidth(laneCount: Int): Int =
        (padding() * 2 + (laneCount.coerceAtLeast(1) - 1) * laneWidth() + JBUI.scale(6)).coerceIn(JBUI.scale(52), JBUI.scale(360))

    fun paint(g: Graphics2D, row: GraphRow, commit: Commit, isHead: Boolean, rowHeight: Int, background: Color, selected: Boolean) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val center = rowHeight / 2f
        val solid = BasicStroke(JBUIScale.scale(2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val dashed = BasicStroke(
            JBUIScale.scale(1.6f),
            BasicStroke.CAP_ROUND,
            BasicStroke.JOIN_ROUND,
            1f,
            floatArrayOf(JBUIScale.scale(3f), JBUIScale.scale(4f)),
            0f,
        )

        val ordered = (row.up.map { it to true } + row.down.map { it to false })
            .sortedBy { (edge, _) -> if (edge.from == edge.to) 0 else 1 }
        ordered.forEach { (edge, upward) ->
            val top = if (upward) center - rowHeight else center
            drawEdge(g, edge, top, rowHeight.toFloat(), solid, dashed)
        }
        drawNode(g, row, commit, isHead, center, background, selected)
    }

    private fun drawEdge(g: Graphics2D, edge: GraphEdge, top: Float, height: Float, solid: BasicStroke, dashed: BasicStroke) {
        g.color = Palette.lane(edge.color)
        g.stroke = if (edge.dashed) dashed else solid
        val x1 = laneX(edge.from)
        val x2 = laneX(edge.to)
        val bottom = top + height
        val path = Path2D.Float()
        path.moveTo(x1, top)
        if (edge.from == edge.to) {
            path.lineTo(x2, bottom)
        } else {
            val middle = top + height * 0.5f
            path.curveTo(x1, middle, x2, middle, x2, bottom)
        }
        g.draw(path)
    }

    private fun drawNode(g: Graphics2D, row: GraphRow, commit: Commit, isHead: Boolean, cy: Float, background: Color, selected: Boolean) {
        val cx = laneX(row.column)
        val color = Palette.lane(row.color)
        val radius = JBUIScale.scale(if (isHead) 5.5f else 4.5f)
        val halo = JBUIScale.scale(2f)
        when (commit.kind) {
            CommitKind.UNCOMMITTED -> {
                circle(g, cx, cy, radius + halo, background)
                g.color = color
                g.stroke = BasicStroke(JBUIScale.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(JBUIScale.scale(2.2f), JBUIScale.scale(2.4f)), 0f)
                g.draw(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
            }
            CommitKind.STASH -> {
                val size = radius * 2
                g.color = background
                g.fill(RoundRectangle2D.Float(cx - radius - halo, cy - radius - halo, size + halo * 2, size + halo * 2, JBUIScale.scale(5f), JBUIScale.scale(5f)))
                g.color = Palette.stash
                g.stroke = BasicStroke(JBUIScale.scale(1.8f))
                g.draw(RoundRectangle2D.Float(cx - radius, cy - radius, size, size, JBUIScale.scale(3f), JBUIScale.scale(3f)))
                val inner = JBUIScale.scale(1.8f)
                circle(g, cx, cy, inner, Palette.stash)
            }
            else -> {
                if (isHead || selected) {
                    circle(g, cx, cy, radius + JBUIScale.scale(4f), Palette.alpha(color, if (isHead) 70 else 50))
                }
                circle(g, cx, cy, radius + halo, background)
                if (commit.isMerge) {
                    g.color = color
                    g.stroke = BasicStroke(JBUIScale.scale(2f))
                    g.draw(Ellipse2D.Float(cx - radius + 1, cy - radius + 1, (radius - 1) * 2, (radius - 1) * 2))
                    circle(g, cx, cy, JBUIScale.scale(1.6f), color)
                } else {
                    circle(g, cx, cy, radius, color)
                    if (isHead) circle(g, cx, cy, JBUIScale.scale(2f), Palette.contrast(color))
                }
            }
        }
    }

    private fun circle(g: Graphics2D, cx: Float, cy: Float, radius: Float, color: Color) {
        g.color = color
        g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }
}

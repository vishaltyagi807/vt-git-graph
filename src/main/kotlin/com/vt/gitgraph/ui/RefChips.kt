package com.vt.gitgraph.ui

import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.RefKind
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.QuadCurve2D
import java.awt.geom.RoundRectangle2D

data class RefChip(
    val label: String,
    val kind: RefKind,
    val primary: GitRef,
    val others: List<GitRef> = emptyList(),
    val trailing: String = "",
) {
    val syncedRemote: Boolean get() = kind == RefKind.LOCAL && others.isNotEmpty()
}

data class PlacedChip(val chip: RefChip, val bounds: Rectangle)

object RefChips {
    fun forRefs(refs: List<GitRef>, showDetachedHead: Boolean, hash: String): List<RefChip> {
        val remoteByName = refs.filter { it.kind == RefKind.REMOTE }.associateBy { it.name }
        val consumed = HashSet<String>()
        val locals = refs.filter { it.kind == RefKind.LOCAL }
            .sortedWith(compareByDescending<GitRef> { it.current }.thenBy { it.name })
            .map { ref ->
                val upstream = ref.upstream?.let { remoteByName[it] }
                if (upstream != null) consumed.add(upstream.name)
                RefChip(ref.name, RefKind.LOCAL, ref, listOfNotNull(upstream), trackLabel(ref))
            }
        val remotes = refs.filter { it.kind == RefKind.REMOTE && it.name !in consumed }.sortedBy { it.name }
            .map { RefChip(it.name, RefKind.REMOTE, it) }
        val tags = refs.filter { it.kind == RefKind.TAG }.sortedBy { it.name }.map { RefChip(it.name, RefKind.TAG, it) }
        val stashes = refs.filter { it.kind == RefKind.STASH }.map { RefChip(it.name, RefKind.STASH, it) }
        val detached = if (showDetachedHead) {
            listOf(RefChip("HEAD", RefKind.LOCAL, GitRef("HEAD", "HEAD", RefKind.LOCAL, hash, current = true)))
        } else {
            emptyList()
        }
        return detached + locals + remotes + tags + stashes
    }

    private fun trackLabel(ref: GitRef): String = buildString {
        if (ref.ahead > 0) append("↑").append(ref.ahead)
        if (ref.behind > 0) {
            if (isNotEmpty()) append(' ')
            append("↓").append(ref.behind)
        }
    }

    fun height(): Int = JBUI.scale(18)

    private fun iconWidth(): Int = JBUI.scale(13)

    private fun pad(): Int = JBUI.scale(6)

    private fun gap(): Int = JBUI.scale(4)

    fun chipFont(base: Font, chip: RefChip): Font {
        val style = if (chip.primary.current) Font.BOLD else Font.PLAIN
        return base.deriveFont(style, base.size2D - JBUIScale.scale(1f))
    }

    private fun width(chip: RefChip, metrics: FontMetrics): Int {
        val trailing = if (chip.trailing.isEmpty()) 0 else metrics.stringWidth(chip.trailing) + JBUI.scale(5)
        val remote = if (chip.syncedRemote) JBUI.scale(13) else 0
        return pad() * 2 + iconWidth() + metrics.stringWidth(chip.label) + trailing + remote
    }

    fun layout(chips: List<RefChip>, base: Font, metricsOf: (Font) -> FontMetrics, startX: Int, rowHeight: Int, maxWidth: Int): Pair<List<PlacedChip>, Int> {
        val placed = ArrayList<PlacedChip>()
        var x = startX
        val y = (rowHeight - height()) / 2
        val limit = startX + maxWidth
        val moreWidth = JBUI.scale(30)
        for ((index, chip) in chips.withIndex()) {
            val width = width(chip, metricsOf(chipFont(base, chip)))
            val remaining = chips.size - index - 1
            val reserve = if (remaining > 0) moreWidth else 0
            if (x + width + reserve > limit) {
                if (index == 0) {
                    placed.add(PlacedChip(chip, Rectangle(x, y, (limit - x - reserve).coerceAtLeast(JBUI.scale(40)), height())))
                    x = placed.last().bounds.x + placed.last().bounds.width + gap()
                    if (remaining == 0) return placed to 0
                    return placed to remaining
                }
                return placed to chips.size - index
            }
            placed.add(PlacedChip(chip, Rectangle(x, y, width, height())))
            x += width + gap()
        }
        return placed to 0
    }

    fun endX(placed: List<PlacedChip>, hidden: Int, startX: Int): Int {
        val last = placed.lastOrNull()?.bounds ?: return startX
        val after = last.x + last.width + gap()
        return if (hidden > 0) after + JBUI.scale(30) else after
    }

    fun paint(g: Graphics2D, placed: PlacedChip, base: Font, laneColor: Color) {
        val chip = placed.chip
        val bounds = placed.bounds
        val current = chip.primary.current
        val color = when (chip.kind) {
            RefKind.TAG -> Palette.tag
            RefKind.STASH -> Palette.stash
            else -> laneColor
        }
        val shape = RoundRectangle2D.Float(
            bounds.x.toFloat(),
            bounds.y.toFloat(),
            bounds.width.toFloat() - 1,
            bounds.height.toFloat() - 1,
            JBUIScale.scale(8f),
            JBUIScale.scale(8f),
        )
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val foreground: Color
        if (current) {
            g.color = color
            g.fill(shape)
            foreground = Palette.contrast(color)
        } else {
            g.color = Palette.alpha(color, 34)
            g.fill(shape)
            g.color = Palette.alpha(color, 120)
            g.stroke = BasicStroke(JBUIScale.scale(1f))
            g.draw(shape)
            foreground = color
        }

        val font = chipFont(base, chip)
        g.font = font
        val metrics = g.fontMetrics
        val iconX = bounds.x + pad()
        val cy = bounds.y + bounds.height / 2f
        drawGlyph(g, chip.kind, iconX.toFloat(), cy, foreground)

        val textX = iconX + iconWidth()
        val baseline = bounds.y + (bounds.height - metrics.height) / 2 + metrics.ascent
        val trailingWidth = if (chip.trailing.isEmpty()) 0 else metrics.stringWidth(chip.trailing) + JBUI.scale(5)
        val remoteWidth = if (chip.syncedRemote) JBUI.scale(13) else 0
        val available = bounds.x + bounds.width - pad() - textX - trailingWidth - remoteWidth
        g.color = foreground
        g.drawString(CellFormat.fit(chip.label, metrics, available), textX, baseline)
        var cursor = textX + available
        if (chip.syncedRemote) {
            drawCloud(g, cursor + JBUIScale.scale(3f), cy, foreground)
            cursor += remoteWidth
        }
        if (chip.trailing.isNotEmpty()) {
            g.color = foreground
            g.drawString(chip.trailing, cursor + JBUI.scale(5), baseline)
        }
    }

    fun paintMore(g: Graphics2D, x: Int, rowHeight: Int, hidden: Int, base: Font, color: Color) {
        val text = "+$hidden"
        g.font = base.deriveFont(base.size2D - JBUIScale.scale(1f))
        val metrics = g.fontMetrics
        val y = (rowHeight - height()) / 2
        val width = metrics.stringWidth(text) + JBUI.scale(10)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Palette.alpha(color, 30)
        g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), width.toFloat(), height().toFloat() - 1, JBUIScale.scale(8f), JBUIScale.scale(8f)))
        g.color = color
        g.drawString(text, x + JBUI.scale(5), y + (height() - metrics.height) / 2 + metrics.ascent)
    }

    private fun drawGlyph(g: Graphics2D, kind: RefKind, x: Float, cy: Float, color: Color) {
        g.color = color
        g.stroke = BasicStroke(JBUIScale.scale(1.2f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (kind) {
            RefKind.LOCAL -> {
                val r = JBUIScale.scale(1.4f)
                val left = x + JBUIScale.scale(1.5f)
                val right = x + JBUIScale.scale(7f)
                val top = cy - JBUIScale.scale(3.8f)
                val bottom = cy + JBUIScale.scale(3.8f)
                g.draw(Line2D.Float(left, top + r, left, bottom - r))
                g.draw(QuadCurve2D.Float(right, top + r, right, cy, left, cy + JBUIScale.scale(1.6f)))
                g.draw(Ellipse2D.Float(left - r, top - r, r * 2, r * 2))
                g.draw(Ellipse2D.Float(left - r, bottom - r, r * 2, r * 2))
                g.draw(Ellipse2D.Float(right - r, top - r, r * 2, r * 2))
            }
            RefKind.REMOTE -> drawCloud(g, x, cy, color)
            RefKind.TAG -> {
                val size = JBUIScale.scale(3.6f)
                val left = x + JBUIScale.scale(0.5f)
                val path = Path2D.Float()
                path.moveTo(left, cy - size)
                path.lineTo(left + JBUIScale.scale(4.6f), cy - size)
                path.lineTo(left + JBUIScale.scale(8.4f), cy)
                path.lineTo(left + JBUIScale.scale(4.6f), cy + size)
                path.lineTo(left, cy + size)
                path.closePath()
                g.draw(path)
                val dot = JBUIScale.scale(1f)
                g.fill(Ellipse2D.Float(left + JBUIScale.scale(1.6f), cy - dot, dot * 2, dot * 2))
            }
            RefKind.STASH -> {
                val width = JBUIScale.scale(8f)
                val left = x + JBUIScale.scale(0.5f)
                g.draw(Line2D.Float(left, cy - JBUIScale.scale(3f), left + width, cy - JBUIScale.scale(3f)))
                g.draw(Line2D.Float(left, cy, left + width, cy))
                g.draw(Line2D.Float(left, cy + JBUIScale.scale(3f), left + width, cy + JBUIScale.scale(3f)))
            }
        }
    }

    private fun drawCloud(g: Graphics2D, x: Float, cy: Float, color: Color) {
        g.color = color
        g.stroke = BasicStroke(JBUIScale.scale(1.1f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        val u = JBUIScale.scale(1f)
        val base = cy + 3f * u
        val path = Path2D.Float()
        path.moveTo(x + 2f * u, base)
        path.curveTo(x - 0.4f * u, base, x - 0.4f * u, cy - 0.8f * u, x + 2f * u, cy - 0.8f * u)
        path.curveTo(x + 2.2f * u, cy - 4f * u, x + 6.6f * u, cy - 4f * u, x + 7f * u, cy - 0.8f * u)
        path.curveTo(x + 9.4f * u, cy - 0.8f * u, x + 9.4f * u, base, x + 7f * u, base)
        path.closePath()
        g.draw(path)
    }
}

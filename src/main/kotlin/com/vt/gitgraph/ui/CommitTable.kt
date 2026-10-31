package com.vt.gitgraph.ui

import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.UIUtil
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.GraphRow
import com.vt.gitgraph.model.GraphSnapshot
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.JTableHeader
import javax.swing.table.TableCellRenderer
import javax.swing.table.TableColumn

class CommitTableModel : AbstractTableModel() {
    var snapshot: GraphSnapshot = GraphSnapshot()

    override fun getRowCount(): Int = snapshot.commits.size
    override fun getColumnCount(): Int = COLUMNS.size
    override fun getColumnName(column: Int): String = COLUMNS[column]
    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = snapshot.commits[rowIndex]

    companion object {
        const val GRAPH = 0
        const val MESSAGE = 1
        const val AUTHOR = 2
        const val DATE = 3
        const val HASH = 4
        val COLUMNS = listOf("Graph", "Description", "Author", "Date", "Commit")
    }
}

class SearchHighlight {
    var ranges: Map<String, List<IntRange>> = emptyMap()
    var current: String? = null
}

class CommitTable : JBTable(CommitTableModel()) {
    val graphModel: CommitTableModel get() = model as CommitTableModel
    val highlight = SearchHighlight()
    var hoverRow = -1
        private set
    private val renderer = CommitCellRenderer(this)
    private var columnsBuilt = false

    init {
        setShowGrid(false)
        intercellSpacing = java.awt.Dimension(0, 0)
        autoCreateColumnsFromModel = false
        autoResizeMode = AUTO_RESIZE_NEXT_COLUMN
        selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        setExpandableItemsEnabled(false)
        setStriped(false)
        emptyText.text = "No commits to show"
        applyDensity()
        rebuildColumns()
        tableHeader.reorderingAllowed = false
        tableHeader.defaultRenderer = HeaderRenderer(tableHeader)

        val hover = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = setHover(rowAtPoint(e.point))
            override fun mouseExited(e: MouseEvent) = setHover(-1)
        }
        addMouseMotionListener(hover)
        addMouseListener(hover)
    }

    private fun setHover(row: Int) {
        if (row == hoverRow) return
        val old = hoverRow
        hoverRow = row
        if (old in 0 until rowCount) repaint(getCellRect(old, 0, true).apply { width = this@CommitTable.width })
        if (row in 0 until rowCount) repaint(getCellRect(row, 0, true).apply { width = this@CommitTable.width })
    }

    fun applyDensity() {
        rowHeight = JBUI.scale(if (GraphSettings.data.compactRows) 24 else 30)
    }

    fun rebuildColumns() {
        val settings = GraphSettings.data
        val widths = HashMap<Int, Int>()
        while (columnModel.columnCount > 0) {
            val column = columnModel.getColumn(0)
            if (columnsBuilt) widths[column.modelIndex] = column.width
            columnModel.removeColumn(column)
        }
        fun add(index: Int, width: Int) {
            val column = TableColumn(index)
            column.headerValue = CommitTableModel.COLUMNS[index]
            column.cellRenderer = renderer
            column.minWidth = JBUI.scale(40)
            column.preferredWidth = widths[index]?.takeIf { it > 0 } ?: width
            column.width = column.preferredWidth
            addColumn(column)
        }
        add(CommitTableModel.GRAPH, GraphPainter.preferredWidth(graphModel.snapshot.graph.laneCount))
        add(CommitTableModel.MESSAGE, JBUI.scale(520))
        if (settings.showAuthorColumn) add(CommitTableModel.AUTHOR, JBUI.scale(170))
        if (settings.showDateColumn) add(CommitTableModel.DATE, JBUI.scale(130))
        if (settings.showHashColumn) add(CommitTableModel.HASH, JBUI.scale(88))
        columnsBuilt = true
    }

    fun show(snapshot: GraphSnapshot) {
        graphModel.snapshot = snapshot
        graphModel.fireTableDataChanged()
        hoverRow = -1
        val graph = (0 until columnModel.columnCount).map { columnModel.getColumn(it) }.firstOrNull { it.modelIndex == CommitTableModel.GRAPH }
        if (graph != null) {
            val width = GraphPainter.preferredWidth(snapshot.graph.laneCount)
            graph.preferredWidth = width
            graph.width = width
        }
    }

    override fun doLayout() {
        if (tableHeader?.resizingColumn != null) {
            super.doLayout()
            return
        }
        var used = 0
        var message: TableColumn? = null
        for (i in 0 until columnModel.columnCount) {
            val column = columnModel.getColumn(i)
            if (column.modelIndex == CommitTableModel.MESSAGE) message = column else used += column.preferredWidth.also { column.width = it }
        }
        message?.width = (width - used).coerceAtLeast(JBUI.scale(120))
        message?.preferredWidth = message?.width ?: 0
    }

    fun commitAt(row: Int): Commit? = graphModel.snapshot.commits.getOrNull(row)

    fun selectedCommits(): List<Commit> = selectedRows.toList().mapNotNull(::commitAt)

    fun chipAt(point: Point): PlacedChip? {
        val row = rowAtPoint(point)
        val column = columnAtPoint(point)
        if (row < 0 || column < 0 || convertColumnIndexToModel(column) != CommitTableModel.MESSAGE) return null
        val cell = getCellRect(row, column, false)
        val commit = commitAt(row) ?: return null
        val (placed, _) = renderer.placeChips(commit, cell.width)
        val local = Point(point.x - cell.x, point.y - cell.y)
        return placed.firstOrNull { it.bounds.contains(local) }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val row = rowAtPoint(event.point)
        val commit = commitAt(row) ?: return null
        val chip = chipAt(event.point)
        if (chip != null) {
            val ref = chip.chip.primary
            return buildString {
                append("<html><b>").append(OpDialog.escape(ref.name)).append("</b>")
                if (ref.upstream != null) append("<br/>Tracks ").append(OpDialog.escape(ref.upstream))
                if (ref.ahead > 0 || ref.behind > 0) append("<br/>").append(ref.ahead).append(" ahead, ").append(ref.behind).append(" behind")
                if (ref.upstreamGone) append("<br/>Upstream is gone")
                if (ref.subject.isNotEmpty() && ref.annotated) append("<br/>").append(OpDialog.escape(ref.subject))
                append("</html>")
            }
        }
        if (commit.kind == CommitKind.UNCOMMITTED) return commit.subject
        return buildString {
            append("<html><b>").append(OpDialog.escape(commit.subject)).append("</b><br/>")
            append(OpDialog.escape(commit.author)).append(" &lt;").append(OpDialog.escape(commit.authorEmail)).append("&gt;<br/>")
            append(CellFormat.full(commit.authorTime)).append("<br/><code>").append(commit.hash).append("</code></html>")
        }
    }

    private class HeaderRenderer(private val header: JTableHeader) : JComponent(), TableCellRenderer {
        private var text = ""

        override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
            text = value?.toString().orEmpty()
            font = header.font
            return this
        }

        override fun getPreferredSize(): java.awt.Dimension = java.awt.Dimension(JBUI.scale(40), JBUI.scale(26))

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            GraphicsUtil.applyRenderingHints(g2)
            g2.color = UIUtil.getPanelBackground()
            g2.fillRect(0, 0, width, height)
            g2.color = Palette.divider
            g2.fillRect(0, height - 1, width, 1)
            g2.font = header.font.deriveFont(Font.BOLD, header.font.size2D - JBUIScale.scale(1f))
            g2.color = Palette.muted
            val metrics = g2.fontMetrics
            g2.drawString(CellFormat.fit(text.uppercase(), metrics, width - JBUI.scale(16)), JBUI.scale(10), (height - metrics.height) / 2 + metrics.ascent)
        }
    }
}

class CommitCellRenderer(private val table: CommitTable) : JComponent(), TableCellRenderer {
    private var row = 0
    private var modelColumn = 0
    private var selected = false
    private var focused = false
    private var hovered = false
    private var commit: Commit? = null
    private var graphRow: GraphRow? = null

    override fun getTableCellRendererComponent(table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int): Component {
        this.row = row
        this.modelColumn = table.convertColumnIndexToModel(column)
        this.selected = isSelected
        this.focused = table.hasFocus()
        this.hovered = row == this.table.hoverRow
        this.commit = value as? Commit
        this.graphRow = this.table.graphModel.snapshot.graph.rows.getOrNull(row)
        return this
    }

    fun placeChips(commit: Commit, width: Int): Pair<List<PlacedChip>, Int> {
        val snapshot = table.graphModel.snapshot
        val refs = snapshot.refsByHash[commit.hash].orEmpty()
            .filter { it.kind != com.vt.gitgraph.model.RefKind.STASH || commit.kind == CommitKind.STASH }
        val detached = snapshot.currentBranch == null && commit.hash == snapshot.head
        if (refs.isEmpty() && !detached) return emptyList<PlacedChip>() to 0
        val chips = RefChips.forRefs(refs, detached, commit.hash)
        return RefChips.layout(chips, table.font, table::getFontMetrics, JBUI.scale(8), table.rowHeight, (width * 0.62).toInt())
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        GraphicsUtil.applyRenderingHints(g2)
        if (g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == null ||
            g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT
        ) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }
        val current = commit ?: return
        val background = Palette.rowBackground(selected, focused)
        g2.color = background
        g2.fillRect(0, 0, width, height)
        if (hovered && !selected) {
            g2.color = Palette.hover
            g2.fillRect(0, 0, width, height)
        }
        val foreground = if (selected) UIUtil.getTableSelectionForeground(focused) else UIUtil.getTableForeground()
        val secondary = if (selected) foreground else Palette.muted
        when (modelColumn) {
            CommitTableModel.GRAPH -> paintGraph(g2, current, background)
            CommitTableModel.MESSAGE -> paintMessage(g2, current, foreground)
            CommitTableModel.AUTHOR -> paintAuthor(g2, current, foreground)
            CommitTableModel.DATE -> paintDate(g2, current, secondary)
            CommitTableModel.HASH -> paintHash(g2, current, secondary)
        }
    }

    private fun paintGraph(g: Graphics2D, commit: Commit, background: Color) {
        val graph = graphRow ?: return
        GraphPainter.paint(g, graph, commit, commit.hash == table.graphModel.snapshot.head, height, opaque(background), selected)
    }

    private fun opaque(color: Color): Color = if (color.alpha == 255) color else Color(color.rgb)

    private fun paintMessage(g: Graphics2D, commit: Commit, foreground: Color) {
        val base = table.font
        val laneColor = graphRow?.let { Palette.lane(it.color) } ?: Palette.muted
        val (placed, hidden) = placeChips(commit, width)
        placed.forEach { RefChips.paint(g, it, base, laneColor) }
        if (hidden > 0) {
            val last = placed.lastOrNull()?.bounds
            val x = if (last == null) JBUI.scale(8) else last.x + last.width + JBUI.scale(4)
            RefChips.paintMore(g, x, height, hidden, base, Palette.muted)
        }
        val textX = if (placed.isEmpty()) JBUI.scale(8) else RefChips.endX(placed, hidden, JBUI.scale(8)) + JBUI.scale(2)
        val isSpecial = commit.kind == CommitKind.UNCOMMITTED
        val isHead = commit.hash == table.graphModel.snapshot.head
        val style = when {
            isHead -> Font.BOLD
            isSpecial -> Font.ITALIC
            else -> Font.PLAIN
        }
        g.font = base.deriveFont(style)
        val metrics = g.fontMetrics
        val text = CellFormat.fit(commit.subject.ifEmpty { "(no message)" }, metrics, width - textX - JBUI.scale(8))
        val baseline = (height - metrics.height) / 2 + metrics.ascent
        paintHighlights(g, commit, text, textX, metrics)
        g.color = foreground
        g.drawString(text, textX, baseline)
    }

    private fun paintHighlights(g: Graphics2D, commit: Commit, text: String, x: Int, metrics: FontMetrics) {
        val ranges = table.highlight.ranges[commit.hash] ?: return
        val isCurrent = table.highlight.current == commit.hash
        val y = (height - metrics.height) / 2
        val limit = text.removeSuffix("…").length
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = if (isCurrent) Palette.currentMatch else Palette.match
        ranges.forEach { range ->
            if (range.first < limit) {
                val end = (range.last + 1).coerceAtMost(limit)
                val startX = x + metrics.stringWidth(text.substring(0, range.first))
                val matchWidth = metrics.stringWidth(text.substring(range.first, end))
                g.fillRoundRect(startX - 1, y, matchWidth + 2, metrics.height, JBUI.scale(4), JBUI.scale(4))
            }
        }
    }

    private fun paintAuthor(g: Graphics2D, commit: Commit, foreground: Color) {
        if (commit.kind == CommitKind.UNCOMMITTED) return
        val size = JBUIScale.scale(18f)
        val x = JBUIScale.scale(8f)
        val y = (height - size) / 2
        val color = Palette.avatar(commit.authorEmail.ifEmpty { commit.author })
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = color
        g.fill(Ellipse2D.Float(x, y, size, size))
        val base = table.font
        g.font = base.deriveFont(Font.BOLD, base.size2D - JBUIScale.scale(3.5f))
        val initials = CellFormat.initials(commit.author)
        val small = g.fontMetrics
        g.color = Palette.contrast(color)
        g.drawString(initials, x + (size - small.stringWidth(initials)) / 2, y + (size - small.height) / 2 + small.ascent)
        g.font = base
        val metrics = g.fontMetrics
        val textX = (x + size + JBUIScale.scale(6f)).toInt()
        g.color = foreground
        g.drawString(CellFormat.fit(commit.author, metrics, width - textX - JBUI.scale(6)), textX, (height - metrics.height) / 2 + metrics.ascent)
    }

    private fun paintDate(g: Graphics2D, commit: Commit, color: Color) {
        val text = if (GraphSettings.data.relativeDates) CellFormat.relative(commit.commitTime) else CellFormat.absolute(commit.commitTime)
        g.font = table.font
        val metrics = g.fontMetrics
        g.color = color
        g.drawString(CellFormat.fit(text, metrics, width - JBUI.scale(16)), JBUI.scale(10), (height - metrics.height) / 2 + metrics.ascent)
    }

    private fun paintHash(g: Graphics2D, commit: Commit, color: Color) {
        val base = table.font
        g.font = Font(Font.MONOSPACED, Font.PLAIN, base.size - 1)
        val metrics = g.fontMetrics
        g.color = color
        g.drawString(CellFormat.fit(commit.shortHash, metrics, width - JBUI.scale(16)), JBUI.scale(10), (height - metrics.height) / 2 + metrics.ascent)
    }
}

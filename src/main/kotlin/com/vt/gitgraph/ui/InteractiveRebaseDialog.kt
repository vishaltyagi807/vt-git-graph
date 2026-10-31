package com.vt.gitgraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.AnActionButton
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vt.gitgraph.ops.RebaseAction
import com.vt.gitgraph.ops.RebaseEntry
import com.vt.gitgraph.ops.RebaseTodo
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyEvent
import javax.swing.DefaultCellEditor
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class InteractiveRebaseDialog(project: Project, val entries: MutableList<RebaseEntry>, warnings: List<String>) : DialogWrapper(project, true) {
    private val model = EntriesModel()
    private val table = JBTable(model)
    private val note = (listOf("Commits run oldest to newest. Reorder them with the arrows, and pick an action for each one.") + warnings)
        .joinToString("<br/>", "<html>", "</html>") { OpDialog.escape(it) }

    init {
        title = "Interactive Rebase"
        setOKButtonText("Start Rebase")
        table.rowHeight = JBUI.scale(28)
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        table.surrendersFocusOnKeystroke = true
        table.columnModel.getColumn(0).apply {
            preferredWidth = JBUI.scale(110)
            maxWidth = JBUI.scale(130)
            cellEditor = DefaultCellEditor(ComboBox(RebaseAction.entries.toTypedArray()))
            cellRenderer = ActionRenderer()
        }
        table.columnModel.getColumn(1).apply {
            preferredWidth = JBUI.scale(84)
            maxWidth = JBUI.scale(100)
        }
        table.columnModel.getColumn(2).preferredWidth = JBUI.scale(520)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val decorated = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .setMoveUpAction { move(-1) }
            .setMoveDownAction { move(1) }
            .createPanel()
        decorated.preferredSize = Dimension(JBUI.scale(780), JBUI.scale(420))
        val label = JBLabel(note)
        label.foreground = UIUtil.getContextHelpForeground()
        label.border = JBUI.Borders.emptyBottom(8)
        val panel = JPanel(BorderLayout())
        panel.add(label, BorderLayout.NORTH)
        panel.add(decorated, BorderLayout.CENTER)
        return panel
    }

    private fun move(delta: Int) {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        val row = table.selectedRow
        val target = row + delta
        if (row < 0 || target !in entries.indices) return
        val moved = entries.removeAt(row)
        entries.add(target, moved)
        model.fireTableDataChanged()
        table.selectionModel.setSelectionInterval(target, target)
    }

    override fun doValidate(): ValidationInfo? {
        if (table.isEditing) table.cellEditor.stopCellEditing()
        return RebaseTodo.validate(entries)?.let { ValidationInfo(it, table) }
    }

    override fun getPreferredFocusedComponent(): JComponent = table

    private inner class EntriesModel : AbstractTableModel() {
        override fun getRowCount(): Int = entries.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(column: Int): String = listOf("Action", "Commit", "Message")[column]
        override fun getColumnClass(column: Int): Class<*> = if (column == 0) RebaseAction::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int): Boolean =
            column == 0 || (column == 2 && entries[row].action == RebaseAction.REWORD)

        override fun getValueAt(row: Int, column: Int): Any {
            val entry = entries[row]
            return when (column) {
                0 -> entry.action
                1 -> entry.commit.shortHash
                else -> entry.message
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val entry = entries[row]
            when (column) {
                0 -> entry.action = value as RebaseAction
                2 -> entry.message = value?.toString().orEmpty()
            }
            fireTableRowsUpdated(row, row)
        }
    }

    private class ActionRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, (value as RebaseAction).label, isSelected, false, row, column)
            if (!isSelected) {
                foreground = when (value) {
                    RebaseAction.DROP -> Palette.deleted
                    RebaseAction.SQUASH, RebaseAction.FIXUP -> Palette.stash
                    RebaseAction.REWORD, RebaseAction.EDIT -> Palette.modified
                    RebaseAction.PICK -> UIUtil.getTableForeground()
                }
            }
            return this
        }
    }
}

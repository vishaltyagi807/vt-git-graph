package com.vt.gitgraph.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vt.gitgraph.model.ReflogEntry
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JList

class ReflogDialog(project: Project, entries: List<ReflogEntry>) : DialogWrapper(project, true) {
    enum class Choice { SHOW, CHECKOUT, RESET }

    var choice: Choice = Choice.SHOW
        private set
    private val list = JBList(entries)
    val selected: ReflogEntry? get() = list.selectedValue

    init {
        title = "Reflog"
        setOKButtonText("Show in Graph")
        list.cellRenderer = object : ColoredListCellRenderer<ReflogEntry>() {
            override fun customizeCellRenderer(
                list: JList<out ReflogEntry>,
                value: ReflogEntry,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.hash.take(8), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ${value.selector}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(value.message)
                append("   ${CellFormat.relative(value.time)}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
        if (entries.isNotEmpty()) list.selectedIndex = 0
        init()
    }

    override fun createCenterPanel(): JComponent {
        val scroll = JBScrollPane(list)
        scroll.preferredSize = Dimension(JBUI.scale(760), JBUI.scale(460))
        return scroll
    }

    override fun getPreferredFocusedComponent(): JComponent = list

    override fun createLeftSideActions(): Array<Action> = arrayOf(choiceAction("Checkout", Choice.CHECKOUT), choiceAction("Reset Here…", Choice.RESET))

    private fun choiceAction(name: String, target: Choice): Action = object : AbstractAction(name) {
        override fun actionPerformed(e: ActionEvent) {
            choice = target
            close(OK_EXIT_CODE)
        }
    }
}

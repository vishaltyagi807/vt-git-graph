package com.vt.gitgraph.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

class OpDialog(
    project: Project,
    dialogTitle: String,
    okText: String,
    description: String? = null,
    danger: Boolean = false,
) : DialogWrapper(project, true) {
    private val form = FormBuilder.createFormBuilder()
    private val validators = ArrayList<() -> ValidationInfo?>()
    private var focus: JComponent? = null

    init {
        title = dialogTitle
        setOKButtonText(okText)
        if (danger) {
            getOKAction().putValue(DEFAULT_ACTION, null)
            getCancelAction().putValue(DEFAULT_ACTION, true)
        }
        if (description != null) {
            val label = JBLabel("<html><body style='width:380px'>${escape(description)}</body></html>")
            label.foreground = UIUtil.getContextHelpForeground()
            label.border = JBUI.Borders.emptyBottom(8)
            form.addComponent(label)
        }
    }

    fun text(label: String, initial: String = "", required: Boolean = true, validate: ((String) -> String?)? = null): JBTextField {
        val field = JBTextField(initial)
        field.columns = 34
        form.addLabeledComponent(label, field)
        if (focus == null) focus = field
        validators.add {
            val value = field.text.trim()
            val message = when {
                required && value.isEmpty() -> "$label is required"
                validate != null -> validate(value)
                else -> null
            }
            message?.let { ValidationInfo(it, field) }
        }
        return field
    }

    fun area(label: String, initial: String = "", required: Boolean = false): JBTextArea {
        val area = JBTextArea(initial, 7, 48)
        area.lineWrap = true
        area.wrapStyleWord = true
        val scroll = JBScrollPane(area)
        scroll.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(150))
        form.addLabeledComponent(label, scroll, true)
        if (focus == null) focus = area
        if (required) validators.add { if (area.text.isBlank()) ValidationInfo("$label is required", area) else null }
        return area
    }

    fun check(label: String, initial: Boolean = false): JBCheckBox {
        val box = JBCheckBox(label, initial)
        form.addComponent(box)
        return box
    }

    fun <T> combo(label: String, values: List<T>, initial: T? = null): ComboBox<T> {
        @Suppress("UNCHECKED_CAST")
        val box = ComboBox(values.map { it as Any }.toTypedArray()) as ComboBox<T>
        if (initial != null) box.selectedItem = initial
        form.addLabeledComponent(label, box)
        return box
    }

    fun open(): Boolean {
        init()
        return showAndGet()
    }

    override fun createCenterPanel(): JComponent {
        val panel = form.panel
        panel.border = JBUI.Borders.empty(4, 2)
        return panel
    }

    override fun getPreferredFocusedComponent(): JComponent? = focus

    override fun doValidateAll(): List<ValidationInfo> = validators.mapNotNull { it() }

    internal val okLabel: Any? get() = getOKAction().getValue(Action.NAME)

    internal val cancelIsDefault: Boolean get() = getCancelAction().getValue(DEFAULT_ACTION) == true

    companion object {
        fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        fun refName(value: String): String? = when {
            value.isEmpty() -> "A name is required"
            value == "@" -> "This is not a valid Git ref name"
            value.any { it.isWhitespace() || it.isISOControl() || it in "~^:?*[\\" } -> "The name contains a character Git does not allow"
            value.startsWith("-") || value.endsWith("/") || value.endsWith(".") || value.contains("..") || value.contains("@{") || value.contains("//") ->
                "This is not a valid Git ref name"
            value.split('/').any { it.isEmpty() || it.startsWith(".") || it.endsWith(".lock") } -> "This is not a valid Git ref name"
            else -> null
        }
    }
}

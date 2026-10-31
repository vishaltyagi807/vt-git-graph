package com.vt.gitgraph.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.vcsUtil.VcsUtil
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitDetails
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.WORKING_TREE
import com.vt.gitgraph.ops.GitOperations
import com.vt.gitgraph.ops.GraphMenus
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.io.IOException
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

sealed interface DetailTarget {
    data object None : DetailTarget
    data class Single(val commit: Commit) : DetailTarget
    data class Compare(val left: String, val right: String) : DetailTarget
}

private class FileNode(val name: String, val change: FileChange?)

class DetailsPanel(private val view: GraphView, private val ops: GitOperations, private val menus: GraphMenus) : JPanel(CardLayout()) {
    private val header = JPanel(VerticalLayout(JBUI.scale(6)))
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val cards = layout as CardLayout
    private var generation = 0
    private var leftRevision: String? = null
    private var rightRevision: String = WORKING_TREE
    private var target: DetailTarget = DetailTarget.None
    private var isWorkingTreeView = false
    private var disposed = false

    init {
        val empty = JBPanelWithEmptyText()
        empty.emptyText.text = "Select a commit to see its details"
        empty.emptyText.appendSecondaryText("Select two commits to compare them", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        add(empty, "empty")

        header.border = JBUI.Borders.empty(12)
        header.background = UIUtil.getTableBackground()
        val headerScroll = ScrollPaneFactory.createScrollPane(header, true)
        headerScroll.viewport.background = UIUtil.getTableBackground()

        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = FileRenderer()
        tree.rowHeight = JBUI.scale(24)
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (!SwingUtilities.isRightMouseButton(e)) return
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                if (!tree.isPathSelected(path)) tree.selectionPath = path
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) selectedChange()?.let(::showDiff)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) selectedChange()?.let(::showDiff)
            }
        })
        PopupHandler.installPopupMenu(tree, DefaultActionGroup(FileMenuGroup()), "VtGitGraphFiles")

        val splitter = OnePixelSplitter(true, "vt.git.graph.details", 0.45f)
        splitter.firstComponent = headerScroll
        splitter.secondComponent = ScrollPaneFactory.createScrollPane(tree, true)
        add(splitter, "content")
        cards.show(this, "empty")
    }

    private fun selectedChange(): FileChange? = (TreeUtil.getLastUserObject(tree.selectionPath) as? FileNode)?.change

    fun show(newTarget: DetailTarget) {
        target = newTarget
        val current = ++generation
        setFiles(emptyList())
        when (newTarget) {
            DetailTarget.None -> {
                cards.show(this, "empty")
                return
            }
            is DetailTarget.Single -> {
                isWorkingTreeView = newTarget.commit.kind == CommitKind.UNCOMMITTED
                renderCommitHeader(newTarget.commit, null)
                cards.show(this, "content")
                loadSingle(newTarget.commit, current)
            }
            is DetailTarget.Compare -> {
                isWorkingTreeView = false
                renderCompareHeader(newTarget, emptyList())
                cards.show(this, "content")
                loadCompare(newTarget, current)
            }
        }
    }

    fun refresh() = show(target)

    fun dispose() {
        disposed = true
        generation++
    }

    private fun background(generationAtStart: Int, load: () -> Unit, apply: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val failure = try {
                load()
                null
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                e
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || generationAtStart != generation || view.project.isDisposed) return@invokeLater
                if (failure == null) {
                    apply()
                } else {
                    header.add(wrapped("Could not load this commit: ${failure.message.orEmpty()}", UIUtil.getLabelFont()))
                    header.revalidate()
                    header.repaint()
                }
            }, ModalityState.any())
        }
    }

    private fun showError(title: String, error: Exception) {
        if (disposed || view.project.isDisposed) return
        Messages.showErrorDialog(view.project, error.message ?: "Unknown error", title)
    }

    private fun loadSingle(commit: Commit, current: Int) {
        val loader = view.loader ?: return
        var details: CommitDetails? = null
        var changes: List<FileChange> = emptyList()
        if (commit.kind == CommitKind.UNCOMMITTED) {
            leftRevision = view.snapshot.head
            rightRevision = WORKING_TREE
            background(current, { changes = loader.status() }) {
                renderCommitHeader(commit, null, changes)
                setFiles(changes)
            }
            return
        }
        val parent = commit.parents.firstOrNull()
        leftRevision = parent
        rightRevision = commit.hash
        background(current, {
            details = loader.details(commit.hash)
            changes = loader.changes(parent ?: loader.emptyTree(commit.hash), commit.hash)
        }) {
            renderCommitHeader(commit, details, changes)
            setFiles(changes)
        }
    }

    private fun loadCompare(compare: DetailTarget.Compare, current: Int) {
        val loader = view.loader ?: return
        var changes: List<FileChange> = emptyList()
        leftRevision = compare.left
        rightRevision = compare.right
        background(current, {
            changes = if (compare.right == WORKING_TREE) loader.changesAgainstWorkingTree(compare.left) else loader.changes(compare.left, compare.right)
        }) {
            renderCompareHeader(compare, changes)
            setFiles(changes)
        }
    }

    private fun label(text: String, color: Color? = null, font: Font? = null): JBLabel {
        val label = JBLabel(text)
        if (color != null) label.foreground = color
        if (font != null) label.font = font
        return label
    }

    private fun wrapped(text: String, font: Font): JBTextArea {
        val area = JBTextArea(text)
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = true
        area.isOpaque = false
        area.font = font
        area.border = JBUI.Borders.empty()
        return area
    }

    private fun row(vararg components: JComponent): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        row.isOpaque = false
        components.forEachIndexed { index, component ->
            if (index > 0) row.add(Box.createHorizontalStrut(JBUI.scale(8)))
            row.add(component)
        }
        return row
    }

    private fun section(text: String) = label(text.uppercase(), Palette.muted, UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getLabelFont().size2D - 2f))

    private fun renderCommitHeader(commit: Commit, details: CommitDetails?, changes: List<FileChange>? = null) {
        header.removeAll()
        val base = UIUtil.getLabelFont()
        val message = details?.message ?: commit.subject
        val title = message.substringBefore('\n')
        val body = message.substringAfter('\n', "").trim()
        header.add(wrapped(title, base.deriveFont(Font.BOLD, base.size2D + 2f)))
        if (body.isNotEmpty()) header.add(wrapped(body, base))

        if (commit.kind == CommitKind.UNCOMMITTED) {
            val stageAll = ActionLink("Stage all") { ops.stageAll() }
            val unstage = ActionLink("Unstage all") { ops.unstageAll() }
            val commitLink = ActionLink("Commit…") { ops.openCommitWindow() }
            val stash = ActionLink("Stash…") { ops.stashChanges() }
            header.add(row(commitLink, stageAll, unstage, stash))
        } else {
            val hash = label(commit.hash.take(16), Palette.muted, Font(Font.MONOSPACED, Font.PLAIN, base.size - 1))
            val copy = ActionLink("Copy") { CopyPasteManager.getInstance().setContents(StringSelection(commit.hash)) }
            header.add(row(hash, copy))

            val refs = view.snapshot.refsByHash[commit.hash].orEmpty()
            if (refs.isNotEmpty()) {
                val laneIndex = view.snapshot.rowByHash[commit.hash]?.let { view.snapshot.graph.rows.getOrNull(it)?.color } ?: 0
                header.add(ChipRow(RefChips.forRefs(refs, false, commit.hash), Palette.lane(laneIndex), base))
            }

            header.add(personRow("Author", commit.author, commit.authorEmail, commit.authorTime))
            if (commit.committerEmail != commit.authorEmail || commit.committer != commit.author || commit.commitTime != commit.authorTime) {
                header.add(personRow("Committer", commit.committer, commit.committerEmail, commit.commitTime))
            }
            signature(details)?.let(header::add)
            navigationRow("Parents", commit.parents)?.let(header::add)
            navigationRow("Children", view.snapshot.childrenByHash[commit.hash].orEmpty())?.let(header::add)
        }
        addStats(changes)
        header.revalidate()
        header.repaint()
    }

    private fun personRow(role: String, name: String, email: String, time: Long): JComponent {
        val base = UIUtil.getLabelFont()
        val panel = JPanel(BorderLayout(JBUI.scale(8), 0))
        panel.isOpaque = false
        panel.add(Avatar(name, email), BorderLayout.WEST)
        val text = JPanel(VerticalLayout(0))
        text.isOpaque = false
        text.add(row(label(name, font = base.deriveFont(Font.BOLD)), label(email, Palette.muted)).also { it.border = JBUI.Borders.empty() })
        text.add(label("$role · ${CellFormat.full(time)} · ${CellFormat.relative(time)}", Palette.muted, base.deriveFont(base.size2D - 1f)))
        panel.add(text, BorderLayout.CENTER)
        return panel
    }

    private fun signature(details: CommitDetails?): JComponent? {
        val status = details?.signatureStatus ?: return null
        if (status.isEmpty() || status == "N") return null
        val good = status == "G"
        val text = when (status) {
            "G" -> "Verified signature"
            "B" -> "Bad signature"
            "U" -> "Valid signature, unknown validity"
            "X" -> "Expired signature"
            "Y" -> "Signed by an expired key"
            "R" -> "Signed by a revoked key"
            "E" -> "Signature cannot be checked"
            else -> "Signed"
        }
        val signer = details.signer.ifEmpty { details.signingKey }
        return label(if (signer.isEmpty()) text else "$text · $signer", if (good) Palette.added else Palette.conflict)
    }

    private fun navigationRow(title: String, hashes: List<String>): JComponent? {
        if (hashes.isEmpty()) return null
        val links = hashes.map { hash -> ActionLink(hash.take(8)) { view.selectCommit(hash) } }
        return row(label(title, Palette.muted), *links.toTypedArray())
    }

    private fun addStats(changes: List<FileChange>?) {
        if (changes == null) return
        val added = changes.sumOf { it.added.coerceAtLeast(0) }
        val deleted = changes.sumOf { it.deleted.coerceAtLeast(0) }
        header.add(section("Changes"))
        val files = if (changes.size == 1) "1 file" else "${changes.size} files"
        header.add(row(label(files), label("+$added", Palette.added), label("−$deleted", Palette.deleted), StatBar(added, deleted)))
    }

    private fun renderCompareHeader(compare: DetailTarget.Compare, changes: List<FileChange>) {
        header.removeAll()
        val base = UIUtil.getLabelFont()
        val right = if (compare.right == WORKING_TREE) "Working Tree" else compare.right.take(8)
        header.add(wrapped("Comparing ${compare.left.take(8)} with $right", base.deriveFont(Font.BOLD, base.size2D + 2f)))
        header.add(row(ActionLink(compare.left.take(8)) { view.selectCommit(compare.left) }, label("→", Palette.muted), if (compare.right == WORKING_TREE) label(right) else ActionLink(right) { view.selectCommit(compare.right) }))
        addStats(changes)
        header.revalidate()
        header.repaint()
    }

    private fun setFiles(changes: List<FileChange>) {
        val root = DefaultMutableTreeNode()
        changes.sortedBy { it.path }.forEach { change ->
            var parent = root
            val parts = change.path.split('/')
            parts.dropLast(1).forEach { part ->
                val existing = (0 until parent.childCount).asSequence()
                    .map { parent.getChildAt(it) as DefaultMutableTreeNode }
                    .firstOrNull { (it.userObject as FileNode).change == null && (it.userObject as FileNode).name == part }
                parent = existing ?: DefaultMutableTreeNode(FileNode(part, null)).also(parent::add)
            }
            parent.add(DefaultMutableTreeNode(FileNode(parts.last(), change)))
        }
        compact(root)
        treeModel.setRoot(root)
        TreeUtil.expandAll(tree)
    }

    private fun compact(node: DefaultMutableTreeNode) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            var current = child
            var name = (child.userObject as FileNode).name
            if ((child.userObject as FileNode).change != null) continue
            while (current.childCount == 1 && ((current.getChildAt(0) as DefaultMutableTreeNode).userObject as FileNode).change == null) {
                current = current.getChildAt(0) as DefaultMutableTreeNode
                name += "/" + (current.userObject as FileNode).name
            }
            if (current !== child) {
                val children = (0 until current.childCount).map { current.getChildAt(it) as DefaultMutableTreeNode }
                child.removeAllChildren()
                children.forEach(child::add)
                child.userObject = FileNode(name, null)
            }
            compact(child)
        }
    }

    private fun bytesContent(root: String?, path: String, bytes: ByteArray?): DiffContent {
        val factory = DiffContentFactory.getInstance()
        if (bytes == null) return factory.createEmpty()
        val filePath = VcsUtil.getFilePath(if (root != null) "$root/$path" else path, false)
        return factory.createFromBytes(view.project, bytes, filePath)
    }

    fun showDiff(change: FileChange) {
        val loader = view.loader ?: return
        val root = view.runner?.root?.path
        val left = leftRevision
        val right = rightRevision
        val leftPath = change.oldPath ?: change.path
        val deleted = change.status == 'D'
        ApplicationManager.getApplication().executeOnPooledThread {
            val leftBytes = left?.takeIf { change.status != 'A' && change.status != '?' }?.let { loader.fileAt(it, leftPath) }
            val rightBytes = if (right == WORKING_TREE || deleted) null else loader.fileAt(right, change.path)
            val workingFile: VirtualFile? = if (right == WORKING_TREE && !deleted && root != null) {
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, change.path))
            } else {
                null
            }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || view.project.isDisposed) return@invokeLater
                try {
                    val factory = DiffContentFactory.getInstance()
                    val leftContent = bytesContent(root, leftPath, leftBytes)
                    val rightContent = when {
                        workingFile != null -> factory.create(view.project, workingFile)
                        else -> bytesContent(root, change.path, rightBytes)
                    }
                    val rightTitle = if (right == WORKING_TREE) "Working Tree" else right.take(8)
                    val request = SimpleDiffRequest(change.path, leftContent, rightContent, left?.take(8) ?: "Empty", rightTitle)
                    DiffManager.getInstance().showDiff(view.project, request)
                } catch (e: IOException) {
                    showError("Show Diff", e)
                }
            }, ModalityState.nonModal())
        }
    }

    private fun openAtRevision(change: FileChange) {
        val loader = view.loader ?: return
        val revision = rightRevision
        if (revision == WORKING_TREE) {
            openWorkingCopy(change)
            return
        }
        val name = change.path.substringAfterLast('/')
        val type = FileTypeManager.getInstance().getFileTypeByFileName(name)
        if (type.isBinary) {
            showDiff(change)
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val bytes = loader.fileAt(revision, change.path) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (disposed || view.project.isDisposed) return@invokeLater
                val file = LightVirtualFile("${revision.take(8)} $name", type, String(bytes, Charsets.UTF_8))
                file.isWritable = false
                FileEditorManager.getInstance(view.project).openFile(file, true)
            }, ModalityState.nonModal())
        }
    }

    private fun workingCopyExists(change: FileChange): Boolean {
        val root = view.runner?.root?.path ?: return false
        return File(root, change.path).exists()
    }

    private fun openWorkingCopy(change: FileChange) {
        val root = view.runner?.root?.path ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root, change.path)) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && !view.project.isDisposed) FileEditorManager.getInstance(view.project).openFile(file, true)
            }, ModalityState.nonModal())
        }
    }

    private inner class FileMenuGroup : DefaultActionGroup() {
        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT

        override fun getChildren(e: com.intellij.openapi.actionSystem.AnActionEvent?): Array<com.intellij.openapi.actionSystem.AnAction> {
            val change = selectedChange() ?: return emptyArray()
            val actions = ArrayList<com.intellij.openapi.actionSystem.AnAction>()
            actions.add(DumbAwareAction.create("Show Diff") { showDiff(change) }.also { it.templatePresentation.icon = AllIcons.Actions.Diff })
            if (change.status != 'D') actions.add(DumbAwareAction.create("Open at This Revision") { openAtRevision(change) })
            if (workingCopyExists(change)) actions.add(DumbAwareAction.create("Open Working Copy") { openWorkingCopy(change) })
            actions.add(DumbAwareAction.create("Show File History") { view.filterByPath(change.path) })
            if (isWorkingTreeView) {
                if (change.unstaged || change.status == '?') actions.add(DumbAwareAction.create("Stage") { ops.stageFile(change) })
                if (change.staged) actions.add(DumbAwareAction.create("Unstage") { ops.unstageFile(change) })
                actions.add(DumbAwareAction.create("Discard Changes…") { ops.discardFile(change) })
            } else if (rightRevision != WORKING_TREE && change.status != 'D') {
                actions.add(DumbAwareAction.create("Restore This Version to Working Tree…") { ops.restoreFile(change, rightRevision, rightRevision.take(8)) })
            }
            actions.add(DumbAwareAction.create("Copy Path") { CopyPasteManager.getInstance().setContents(StringSelection(change.path)) })
            return actions.toTypedArray()
        }
    }

    private class FileRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? FileNode ?: return
            val change = node.change
            if (change == null) {
                icon = AllIcons.Nodes.Folder
                append(node.name)
                return
            }
            icon = FileTypeManager.getInstance().getFileTypeByFileName(node.name).icon
            val color = Palette.statusColor(change.status)
            val style = if (change.status == 'D') SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN
            append(node.name, SimpleTextAttributes(style, color))
            if (change.oldPath != null) append("  ← ${change.oldPath}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            append("  ${change.status}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color))
            if (!change.isBinary) {
                if (change.added > 0) append("  +${change.added}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.added))
                if (change.deleted > 0) append("  −${change.deleted}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.deleted))
            }
            toolTipText = "${Palette.statusLabel(change.status)}: ${change.path}"
        }
    }

    private class Avatar(private val name: String, private val email: String) : JComponent() {
        override fun getPreferredSize(): Dimension = JBUI.size(32, 32)

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            GraphicsUtil.applyRenderingHints(g2)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val color = Palette.avatar(email.ifEmpty { name })
            val size = JBUI.scale(30)
            g2.color = color
            g2.fillOval(0, 0, size, size)
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            val initials = CellFormat.initials(name)
            val metrics = g2.fontMetrics
            g2.color = Palette.contrast(color)
            g2.drawString(initials, (size - metrics.stringWidth(initials)) / 2, (size - metrics.height) / 2 + metrics.ascent)
        }
    }

    private class StatBar(private val added: Int, private val deleted: Int) : JComponent() {
        override fun getPreferredSize(): Dimension = JBUI.size(90, 16)

        override fun paintComponent(g: Graphics) {
            val total = added + deleted
            if (total == 0) return
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val barHeight = JBUI.scale(6)
            val y = (height - barHeight) / 2
            val split = (width * added.toFloat() / total).toInt()
            g2.color = Palette.deleted
            g2.fillRoundRect(0, y, width, barHeight, barHeight, barHeight)
            if (split > 0) {
                g2.color = Palette.added
                g2.fillRoundRect(0, y, split, barHeight, barHeight, barHeight)
            }
        }
    }

    private class ChipRow(private val chips: List<RefChip>, private val laneColor: Color, private val base: Font) : JComponent() {
        private fun place(maxWidth: Int): List<PlacedChip> {
            val metrics = { font: Font -> getFontMetrics(font) }
            return RefChips.layout(chips, base, metrics, 0, RefChips.height() + JBUI.scale(2), maxWidth).first
        }

        override fun getPreferredSize(): Dimension = Dimension(JBUI.scale(120), RefChips.height() + JBUI.scale(2))

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            GraphicsUtil.applyRenderingHints(g2)
            place(width).forEach { RefChips.paint(g2, it, base, laneColor) }
        }
    }
}

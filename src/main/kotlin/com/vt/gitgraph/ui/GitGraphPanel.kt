package com.vt.gitgraph.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.dvcs.repo.VcsRepositoryMappingListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.ui.CheckBoxList
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.Alarm
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.GraphicsUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vt.gitgraph.git.GitDataLoader
import com.vt.gitgraph.git.GitRunner
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.GraphSnapshot
import com.vt.gitgraph.model.LoadOptions
import com.vt.gitgraph.model.LogOrder
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RepoOperation
import com.vt.gitgraph.model.WORKING_TREE
import com.vt.gitgraph.ops.GitOperations
import com.vt.gitgraph.ops.GraphMenus
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.ActionEvent
import java.awt.event.HierarchyEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import javax.swing.AbstractAction
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.plaf.basic.BasicToggleButtonUI

private class MiniToggle(label: String) : JToggleButton(label) {
    init {
        setUI(BasicToggleButtonUI())
        isOpaque = false
        isFocusable = false
        isContentAreaFilled = false
        isBorderPainted = false
        border = JBUI.Borders.empty()
        preferredSize = JBUI.size(30, 26)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        GraphicsUtil.applyRenderingHints(g2)
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING) == RenderingHints.VALUE_TEXT_ANTIALIAS_DEFAULT) {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        }
        val arc = JBUI.scale(6)
        if (isSelected) {
            g2.color = Palette.alpha(Palette.modified, 60)
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        } else if (model.isRollover) {
            g2.color = Palette.hover
            g2.fillRoundRect(0, 0, width, height, arc, arc)
        }
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
        val metrics = g2.fontMetrics
        g2.color = if (isSelected) Palette.modified else Palette.muted
        g2.drawString(text, (width - metrics.stringWidth(text)) / 2, (height - metrics.height) / 2 + metrics.ascent)
    }
}

class GitGraphPanel(override val project: Project) : JPanel(BorderLayout()), GraphView, Disposable {
    private val settings get() = GraphSettings.data
    private val table = CommitTable()
    private val ops = GitOperations(this)
    private val menus = GraphMenus(this, ops)
    private val details = DetailsPanel(this, ops, menus)
    private val sidebar = SidebarPanel(this, ops, menus)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    private val repoCombo = ComboBox<GitRepository>()
    private val branchButton = JButton("All Branches", AllIcons.Vcs.Branch)
    private val filterButton = JButton("Filter", AllIcons.General.Filter)
    private val search = SearchTextField(false)
    private val matchLabel = JBLabel("")
    private val caseToggle = MiniToggle("Aa")
    private val regexToggle = MiniToggle(".*")
    private val banner = JPanel(BorderLayout())
    private val footerLeft = JBLabel("")
    private val footerRight = JBLabel("")
    private val body = JPanel(CardLayout())
    private val emptyPanel = JBPanelWithEmptyText()
    private val outerSplitter = OnePixelSplitter(false, "vt.git.graph.sidebar", 0.16f)
    private val innerSplitter = OnePixelSplitter(false, "vt.git.graph.main", 0.72f)
    private val tableScroll: JBScrollPane

    private var currentRepository: GitRepository? = null
    private var currentRunner: GitRunner? = null
    private var currentLoader: GitDataLoader? = null
    private var options = LoadOptions()
    private var loadGeneration = 0
    private var loading = false
    private var limit = 0
    private var needsReload = false
    private var suppressSelection = false
    private var updatingRepositories = false
    private var disposed = false
    private var matches: List<Int> = emptyList()
    private var matchIndex = -1

    override var snapshot: GraphSnapshot = GraphSnapshot()
        private set
    override val repository: GitRepository? get() = currentRepository
    override val runner: GitRunner? get() = currentRunner
    override val loader: GitDataLoader? get() = currentLoader

    init {
        limit = settings.pageSize
        tableScroll = ScrollPaneFactory.createScrollPane(table, true) as JBScrollPane

        innerSplitter.firstComponent = tableScroll
        innerSplitter.secondComponent = details
        outerSplitter.firstComponent = sidebar
        outerSplitter.secondComponent = innerSplitter
        sidebar.isVisible = settings.showSidebar
        details.isVisible = settings.showDetails

        emptyPanel.emptyText.text = "No Git repository found in this project"
        body.add(outerSplitter, "graph")
        body.add(emptyPanel, "empty")

        val center = JPanel(BorderLayout())
        banner.isVisible = false
        center.add(banner, BorderLayout.NORTH)
        center.add(body, BorderLayout.CENTER)

        add(createTopBar(), BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(createFooter(), BorderLayout.SOUTH)

        installTableBehaviour()
        installKeys()
        installListeners()
        updateRepositories()
    }

    private fun createTopBar(): JComponent {
        repoCombo.isVisible = false
        repoCombo.renderer = object : com.intellij.ui.SimpleListCellRenderer<GitRepository>() {
            override fun customize(list: javax.swing.JList<out GitRepository>, value: GitRepository?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.root?.name.orEmpty()
            }
        }
        repoCombo.addActionListener {
            val selected = repoCombo.selectedItem as? GitRepository
            if (!updatingRepositories && selected != null && selected != currentRepository) setRepository(selected)
        }
        branchButton.addActionListener { showBranchPopup() }
        filterButton.addActionListener { showFilterPopup() }
        search.textEditor.emptyText.text = "Search message, author, hash, ref"
        search.textEditor.columns = 22
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = updateSearch(true)
            override fun removeUpdate(e: DocumentEvent) = updateSearch(true)
            override fun changedUpdate(e: DocumentEvent) = updateSearch(true)
        })
        caseToggle.toolTipText = "Match case"
        regexToggle.toolTipText = "Regular expression"
        listOf(caseToggle, regexToggle).forEach {
            it.addActionListener { updateSearch(true) }
        }
        matchLabel.foreground = Palette.muted
        val previous = JButton(AllIcons.Actions.PreviousOccurence)
        val next = JButton(AllIcons.Actions.NextOccurence)
        listOf(previous, next).forEach {
            it.isFocusable = false
            it.putClientProperty("JButton.buttonType", "square")
            it.preferredSize = JBUI.size(30, 28)
            it.minimumSize = JBUI.size(30, 28)
        }
        previous.toolTipText = "Previous match (Shift+Enter)"
        next.toolTipText = "Next match (Enter)"
        previous.addActionListener { stepMatch(-1) }
        next.addActionListener { stepMatch(1) }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4)))
        left.add(repoCombo)
        left.add(branchButton)
        left.add(filterButton)
        left.add(search)
        left.add(caseToggle)
        left.add(regexToggle)
        left.add(previous)
        left.add(next)
        left.add(matchLabel)

        val toolbar = ActionManager.getInstance().createActionToolbar("VtGitGraph", createToolbarActions(), true)
        toolbar.targetComponent = this
        toolbar.component.border = JBUI.Borders.empty()

        val bar = JPanel(BorderLayout())
        bar.border = JBUI.Borders.customLineBottom(JBUI.CurrentTheme.ToolWindow.headerBorderBackground())
        bar.add(left, BorderLayout.CENTER)
        bar.add(toolbar.component, BorderLayout.EAST)
        return bar
    }

    private fun createFooter(): JComponent {
        val footer = JPanel(BorderLayout())
        footer.border = JBUI.Borders.compound(
            JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.headerBorderBackground()),
            JBUI.Borders.empty(3, 10),
        )
        footerLeft.foreground = Palette.muted
        footerRight.foreground = Palette.muted
        val small = UIUtil.getLabelFont().deriveFont(UIUtil.getLabelFont().size2D - 1f)
        footerLeft.font = small
        footerRight.font = small
        footer.add(footerLeft, BorderLayout.WEST)
        footer.add(footerRight, BorderLayout.EAST)
        return footer
    }

    private fun createToolbarActions(): DefaultActionGroup {
        val group = DefaultActionGroup()
        group.add(action("Refresh", AllIcons.Actions.Refresh) { reload() })
        group.add(action("Fetch", AllIcons.Vcs.Fetch) { ops.fetchAll() })
        group.add(action("Pull", AllIcons.Actions.CheckOut) { ops.pullCurrent() })
        group.add(action("Push Current Branch", AllIcons.Vcs.Push) { ops.pushCurrent() })
        group.addSeparator()
        group.add(action("Stash Changes", AllIcons.Vcs.ShelveSilent) { ops.stashChanges() })
        group.add(action("Reflog", AllIcons.Vcs.History) { ops.openReflog() })
        group.addSeparator()
        group.add(toggle("Branches and Tags Sidebar", AllIcons.Actions.PreviewDetailsVertically, { settings.showSidebar }) {
            settings.showSidebar = it
            sidebar.isVisible = it
            outerSplitter.revalidate()
        })
        group.add(toggle("Commit Details", AllIcons.Actions.PreviewDetails, { settings.showDetails }) {
            settings.showDetails = it
            details.isVisible = it
            innerSplitter.revalidate()
        })
        group.add(action("Open in Editor Tab", AllIcons.Actions.MoveToWindow) { GitGraphEditorProvider.open(project) })
        group.add(createMoreGroup())
        return group
    }

    private fun createMoreGroup(): DefaultActionGroup {
        val more = DefaultActionGroup("View and Tools", true)
        more.templatePresentation.icon = AllIcons.Actions.More
        more.add(option("Show Remote Branches", { settings.showRemotes }) { settings.showRemotes = it })
        more.add(option("Show Tags", { settings.showTags }) { settings.showTags = it })
        more.add(option("Show Stashes", { settings.showStashes }) { settings.showStashes = it })
        more.add(option("Show Uncommitted Changes", { settings.showUncommitted }) { settings.showUncommitted = it })
        more.add(option("First Parent Only", { settings.firstParent }) { settings.firstParent = it })
        val order = DefaultActionGroup("Commit Order", true)
        LogOrder.entries.forEach { value ->
            order.add(option(value.label, { settings.order == value.name }) { if (it) settings.order = value.name })
        }
        more.add(order)
        more.addSeparator("Display")
        more.add(visual("Relative Dates", { settings.relativeDates }) { settings.relativeDates = it })
        more.add(visual("Compact Rows", { settings.compactRows }) {
            settings.compactRows = it
            table.applyDensity()
        })
        more.add(visual("Author Column", { settings.showAuthorColumn }) {
            settings.showAuthorColumn = it
            table.rebuildColumns()
        })
        more.add(visual("Date Column", { settings.showDateColumn }) {
            settings.showDateColumn = it
            table.rebuildColumns()
        })
        more.add(visual("Commit Column", { settings.showHashColumn }) {
            settings.showHashColumn = it
            table.rebuildColumns()
        })
        more.addSeparator("Repository")
        more.add(action("Add Remote…", AllIcons.General.Add) { ops.addRemote() })
        more.add(action("Add Worktree…", null) { ops.addWorktree(null) })
        more.add(action("Apply Patch…", null) { ops.applyPatch() })
        more.add(action("Start Bisect", null) { ops.bisectStart(null, null) })
        more.add(action("Update Submodules", null) { ops.updateSubmodules() })
        more.add(action("Run Garbage Collection", null) { ops.garbageCollect() })
        return more
    }

    private fun action(text: String, icon: javax.swing.Icon?, handler: () -> Unit) =
        object : DumbAwareAction(text, null, icon) {
            override fun actionPerformed(e: AnActionEvent) = handler()
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun toggle(text: String, icon: javax.swing.Icon?, get: () -> Boolean, set: (Boolean) -> Unit) =
        object : DumbAwareToggleAction(text, null, icon) {
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private fun option(text: String, get: () -> Boolean, set: (Boolean) -> Unit) = toggle(text, null, get) {
        set(it)
        options = buildOptions()
        reload()
    }

    private fun visual(text: String, get: () -> Boolean, set: (Boolean) -> Unit) = toggle(text, null, get) {
        set(it)
        table.repaint()
    }

    private fun buildOptions(current: LoadOptions = options): LoadOptions = current.copy(
        limit = limit,
        showRemotes = settings.showRemotes,
        showTags = settings.showTags,
        showStashes = settings.showStashes,
        showUncommitted = settings.showUncommitted,
        firstParent = settings.firstParent,
        order = runCatching { LogOrder.valueOf(settings.order) }.getOrDefault(LogOrder.DATE),
    )

    private fun installTableBehaviour() {
        table.selectionModel.addListSelectionListener { event ->
            if (event.valueIsAdjusting || suppressSelection) return@addListSelectionListener
            updateDetails()
        }
        table.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2 || e.button != MouseEvent.BUTTON1) return
                val chip = table.chipAt(e.point) ?: return
                val ref = chip.chip.primary
                when (ref.kind) {
                    RefKind.LOCAL -> if (!ref.current) ops.checkout(ref.name)
                    RefKind.REMOTE -> ops.checkoutRemote(ref)
                    RefKind.TAG -> ops.checkout("refs/tags/${ref.name}", ref.name)
                    RefKind.STASH -> Unit
                }
            }
        })
        tableScroll.viewport.addChangeListener {
            val visible = table.visibleRect
            val lastRow = table.rowAtPoint(java.awt.Point(0, visible.y + visible.height - 1))
            if (snapshot.hasMore && !loading && lastRow >= 0 && lastRow > table.rowCount - LOAD_AHEAD_ROWS) loadMore()
        }
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val row = table.rowAtPoint(e.point)
        if (row < 0) return
        if (!table.isRowSelected(row)) table.setRowSelectionInterval(row, row)
        val chip = table.chipAt(e.point)
        val commit = table.commitAt(row) ?: return
        val group = if (chip != null && chip.chip.primary.fullName != "HEAD") {
            menus.refMenu(chip.chip.primary).also { base ->
                chip.chip.others.forEach { other ->
                    val nested = menus.refMenu(other)
                    nested.templatePresentation.text = other.name
                    nested.isPopup = true
                    base.addSeparator()
                    base.add(nested)
                }
            }
        } else {
            menus.commitMenu(commit, table.selectedCommits())
        }
        menus.show(group, table, e.x, e.y)
    }

    private fun installKeys() {
        bind(this, if (SystemInfo.isMac) "meta F" else "control F", "focusSearch") { search.textEditor.requestFocusInWindow() }
        bind(this, "F5", "refreshGraph") { reload() }
        bind(search.textEditor, "ENTER", "nextMatch") { stepMatch(1) }
        bind(search.textEditor, "shift ENTER", "previousMatch") { stepMatch(-1) }
        bind(search.textEditor, "ESCAPE", "clearSearch") {
            search.text = ""
            table.requestFocusInWindow()
        }
    }

    private fun bind(component: JComponent, stroke: String, name: String, handler: () -> Unit) {
        component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(stroke), name)
        component.actionMap.put(name, object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent) = handler()
        })
    }

    private fun installListeners() {
        val connection = project.messageBus.connect(this)
        connection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener { changed ->
            if (changed == currentRepository) scheduleReload(REPOSITORY_DELAY_MS)
        })
        connection.subscribe(VcsRepositoryManager.VCS_REPOSITORY_MAPPING_UPDATED, VcsRepositoryMappingListener {
            ApplicationManager.getApplication().invokeLater({ updateRepositories() }, ModalityState.any())
        })
        connection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListUpdateDone() = scheduleReload(CHANGES_DELAY_MS)
        })
        addHierarchyListener { event ->
            if (event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing && needsReload) reload()
        }
    }

    private fun scheduleReload(delay: Int) {
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest({
            if (isShowing) reload() else needsReload = true
        }, delay)
    }

    private fun updateRepositories() {
        val repositories = GitRepositoryManager.getInstance(project).repositories
        val previous = currentRepository
        val selected = repositories.firstOrNull { it == previous } ?: repositories.firstOrNull()
        updatingRepositories = true
        try {
            repoCombo.removeAllItems()
            repositories.forEach(repoCombo::addItem)
            repoCombo.selectedItem = selected
        } finally {
            updatingRepositories = false
        }
        repoCombo.isVisible = repositories.size > 1
        if (selected == null) {
            currentRepository = null
            currentRunner = null
            currentLoader = null
            snapshot = GraphSnapshot()
            (body.layout as CardLayout).show(body, "empty")
            footerLeft.text = ""
            footerRight.text = ""
            return
        }
        (body.layout as CardLayout).show(body, "graph")
        if (selected != currentRepository) setRepository(selected)
    }

    private fun setRepository(repository: GitRepository) {
        currentRepository = repository
        val newRunner = GitRunner(project, repository.root)
        currentRunner = newRunner
        currentLoader = GitDataLoader(newRunner)
        limit = settings.pageSize
        options = buildOptions(LoadOptions())
        updateFilterButtons()
        table.clearSelection()
        details.show(DetailTarget.None)
        reload()
    }

    override fun reload() {
        if (disposed) return
        val activeLoader = currentLoader ?: return
        val gitDir = currentRepository?.repositoryFiles?.worktreeGitDir?.toNioPath()
        val request = buildOptions()
        options = request
        val generation = ++loadGeneration
        loading = true
        needsReload = false
        footerRight.text = "Loading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching { activeLoader.load(request, gitDir) }
            ApplicationManager.getApplication().invokeLater({
                if (disposed || generation != loadGeneration || project.isDisposed) return@invokeLater
                loading = false
                result.onSuccess { applySnapshot(it) }
                result.onFailure { error -> footerRight.text = "Load failed: ${error.message?.lineSequence()?.firstOrNull().orEmpty()}" }
            }, ModalityState.any())
        }
    }

    private fun loadMore() {
        limit += settings.pageSize
        reload()
    }

    private fun sameContent(a: GraphSnapshot, b: GraphSnapshot): Boolean =
        a.commits.size == b.commits.size &&
            a.commits.indices.all { a.commits[it].hash == b.commits[it].hash && a.commits[it].subject == b.commits[it].subject } &&
            a.refs == b.refs &&
            a.statusFiles == b.statusFiles &&
            a.state == b.state &&
            a.worktrees == b.worktrees &&
            a.remotes == b.remotes &&
            a.submodules == b.submodules &&
            a.hasMore == b.hasMore

    internal fun useLoader(loader: GitDataLoader) {
        currentLoader = loader
        (body.layout as CardLayout).show(body, "graph")
    }

    internal fun selectRow(row: Int) = table.setRowSelectionInterval(row, row)

    fun applySnapshot(newSnapshot: GraphSnapshot) {
        val unchanged = sameContent(snapshot, newSnapshot)
        val selectedHashes = table.selectedCommits().map { it.hash }
        val viewRect = table.visibleRect
        snapshot = newSnapshot
        updateFooter()
        if (unchanged) return

        suppressSelection = true
        table.show(newSnapshot)
        table.revalidate()
        selectedHashes.forEach { hash ->
            newSnapshot.rowByHash[hash]?.let { table.addRowSelectionInterval(it, it) }
        }
        table.scrollRectToVisible(viewRect)
        suppressSelection = false
        sidebar.show(newSnapshot)
        updateBanner()
        updateSearch(false)
        if (selectedHashes.isEmpty() || selectedHashes.none { it in newSnapshot.rowByHash }) {
            details.show(DetailTarget.None)
        } else {
            updateDetails()
        }
    }

    private fun updateDetails() {
        val selected = table.selectedCommits()
        when {
            selected.isEmpty() -> details.show(DetailTarget.None)
            selected.size == 2 -> {
                val rows = selected.sortedByDescending { snapshot.rowByHash[it.hash] ?: 0 }
                val older = rows[0]
                val newer = rows[1]
                if (older.kind == CommitKind.UNCOMMITTED || newer.kind == CommitKind.UNCOMMITTED) {
                    details.show(DetailTarget.Compare(if (older.kind == CommitKind.UNCOMMITTED) newer.hash else older.hash, WORKING_TREE))
                } else {
                    details.show(DetailTarget.Compare(older.hash, newer.hash))
                }
            }
            else -> details.show(DetailTarget.Single(selected.last()))
        }
    }

    private fun updateFooter() {
        val s = snapshot
        val normal = s.commits.count { it.kind == CommitKind.NORMAL }
        val current = s.localBranches.firstOrNull { it.current }
        val branch = when {
            current != null -> buildString {
                append(current.name)
                if (current.ahead > 0) append(" ↑").append(current.ahead)
                if (current.behind > 0) append(" ↓").append(current.behind)
            }
            s.head != null -> "detached at ${s.head.take(8)}"
            else -> "no commits"
        }
        footerLeft.text = listOf(
            branch,
            count(normal, "commit", if (s.hasMore) "+" else ""),
            count(s.localBranches.size, "branch"),
            count(s.tags.size, "tag"),
            count(s.stashes.size, "stash"),
        ).joinToString("  ·  ")
        footerRight.text = "Loaded in ${s.loadMillis} ms"
    }

    private fun count(value: Int, noun: String, suffix: String = ""): String {
        val plural = if (value == 1) noun else if (noun.endsWith("h")) "${noun}es" else "${noun}s"
        return "${String.format("%,d", value)}$suffix $plural"
    }

    private fun updateBanner() {
        banner.removeAll()
        val state = snapshot.state
        val operation = state.operation
        if (operation == RepoOperation.NONE && !state.bisecting && state.conflicts == 0) {
            banner.isVisible = false
            return
        }
        val content = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), JBUI.scale(4)))
        content.isOpaque = false
        val hasConflicts = state.conflicts > 0
        banner.background = if (hasConflicts) Palette.bannerConflict else Palette.bannerInfo
        banner.isOpaque = true
        val message = buildString {
            if (operation != RepoOperation.NONE) append(operation.label).append(" in progress") else if (state.bisecting) append("Bisect in progress")
            if (hasConflicts) {
                if (isNotEmpty()) append("  ·  ")
                append(if (state.conflicts == 1) "1 conflicted file" else "${state.conflicts} conflicted files")
            }
        }
        val label = JBLabel(message)
        label.font = label.font.deriveFont(Font.BOLD)
        label.icon = if (hasConflicts) AllIcons.General.Warning else AllIcons.General.Information
        content.add(label)
        if (hasConflicts) content.add(ActionLink("Resolve…") { ops.resolveConflicts() })
        if (operation != RepoOperation.NONE) {
            content.add(ActionLink("Continue") { ops.continueOperation(operation) })
            if (operation != RepoOperation.MERGE) content.add(ActionLink("Skip") { ops.skipOperation(operation) })
            content.add(ActionLink("Abort") { ops.abortOperation(operation) })
        }
        if (state.bisecting) {
            content.add(ActionLink("Good") { ops.bisectMark("good", null) })
            content.add(ActionLink("Bad") { ops.bisectMark("bad", null) })
            content.add(ActionLink("Skip") { ops.bisectMark("skip", null) })
            content.add(ActionLink("End Bisect") { ops.bisectReset() })
        }
        banner.add(content, BorderLayout.CENTER)
        banner.isVisible = true
        banner.revalidate()
        banner.repaint()
    }

    private fun updateSearch(jump: Boolean) {
        val query = search.text
        if (query.isEmpty()) {
            matches = emptyList()
            matchIndex = -1
            matchLabel.text = ""
            table.highlight.ranges = emptyMap()
            table.highlight.current = null
            table.repaint()
            return
        }
        val flags = if (caseToggle.isSelected) 0 else Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        val pattern = try {
            if (regexToggle.isSelected) Pattern.compile(query, flags) else Pattern.compile(Pattern.quote(query), flags)
        } catch (_: PatternSyntaxException) {
            matchLabel.text = "Invalid regex"
            return
        }
        val rows = ArrayList<Int>()
        val ranges = HashMap<String, List<IntRange>>()
        snapshot.commits.forEachIndexed { index, commit ->
            val subjectRanges = ArrayList<IntRange>()
            val matcher = pattern.matcher(commit.subject)
            while (matcher.find()) {
                if (matcher.end() == matcher.start()) break
                subjectRanges.add(matcher.start() until matcher.end())
            }
            val hashMatches = commit.kind != CommitKind.UNCOMMITTED && pattern.matcher(commit.hash).find()
            val other = hashMatches || pattern.matcher(commit.author).find() ||
                pattern.matcher(commit.authorEmail).find() ||
                snapshot.refsByHash[commit.hash].orEmpty().any { pattern.matcher(it.name).find() }
            if (subjectRanges.isNotEmpty() || other) {
                rows.add(index)
                if (subjectRanges.isNotEmpty()) ranges[commit.hash] = subjectRanges
            }
        }
        matches = rows
        table.highlight.ranges = ranges
        if (jump) {
            matchIndex = if (rows.isEmpty()) -1 else 0
            revealMatch()
        } else {
            matchIndex = matchIndex.coerceAtMost(rows.lastIndex)
            updateMatchLabel()
        }
        table.repaint()
    }

    private fun stepMatch(delta: Int) {
        if (matches.isEmpty()) return
        matchIndex = Math.floorMod(matchIndex + delta, matches.size)
        revealMatch()
    }

    private fun revealMatch() {
        updateMatchLabel()
        val row = matches.getOrNull(matchIndex)
        table.highlight.current = row?.let { table.commitAt(it)?.hash }
        if (row != null) {
            table.setRowSelectionInterval(row, row)
            table.scrollRectToVisible(table.getCellRect(row, 0, true))
        }
        table.repaint()
    }

    private fun updateMatchLabel() {
        val suffix = if (snapshot.hasMore) " in loaded" else ""
        matchLabel.text = when {
            matches.isEmpty() -> "No matches$suffix"
            else -> "${matchIndex + 1} of ${matches.size}$suffix"
        }
    }

    private fun updateFilterButtons() {
        branchButton.text = when (options.branches.size) {
            0 -> "All Branches"
            1 -> options.branches.first().removePrefix("refs/heads/").removePrefix("refs/remotes/").removePrefix("refs/tags/")
            else -> "${options.branches.size} Branches"
        }
        val active = listOf(options.author, options.grep, options.path, options.since).count { it.isNotBlank() }
        filterButton.text = if (active == 0) "Filter" else "Filter ($active)"
    }

    private fun showBranchPopup() {
        val refs = (snapshot.localBranches + snapshot.remoteBranches + snapshot.tags)
        val checked = options.branches.toMutableSet()
        val list = CheckBoxList<String>()
        val field = SearchTextField(false)
        fun fill() {
            val filter = field.text.trim().lowercase()
            list.clear()
            refs.filter { it.name.lowercase().contains(filter) }.forEach { ref ->
                val prefix = when (ref.kind) {
                    RefKind.TAG -> "tag: "
                    else -> ""
                }
                list.addItem(ref.fullName, prefix + ref.name, ref.fullName in checked)
            }
        }
        list.setCheckBoxListListener { index, value ->
            val name = list.getItemAt(index)
            if (name != null) {
                if (value) checked.add(name) else checked.remove(name)
            }
        }
        field.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = fill()
            override fun removeUpdate(e: DocumentEvent) = fill()
            override fun changedUpdate(e: DocumentEvent) = fill()
        })
        fill()
        val panel = JPanel(BorderLayout(0, JBUI.scale(6)))
        panel.border = JBUI.Borders.empty(8)
        panel.add(field, BorderLayout.NORTH)
        val scroll = ScrollPaneFactory.createScrollPane(list, true)
        scroll.preferredSize = Dimension(JBUI.scale(320), JBUI.scale(320))
        panel.add(scroll, BorderLayout.CENTER)
        val apply = JButton("Apply")
        val all = JButton("Show All")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        buttons.add(all)
        buttons.add(apply)
        panel.add(buttons, BorderLayout.SOUTH)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field.textEditor)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setResizable(true)
            .createPopup()
        apply.addActionListener {
            popup.closeOk(null)
            setBranches(checked.toSet())
        }
        all.addActionListener {
            popup.closeOk(null)
            setBranches(emptySet())
        }
        popup.showUnderneathOf(branchButton)
    }

    private fun setBranches(branches: Set<String>) {
        options = options.copy(branches = branches)
        updateFilterButtons()
        reload()
    }

    private fun showFilterPopup() {
        val author = ComboBox((listOf("") + snapshot.authors).toTypedArray())
        author.isEditable = true
        author.selectedItem = options.author
        val grep = JBTextField(options.grep, 26)
        val path = JBTextField(options.path, 26)
        val sinceValues = linkedMapOf("Any time" to "", "Last 24 hours" to "1.day.ago", "Last week" to "1.week.ago", "Last month" to "1.month.ago", "Last 3 months" to "3.months.ago", "Last year" to "1.year.ago")
        val since = ComboBox(sinceValues.keys.toTypedArray())
        since.selectedItem = sinceValues.entries.firstOrNull { it.value == options.since }?.key ?: "Any time"
        val apply = JButton("Apply")
        val clear = JButton("Clear")
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0))
        buttons.add(clear)
        buttons.add(apply)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Author:", author)
            .addLabeledComponent("Message:", grep)
            .addLabeledComponent("Path:", path)
            .addLabeledComponent("Since:", since)
            .addComponent(buttons)
            .panel
        panel.border = JBUI.Borders.empty(10)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, grep)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
        apply.addActionListener {
            popup.closeOk(null)
            setFilters(author.editor.item?.toString().orEmpty().trim(), grep.text.trim(), path.text.trim(), sinceValues[since.selectedItem].orEmpty())
        }
        clear.addActionListener {
            popup.closeOk(null)
            setFilters("", "", "", "")
        }
        popup.showUnderneathOf(filterButton)
    }

    private fun setFilters(author: String, grep: String, path: String, since: String) {
        options = options.copy(author = author, grep = grep, path = path, since = since)
        updateFilterButtons()
        reload()
    }

    override fun selectCommit(hash: String) {
        val row = snapshot.rowByHash[hash]
        if (row == null) {
            footerRight.text = "${hash.take(8)} is not in the loaded history"
            return
        }
        table.setRowSelectionInterval(row, row)
        val rect = table.getCellRect(row, 0, true)
        rect.height = (rect.height * 5).coerceAtMost(table.height - rect.y)
        table.scrollRectToVisible(rect)
    }

    override fun compare(left: String, right: String) {
        details.isVisible = true
        details.show(DetailTarget.Compare(left, right))
    }

    override fun compareWithWorkingTree(hash: String) = compare(hash, WORKING_TREE)

    override fun filterByPath(path: String) {
        options = options.copy(path = path)
        updateFilterButtons()
        reload()
    }

    override fun filterByBranch(fullName: String) = setBranches(setOf(fullName))

    override fun dispose() {
        disposed = true
        details.dispose()
    }

    private companion object {
        const val LOAD_AHEAD_ROWS = 80
        const val REPOSITORY_DELAY_MS = 250
        const val CHANGES_DELAY_MS = 1200
    }
}

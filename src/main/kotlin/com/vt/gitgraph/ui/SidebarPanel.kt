package com.vt.gitgraph.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.GraphSnapshot
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.SubmoduleInfo
import com.vt.gitgraph.model.WorktreeInfo
import com.vt.gitgraph.ops.GitOperations
import com.vt.gitgraph.ops.GraphMenus
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

private sealed interface SidebarItem {
    val key: String

    data class Section(val title: String, val menuKey: String, val count: Int) : SidebarItem {
        override val key: String get() = "section:$menuKey"
    }

    data class Folder(val name: String, override val key: String) : SidebarItem

    data class Ref(val ref: GitRef, val label: String) : SidebarItem {
        override val key: String get() = ref.fullName
    }

    data class Remote(val remote: RemoteInfo) : SidebarItem {
        override val key: String get() = "remote:${remote.name}"
    }

    data class Worktree(val worktree: WorktreeInfo) : SidebarItem {
        override val key: String get() = "worktree:${worktree.path}"
    }

    data class Submodule(val submodule: SubmoduleInfo) : SidebarItem {
        override val key: String get() = "submodule:${submodule.path}"
    }
}

class SidebarPanel(private val view: GraphView, private val ops: GitOperations, private val menus: GraphMenus) : JPanel(BorderLayout()) {
    private val treeModel = DefaultTreeModel(DefaultMutableTreeNode())
    private val tree = Tree(treeModel)
    private val search = SearchTextField(false)
    private var snapshot = GraphSnapshot()
    private val expanded = hashSetOf("section:Local", "section:Remote")

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.cellRenderer = ItemRenderer()
        tree.rowHeight = JBUI.scale(24)
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent) {
                itemOf(event.path)?.let { expanded.add(it.key) }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent) {
                itemOf(event.path)?.let { expanded.remove(it.key) }
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                val item = tree.getPathForLocation(e.x, e.y)?.let(::itemOf) ?: return
                when (item) {
                    is SidebarItem.Ref -> {
                        if (e.clickCount == 2) activate(item.ref) else view.selectCommit(item.ref.hash)
                    }
                    is SidebarItem.Worktree -> item.worktree.head?.let(view::selectCommit)
                    else -> Unit
                }
            }
        })
        search.textEditor.emptyText.text = "Filter branches, tags, stashes"
        search.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        val top = JPanel(BorderLayout())
        top.border = JBUI.Borders.empty(6)
        top.add(search, BorderLayout.CENTER)
        add(top, BorderLayout.NORTH)
        add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
        minimumSize = JBUI.size(160, 100)
    }

    private fun itemOf(path: TreePath?): SidebarItem? =
        (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? SidebarItem

    private fun activate(ref: GitRef) {
        when (ref.kind) {
            RefKind.LOCAL -> if (!ref.current) ops.checkout(ref.name)
            RefKind.REMOTE -> ops.checkoutRemote(ref)
            RefKind.TAG -> ops.checkout("refs/tags/${ref.name}", ref.name)
            RefKind.STASH -> Unit
        }
    }

    private fun popup(e: MouseEvent) {
        if (!e.isPopupTrigger) return
        val path = tree.getClosestPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val group = when (val item = itemOf(path)) {
            is SidebarItem.Section -> menus.sectionMenu(item.menuKey)
            is SidebarItem.Ref -> menus.refMenu(item.ref)
            is SidebarItem.Remote -> menus.remoteMenu(item.remote)
            is SidebarItem.Worktree -> menus.worktreeMenu(item.worktree)
            is SidebarItem.Submodule -> menus.submoduleMenu(item.submodule)
            else -> null
        } ?: return
        menus.show(group, tree, e.x, e.y)
    }

    fun show(newSnapshot: GraphSnapshot) {
        snapshot = newSnapshot
        rebuild()
    }

    private fun rebuild() {
        val filter = search.text.trim().lowercase()
        val matches = { text: String -> filter.isEmpty() || text.lowercase().contains(filter) }
        val root = DefaultMutableTreeNode()

        val locals = snapshot.localBranches.filter { matches(it.name) }
        section(root, "Local Branches", "Local", locals.size) { node -> addNested(node, locals, "local") { it.name } }

        section(root, "Remotes", "Remote", snapshot.remotes.size) { node ->
            snapshot.remotes.forEach { remote ->
                val branches = snapshot.remoteBranches.filter { snapshot.remoteBranchOf(it.name)?.remote == remote.name && matches(it.name) }
                if (filter.isEmpty() || branches.isNotEmpty() || matches(remote.name)) {
                    val child = DefaultMutableTreeNode(SidebarItem.Remote(remote))
                    addNested(child, branches, "remote:${remote.name}") { snapshot.remoteBranchOf(it.name)?.branch ?: it.name }
                    node.add(child)
                }
            }
        }

        val tags = snapshot.tags.filter { matches(it.name) }.sortedByDescending { it.name }
        section(root, "Tags", "Tags", tags.size) { node ->
            tags.forEach { node.add(DefaultMutableTreeNode(SidebarItem.Ref(it, it.name))) }
        }

        val stashes = snapshot.stashes.filter { matches(it.name) || matches(it.subject) }
        section(root, "Stashes", "Stashes", stashes.size) { node ->
            stashes.forEach { node.add(DefaultMutableTreeNode(SidebarItem.Ref(it, it.subject.ifEmpty { it.name }))) }
        }

        val worktrees = snapshot.worktrees.filter { matches(it.path) || matches(it.branch.orEmpty()) }
        section(root, "Worktrees", "Worktrees", worktrees.size) { node ->
            worktrees.forEach { node.add(DefaultMutableTreeNode(SidebarItem.Worktree(it))) }
        }

        if (snapshot.submodules.isNotEmpty()) {
            val submodules = snapshot.submodules.filter { matches(it.path) }
            section(root, "Submodules", "Submodules", submodules.size) { node ->
                submodules.forEach { node.add(DefaultMutableTreeNode(SidebarItem.Submodule(it))) }
            }
        }

        val remembered = expanded.toSet()
        treeModel.setRoot(root)
        restoreExpansion(root, TreePath(root), remembered, filter.isNotEmpty())
        expanded.clear()
        expanded.addAll(remembered)
    }

    private fun restoreExpansion(node: DefaultMutableTreeNode, path: TreePath, keys: Set<String>, expandAll: Boolean) {
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            if (child.isLeaf) continue
            val childPath = path.pathByAddingChild(child)
            val item = child.userObject as? SidebarItem
            if (expandAll || (item != null && item.key in keys)) tree.expandPath(childPath)
            restoreExpansion(child, childPath, keys, expandAll)
        }
    }

    private fun section(root: DefaultMutableTreeNode, title: String, key: String, count: Int, fill: (DefaultMutableTreeNode) -> Unit): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(SidebarItem.Section(title, key, count))
        fill(node)
        root.add(node)
        return node
    }

    private fun addNested(parent: DefaultMutableTreeNode, refs: List<GitRef>, prefix: String, nameOf: (GitRef) -> String) {
        refs.sortedWith(compareByDescending<GitRef> { it.current }.thenBy { nameOf(it) }).forEach { ref ->
            val parts = nameOf(ref).split('/')
            var current = parent
            var key = prefix
            parts.dropLast(1).forEach { part ->
                key += "/$part"
                val folderKey = key
                val existing = (0 until current.childCount).asSequence()
                    .map { current.getChildAt(it) as DefaultMutableTreeNode }
                    .firstOrNull { (it.userObject as? SidebarItem.Folder)?.key == folderKey }
                current = existing ?: DefaultMutableTreeNode(SidebarItem.Folder(part, folderKey)).also(current::add)
            }
            current.add(DefaultMutableTreeNode(SidebarItem.Ref(ref, parts.last())))
        }
    }

    private class ItemRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            when (val item = (value as? DefaultMutableTreeNode)?.userObject as? SidebarItem) {
                is SidebarItem.Section -> {
                    icon = when (item.menuKey) {
                        "Local" -> AllIcons.Vcs.Branch
                        "Remote" -> AllIcons.Nodes.WebFolder
                        "Tags" -> AllIcons.Nodes.Tag
                        "Stashes" -> AllIcons.Vcs.ShelveSilent
                        "Worktrees" -> AllIcons.Nodes.Folder
                        else -> AllIcons.Nodes.Module
                    }
                    append(item.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ${item.count}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is SidebarItem.Folder -> {
                    icon = AllIcons.Nodes.Folder
                    append(item.name)
                }
                is SidebarItem.Ref -> renderRef(item)
                is SidebarItem.Remote -> {
                    icon = AllIcons.Nodes.WebFolder
                    append(item.remote.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    toolTipText = item.remote.fetchUrl
                }
                is SidebarItem.Worktree -> {
                    val worktree = item.worktree
                    icon = AllIcons.Nodes.Folder
                    append(worktree.path.substringAfterLast('/'), if (worktree.main) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ${worktree.branch ?: worktree.head?.take(8).orEmpty()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    if (worktree.locked) append("  locked", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.tag))
                    if (worktree.prunable) append("  prunable", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.deleted))
                    toolTipText = worktree.path
                }
                is SidebarItem.Submodule -> {
                    val submodule = item.submodule
                    icon = AllIcons.Nodes.Module
                    append(submodule.path)
                    val state = when (submodule.state) {
                        '-' -> "not initialized"
                        '+' -> "out of date"
                        'U' -> "conflicts"
                        else -> submodule.describe
                    }
                    append("  $state", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                null -> Unit
            }
        }

        private fun renderRef(item: SidebarItem.Ref) {
            val ref = item.ref
            icon = when (ref.kind) {
                RefKind.LOCAL -> if (ref.current) AllIcons.Actions.Checked else AllIcons.Vcs.Branch
                RefKind.REMOTE -> AllIcons.Vcs.Branch
                RefKind.TAG -> AllIcons.Nodes.Tag
                RefKind.STASH -> AllIcons.Vcs.ShelveSilent
            }
            append(item.label, if (ref.current) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            if (ref.ahead > 0) append("  ↑${ref.ahead}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.added))
            if (ref.behind > 0) append("  ↓${ref.behind}", SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, Palette.conflict))
            if (ref.upstreamGone) append("  gone", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, Palette.deleted))
            toolTipText = if (ref.kind == RefKind.STASH) ref.name else ref.fullName
        }
    }

    init {
        background = UIUtil.getPanelBackground()
    }
}

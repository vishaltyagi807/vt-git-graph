package com.vt.gitgraph.ops

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.SubmoduleInfo
import com.vt.gitgraph.model.WORKING_TREE
import com.vt.gitgraph.model.WorktreeInfo
import com.vt.gitgraph.ui.GraphView
import java.awt.Component
import java.awt.datatransfer.StringSelection
import javax.swing.Icon

class GraphMenus(private val view: GraphView, private val ops: GitOperations) {
    private val snapshot get() = view.snapshot

    private fun act(text: String, icon: Icon? = null, handler: () -> Unit): AnAction =
        DumbAwareAction.create(text) { handler() }.also { action ->
            if (icon != null) action.templatePresentation.icon = icon
        }

    private fun copy(text: String) = CopyPasteManager.getInstance().setContents(StringSelection(text))

    private fun group(text: String, vararg actions: AnAction?): DefaultActionGroup {
        val result = DefaultActionGroup(text, true)
        actions.filterNotNull().forEach(result::add)
        return result
    }

    fun show(group: DefaultActionGroup, component: Component, x: Int, y: Int) {
        ActionManager.getInstance().createActionPopupMenu("VtGitGraph", group).component.show(component, x, y)
    }

    fun commitMenu(commit: Commit, selection: List<Commit>): DefaultActionGroup {
        if (commit.kind == CommitKind.UNCOMMITTED) return uncommittedMenu()
        if (commit.kind == CommitKind.STASH) return stashMenu(commit.stashName ?: "stash@{0}", commit)
        val normal = selection.filter { it.kind == CommitKind.NORMAL }
        if (normal.size >= 2) return multiCommitMenu(normal)
        val g = DefaultActionGroup()
        val isHead = commit.hash == snapshot.head
        g.add(act("Checkout…", AllIcons.Actions.CheckOut) { ops.checkoutCommit(commit) })
        g.add(act("New Branch…", AllIcons.Vcs.Branch) { ops.createBranch(commit.hash, commit.shortHash) })
        g.add(act("New Tag…") { ops.createTag(commit) })
        g.addSeparator()
        if (!isHead) {
            g.add(act("Cherry-pick…") { ops.cherryPick(listOf(commit)) })
            g.add(act("Merge into Current Branch…", AllIcons.Vcs.Merge) { ops.merge(commit.hash, commit.shortHash) })
            g.add(act("Rebase Current Branch onto Here…") { ops.rebaseOnto(commit.hash, commit.shortHash) })
        }
        g.add(act("Revert…", AllIcons.Actions.Rollback) { ops.revert(commit) })
        g.addSeparator()
        g.add(act("Interactive Rebase from Here…") { ops.interactiveRebase(commit) })
        g.add(act("Edit Message…", AllIcons.Actions.Edit) { ops.amendMessage(commit) })
        g.add(act("Drop Commit…", AllIcons.General.Remove) { ops.dropCommit(commit) })
        if (isHead && commit.parents.isNotEmpty()) g.add(act("Undo Last Commit…") { ops.undoLastCommit() })
        g.add(act("Reset Current Branch to Here…", AllIcons.Actions.Undo) { ops.reset(commit.hash, commit.shortHash) })
        g.addSeparator()
        g.add(act("Compare with Working Tree") { view.compareWithWorkingTree(commit.hash) })
        g.add(
            group(
                "Bisect",
                act("Start Here, Marked Bad") { ops.bisectStart(commit, null) }.takeIf { !snapshot.state.bisecting },
                act("Mark Bad") { ops.bisectMark("bad", commit.hash) }.takeIf { snapshot.state.bisecting },
                act("Mark Good") { ops.bisectMark("good", commit.hash) }.takeIf { snapshot.state.bisecting },
                act("Skip") { ops.bisectMark("skip", commit.hash) }.takeIf { snapshot.state.bisecting },
            ),
        )
        g.add(
            group(
                "Export",
                act("Save as Patch…") { ops.savePatch(commit) },
                act("Save as Archive…") { ops.archive(commit) },
                act("New Worktree Here…") { ops.addWorktree(commit.hash) },
            ),
        )
        g.addSeparator()
        g.add(
            group(
                "Copy",
                act("Full Hash") { copy(commit.hash) },
                act("Short Hash") { copy(commit.shortHash) },
                act("Subject") { copy(commit.subject) },
                act("Hash and Subject") { copy("${commit.shortHash} ${commit.subject}") },
            ),
        )
        return g
    }

    private fun multiCommitMenu(commits: List<Commit>): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Cherry-pick ${commits.size} Commits…") { ops.cherryPick(commits) })
        if (commits.size == 2) {
            val ordered = commits.sortedByDescending { view.snapshot.rowByHash[it.hash] ?: 0 }
            g.add(act("Compare Selected Commits") { view.compare(ordered[0].hash, ordered[1].hash) })
            g.add(act("Start Bisect (Newer Bad, Older Good)") { ops.bisectStart(ordered[1], ordered[0]) })
        }
        g.addSeparator()
        g.add(act("Copy Hashes") { copy(commits.joinToString("\n") { it.hash }) })
        g.add(act("Copy Subjects") { copy(commits.joinToString("\n") { it.subject }) })
        return g
    }

    fun uncommittedMenu(): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Commit…", AllIcons.Actions.Commit) { ops.openCommitWindow() })
        g.add(act("Stage All") { ops.stageAll() })
        g.add(act("Unstage All") { ops.unstageAll() })
        g.addSeparator()
        g.add(act("Stash…") { ops.stashChanges() })
        g.addSeparator()
        g.add(act("Discard All Changes…", AllIcons.General.Remove) { ops.discardAll() })
        g.add(act("Clean Untracked Files…") { ops.cleanUntracked() })
        if (snapshot.state.conflicts > 0) {
            g.addSeparator()
            g.add(act("Resolve Conflicts…") { ops.resolveConflicts() })
        }
        return g
    }

    fun stashMenu(name: String, commit: Commit?): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Apply") { ops.stashApply(name) })
        g.add(act("Apply and Restore Index") { ops.stashApply(name, restoreIndex = true) })
        g.add(act("Pop") { ops.stashPop(name) })
        g.add(act("Branch from Stash…", AllIcons.Vcs.Branch) { ops.stashBranch(name) })
        g.addSeparator()
        g.add(act("Drop…", AllIcons.General.Remove) { ops.stashDrop(name) })
        if (commit != null) {
            g.addSeparator()
            g.add(act("Copy Hash") { copy(commit.hash) })
        }
        return g
    }

    fun refMenu(ref: GitRef): DefaultActionGroup = when (ref.kind) {
        RefKind.LOCAL -> localBranchMenu(ref)
        RefKind.REMOTE -> remoteBranchMenu(ref)
        RefKind.TAG -> tagMenu(ref)
        RefKind.STASH -> stashMenu(ref.name, null)
    }

    private fun localBranchMenu(ref: GitRef): DefaultActionGroup {
        val g = DefaultActionGroup()
        val current = ref.current
        if (!current) g.add(act("Checkout", AllIcons.Actions.CheckOut) { ops.checkout(ref.name) })
        g.add(act("New Branch from Here…", AllIcons.Vcs.Branch) { ops.createBranch(ref.name, ref.name) })
        g.add(act("Rename…", AllIcons.Actions.Edit) { ops.renameBranch(ref) })
        g.addSeparator()
        if (!current) {
            g.add(act("Merge into Current Branch…", AllIcons.Vcs.Merge) { ops.merge(ref.name, ref.name) })
            g.add(act("Rebase Current Branch onto This…") { ops.rebaseOnto(ref.name, ref.name) })
            g.add(act("Compare with Current Branch") { compareWithHead(ref) })
            g.addSeparator()
        }
        g.add(act("Push…", AllIcons.Vcs.Push) { ops.push(ref) })
        if (current) g.add(act("Pull…", AllIcons.Actions.CheckOut) { ops.pullCurrent() })
        if (!current && ref.upstream != null) g.add(act("Fetch into Branch") { ops.fetchInto(ref) })
        g.add(act("Set Upstream…") { ops.setUpstream(ref) })
        g.addSeparator()
        g.add(act("Show Only This Branch") { view.filterByBranch(ref.fullName) })
        g.add(act("Copy Name") { copy(ref.name) })
        if (!current) {
            g.addSeparator()
            g.add(act("Delete…", AllIcons.General.Remove) { ops.deleteBranch(ref) })
        }
        return g
    }

    private fun remoteBranchMenu(ref: GitRef): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Checkout as Local Branch…", AllIcons.Actions.CheckOut) { ops.checkoutRemote(ref) })
        g.add(act("New Branch from Here…", AllIcons.Vcs.Branch) { ops.createBranch(ref.name, ref.name) })
        g.addSeparator()
        g.add(act("Merge into Current Branch…", AllIcons.Vcs.Merge) { ops.merge(ref.name, ref.name) })
        g.add(act("Rebase Current Branch onto This…") { ops.rebaseOnto(ref.name, ref.name) })
        g.add(act("Compare with Current Branch") { compareWithHead(ref) })
        g.addSeparator()
        g.add(act("Show Only This Branch") { view.filterByBranch(ref.fullName) })
        g.add(act("Copy Name") { copy(ref.name) })
        g.addSeparator()
        g.add(act("Delete from Remote…", AllIcons.General.Remove) { ops.deleteRemoteBranch(ref) })
        return g
    }

    private fun tagMenu(ref: GitRef): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Checkout", AllIcons.Actions.CheckOut) { ops.checkout("refs/tags/${ref.name}", ref.name) })
        g.add(act("New Branch from Here…", AllIcons.Vcs.Branch) { ops.createBranch(ref.fullName, ref.name) })
        g.addSeparator()
        g.add(act("Merge into Current Branch…", AllIcons.Vcs.Merge) { ops.merge(ref.fullName, ref.name) })
        g.add(act("Compare with Current Branch") { compareWithHead(ref) })
        g.add(act("Push Tag…", AllIcons.Vcs.Push) { ops.pushTag(ref) })
        g.addSeparator()
        g.add(act("Copy Name") { copy(ref.name) })
        g.addSeparator()
        g.add(act("Delete…", AllIcons.General.Remove) { ops.deleteTag(ref) })
        return g
    }

    private fun compareWithHead(ref: GitRef) {
        val head = snapshot.head ?: return
        view.compare(head, ref.hash)
    }

    fun remoteMenu(remote: RemoteInfo): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Fetch", AllIcons.Vcs.Fetch) { ops.fetchRemote(remote) })
        g.add(act("Prune") { ops.pruneRemote(remote) })
        g.addSeparator()
        g.add(act("Edit URLs…", AllIcons.Actions.Edit) { ops.editRemoteUrl(remote) })
        g.add(act("Rename…") { ops.renameRemote(remote) })
        g.add(act("Copy URL") { copy(remote.fetchUrl) })
        g.addSeparator()
        g.add(act("Remove…", AllIcons.General.Remove) { ops.removeRemote(remote) })
        return g
    }

    fun worktreeMenu(worktree: WorktreeInfo): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Open in New Window") { ops.openWorktree(worktree) })
        g.add(act("Copy Path") { copy(worktree.path) })
        if (!worktree.main) {
            g.addSeparator()
            g.add(act(if (worktree.locked) "Unlock" else "Lock") { ops.toggleWorktreeLock(worktree) })
            if (worktree.path != view.repository?.root?.path) {
                g.add(act("Remove…", AllIcons.General.Remove) { ops.removeWorktree(worktree) })
            }
        }
        return g
    }

    fun submoduleMenu(submodule: SubmoduleInfo): DefaultActionGroup {
        val g = DefaultActionGroup()
        g.add(act("Update") { ops.updateSubmodule(submodule) })
        g.add(act("Copy Path") { copy(submodule.path) })
        return g
    }

    fun sectionMenu(section: String): DefaultActionGroup? {
        val g = DefaultActionGroup()
        when (section) {
            "Local" -> g.add(act("New Branch from HEAD…", AllIcons.Vcs.Branch) { ops.createBranch(snapshot.head ?: "HEAD", "HEAD") })
            "Remote" -> {
                g.add(act("Add Remote…", AllIcons.General.Add) { ops.addRemote() })
                g.add(act("Fetch All…", AllIcons.Vcs.Fetch) { ops.fetchAll() })
            }
            "Tags" -> g.add(act("Push All Tags…", AllIcons.Vcs.Push) { ops.pushAllTags() })
            "Stashes" -> {
                g.add(act("Stash Changes…") { ops.stashChanges() })
                g.add(act("Clear All Stashes…", AllIcons.General.Remove) { ops.stashClear() })
            }
            "Worktrees" -> {
                g.add(act("Add Worktree…", AllIcons.General.Add) { ops.addWorktree(null) })
                g.add(act("Prune Worktrees") { ops.pruneWorktrees() })
            }
            "Submodules" -> {
                g.add(act("Update All") { ops.updateSubmodules() })
                g.add(act("Sync All") { ops.syncSubmodules() })
            }
            else -> return null
        }
        return g
    }
}

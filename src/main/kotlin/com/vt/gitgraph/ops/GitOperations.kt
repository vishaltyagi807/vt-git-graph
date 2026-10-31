package com.vt.gitgraph.ops

import com.intellij.ide.impl.ProjectUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.AbstractVcsHelper
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.wm.ToolWindowManager
import com.vt.gitgraph.git.GitCall
import com.vt.gitgraph.git.GitResult
import com.vt.gitgraph.git.GitRunner
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.RepoOperation
import com.vt.gitgraph.model.SubmoduleInfo
import com.vt.gitgraph.model.WorktreeInfo
import com.vt.gitgraph.ui.GraphView
import com.vt.gitgraph.ui.InteractiveRebaseDialog
import com.vt.gitgraph.ui.OpDialog
import com.vt.gitgraph.ui.ReflogDialog
import git4idea.GitVcs
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

class GitOperations(private val view: GraphView) {
    private class RebaseFiles(val gitDir: Path?, val directory: Path)

    private val project get() = view.project
    private val snapshot get() = view.snapshot
    private val rebaseFiles = CopyOnWriteArrayList<RebaseFiles>()

    private fun rebaseActive(gitDir: Path?): Boolean =
        gitDir != null && (Files.exists(gitDir.resolve("rebase-merge")) || Files.exists(gitDir.resolve("rebase-apply")))

    private fun sweepRebaseFiles() {
        rebaseFiles.filter { !rebaseActive(it.gitDir) }.forEach { files ->
            files.directory.toFile().deleteRecursively()
            rebaseFiles.remove(files)
        }
    }

    private fun noRemotes(title: String) {
        Messages.showErrorDialog(project, "This repository has no remotes. Add one first.", title)
    }

    private fun urls(remote: String): List<String> = snapshot.remotes.filter { it.name == remote }.flatMap { listOf(it.fetchUrl, it.pushUrl) }.distinct()

    private fun allUrls(): List<String> = snapshot.remotes.flatMap { listOf(it.fetchUrl, it.pushUrl) }.distinct()

    private fun remoteCall(remote: String, vararg args: String) = GitCall(args.toList(), urls(remote))

    private fun confirm(title: String, message: String, ok: String): Boolean =
        Messages.showOkCancelDialog(project, message, title, ok, "Cancel", Messages.getWarningIcon()) == Messages.OK

    private fun remoteNames(): List<String> = snapshot.remotes.map { it.name }

    private fun preferredRemote(): String? = remoteNames().let { names -> names.firstOrNull { it == "origin" } ?: names.firstOrNull() }

    fun run(title: String, success: String?, vararg calls: GitCall) = run(title, success, calls.toList())

    fun run(title: String, success: String?, calls: List<GitCall>, showOutput: Boolean = false) {
        val runner = view.runner ?: return
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                var last: GitResult? = null
                try {
                    for (call in calls) {
                        indicator.text2 = "git " + call.args.joinToString(" ").take(120)
                        last = execute(runner, call)
                        if (!last.success) break
                    }
                } finally {
                    sweepRebaseFiles()
                }
                refreshRepository()
                report(runner, title, success, last, showOutput)
            }
        }.queue()
    }

    private fun execute(runner: GitRunner, call: GitCall): GitResult =
        try {
            runner.write(call)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            GitResult(false, "", e.message.orEmpty())
        }

    private fun refreshRepository() {
        val repository = view.repository ?: return
        repository.update()
        VfsUtil.markDirtyAndRefresh(true, true, false, repository.root)
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        ApplicationManager.getApplication().invokeLater({ view.reload() }, ModalityState.any())
    }

    private fun display(text: String): String {
        val shortened = if (text.length > MAX_NOTIFICATION_CHARS) text.take(MAX_NOTIFICATION_CHARS) + "\n…" else text
        return OpDialog.escape(shortened).replace("\n", "<br/>")
    }

    private fun hasConflicts(runner: GitRunner): Boolean =
        try {
            runner.read(listOf("diff", "--name-only", "--diff-filter=U")).isNotEmpty()
        } catch (e: VcsException) {
            false
        }

    private fun report(runner: GitRunner, title: String, success: String?, result: GitResult?, showOutput: Boolean) {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("VT Git Graph")
        if (result == null) return
        val safeTitle = OpDialog.escape(title)
        if (result.success) {
            val output = result.combined.trim()
            when {
                showOutput && output.isNotEmpty() -> group.createNotification(safeTitle, display(output), NotificationType.INFORMATION).notify(project)
                success != null -> group.createNotification(OpDialog.escape(success), NotificationType.INFORMATION).notify(project)
            }
            return
        }
        val text = result.combined.ifBlank { "Git reported a failure with no further details." }
        val conflicted = hasConflicts(runner) || text.contains("CONFLICT") || text.contains("could not apply")
        val notification = group.createNotification(
            if (conflicted) "$safeTitle stopped on conflicts" else "$safeTitle failed",
            display(text),
            if (conflicted) NotificationType.WARNING else NotificationType.ERROR,
        )
        if (conflicted) notification.addAction(NotificationAction.createSimple("Resolve Conflicts") { resolveConflicts() })
        notification.notify(project)
    }

    private fun <T> background(title: String, load: () -> T, then: (T) -> Unit) {
        object : Task.Backgroundable(project, title, true) {
            private var value: Result<T>? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                value = try {
                    Result.success(load())
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }

            override fun onSuccess() {
                value?.onSuccess(then)?.onFailure { error ->
                    Messages.showErrorDialog(project, error.message ?: "Unknown error", title)
                }
            }
        }.queue()
    }

    fun checkout(target: String, label: String = target) {
        run("Checkout $label", null, GitCall("checkout", target, "--"))
    }

    fun checkoutCommit(commit: Commit) {
        if (!confirm("Checkout Commit", "Check out ${commit.shortHash}? HEAD will be detached until you switch to a branch.", "Checkout")) return
        checkout(commit.hash, commit.shortHash)
    }

    fun checkoutRemote(ref: GitRef) {
        val branch = snapshot.remoteBranchOf(ref.name)?.branch ?: ref.remoteBranch ?: ref.name
        val dialog = OpDialog(project, "Checkout Remote Branch", "Checkout", "Create a local branch that tracks ${ref.name}.")
        val existing = snapshot.localBranches.map { it.name }
        val name = dialog.text("Local branch:", branch) { OpDialog.refName(it) }
        val track = dialog.check("Track the remote branch", true)
        if (!dialog.open()) return
        val local = name.text.trim()
        if (local in existing) {
            checkout(local)
        } else if (track.isSelected) {
            run("Checkout $local", null, GitCall("checkout", "-b", local, "--track", ref.name))
        } else {
            run("Checkout $local", null, GitCall("checkout", "-b", local, "--no-track", ref.name))
        }
    }

    fun createBranch(startPoint: String, label: String) {
        val dialog = OpDialog(project, "Create Branch", "Create", "New branch starting at $label.")
        val name = dialog.text("Branch name:") { OpDialog.refName(it) }
        val checkout = dialog.check("Check out the new branch", true)
        val force = dialog.check("Overwrite if the branch already exists", false)
        if (!dialog.open()) return
        val branch = name.text.trim()
        val call = when {
            checkout.isSelected && force.isSelected -> GitCall("checkout", "-B", branch, startPoint)
            checkout.isSelected -> GitCall("checkout", "-b", branch, startPoint)
            force.isSelected -> GitCall("branch", "--force", branch, startPoint)
            else -> GitCall("branch", branch, startPoint)
        }
        run("Create branch $branch", null, call)
    }

    fun renameBranch(ref: GitRef) {
        val dialog = OpDialog(project, "Rename Branch", "Rename")
        val name = dialog.text("New name:", ref.name) { OpDialog.refName(it) }
        if (!dialog.open()) return
        run("Rename branch", null, GitCall("branch", "-m", ref.name, name.text.trim()))
    }

    fun deleteBranch(ref: GitRef) {
        val dialog = OpDialog(project, "Delete Branch", "Delete", "Delete local branch ${ref.name}.", danger = true)
        val force = dialog.check("Force delete, even if the branch is not merged", false)
        val upstream = ref.upstream?.let(snapshot::remoteBranchOf)
        val remote = upstream?.let { dialog.check("Also delete ${ref.upstream} from the remote", false) }
        if (!dialog.open()) return
        val calls = ArrayList<GitCall>()
        calls.add(GitCall("branch", if (force.isSelected) "-D" else "-d", ref.name))
        upstream?.takeIf { remote?.isSelected == true }?.let {
            calls.add(remoteCall(it.remote, "push", it.remote, "--delete", "refs/heads/${it.branch}"))
        }
        run("Delete branch ${ref.name}", null, calls)
    }

    fun deleteRemoteBranch(ref: GitRef) {
        val parts = snapshot.remoteBranchOf(ref.name) ?: return
        if (!confirm("Delete Remote Branch", "Delete ${parts.branch} from ${parts.remote}? This affects everyone using that remote.", "Delete")) return
        run("Delete remote branch", "Deleted ${ref.name}", remoteCall(parts.remote, "push", parts.remote, "--delete", "refs/heads/${parts.branch}"))
    }

    fun pushCurrent() {
        val current = snapshot.localBranches.firstOrNull { it.current }
        if (current == null) {
            Messages.showErrorDialog(project, "HEAD is detached, so there is no branch to push.", "Push")
            return
        }
        push(current)
    }

    fun push(ref: GitRef) {
        val remotes = remoteNames()
        if (remotes.isEmpty()) {
            noRemotes("Push")
            return
        }
        val upstream = ref.upstream?.let(snapshot::remoteBranchOf)
        val dialog = OpDialog(project, "Push Branch", "Push", "Push ${ref.name} to a remote.")
        val remote = dialog.combo("Remote:", remotes, upstream?.remote ?: preferredRemote())
        val target = dialog.text("Remote branch:", upstream?.branch ?: ref.name) { OpDialog.refName(it) }
        val setUpstream = dialog.check("Set as upstream", ref.upstream == null)
        val tags = dialog.check("Include annotated tags (--follow-tags)", false)
        val lease = dialog.check("Force with lease", false)
        val force = dialog.check("Force (overwrites remote history)", false)
        val noVerify = dialog.check("Skip pre-push hooks", false)
        if (!dialog.open()) return
        val name = remote.selectedItem as String
        if (force.isSelected && !confirm("Force Push", "Force push overwrites history on $name. Continue?", "Force Push")) return
        val args = arrayListOf("push")
        if (setUpstream.isSelected) args += "--set-upstream"
        if (tags.isSelected) args += "--follow-tags"
        if (force.isSelected) args += "--force" else if (lease.isSelected) args += "--force-with-lease"
        if (noVerify.isSelected) args += "--no-verify"
        args += name
        args += "refs/heads/${ref.name}:refs/heads/${target.text.trim()}"
        run("Push ${ref.name}", "Pushed ${ref.name} to $name", GitCall(args, urls(name)))
    }

    fun pullCurrent() {
        val current = snapshot.localBranches.firstOrNull { it.current }
        if (current == null) {
            Messages.showErrorDialog(project, "HEAD is detached, so there is no branch to pull into.", "Pull")
            return
        }
        val remotes = remoteNames()
        if (remotes.isEmpty()) {
            noRemotes("Pull")
            return
        }
        val upstream = current.upstream?.let(snapshot::remoteBranchOf)
        val dialog = OpDialog(project, "Pull", "Pull", "Pull into ${current.name}.")
        val remote = dialog.combo("Remote:", remotes, upstream?.remote ?: preferredRemote())
        val branch = dialog.text("Remote branch:", upstream?.branch ?: current.name) { OpDialog.refName(it) }
        val mode = dialog.combo("Strategy:", listOf("Default", "Rebase", "Fast-forward only", "No fast-forward"), "Default")
        val autostash = dialog.check("Autostash local changes", false)
        val prune = dialog.check("Prune deleted remote branches", false)
        if (!dialog.open()) return
        val name = remote.selectedItem as String
        val args = arrayListOf("pull")
        when (mode.selectedItem) {
            "Rebase" -> args += "--rebase"
            "Fast-forward only" -> args += "--ff-only"
            "No fast-forward" -> args += "--no-ff"
        }
        if (autostash.isSelected) args += "--autostash"
        if (prune.isSelected) args += "--prune"
        args += name
        args += branch.text.trim()
        run("Pull", "Pulled $name/${branch.text.trim()}", GitCall(args, urls(name)))
    }

    fun fetchAll() {
        if (snapshot.remotes.isEmpty()) {
            noRemotes("Fetch")
            return
        }
        val dialog = OpDialog(project, "Fetch", "Fetch")
        val prune = dialog.check("Prune deleted remote branches", true)
        val tags = dialog.check("Fetch all tags", true)
        val pruneTags = dialog.check("Prune tags deleted on the remote", false)
        if (!dialog.open()) return
        val args = arrayListOf("fetch", "--all")
        if (prune.isSelected) args += "--prune"
        if (tags.isSelected) args += "--tags"
        if (pruneTags.isSelected) args += "--prune-tags"
        run("Fetch", "Fetch finished", GitCall(args, allUrls()))
    }

    fun fetchInto(ref: GitRef) {
        val upstream = ref.upstream?.let(snapshot::remoteBranchOf) ?: return
        run(
            "Fetch into ${ref.name}",
            "Updated ${ref.name}",
            remoteCall(upstream.remote, "fetch", upstream.remote, "refs/heads/${upstream.branch}:refs/heads/${ref.name}"),
        )
    }

    fun setUpstream(ref: GitRef) {
        val options = (listOf("(none)") + listOfNotNull(ref.upstream) + snapshot.remoteBranches.map { it.name }).distinct()
        val dialog = OpDialog(project, "Set Upstream", "Save", "Choose the upstream for ${ref.name}.")
        val upstream = dialog.combo("Upstream:", options, ref.upstream ?: "(none)")
        if (!dialog.open()) return
        val value = upstream.selectedItem as String
        if (value == (ref.upstream ?: "(none)")) return
        if (value == "(none)") {
            run("Unset upstream", null, GitCall("branch", "--unset-upstream", ref.name))
        } else {
            run("Set upstream", null, GitCall("branch", "--set-upstream-to=$value", ref.name))
        }
    }

    fun merge(target: String, label: String) {
        val current = snapshot.currentBranch ?: "HEAD"
        val dialog = OpDialog(project, "Merge", "Merge", "Merge $label into $current.")
        val mode = dialog.combo("Mode:", listOf("Default", "Always create merge commit (--no-ff)", "Fast-forward only", "Squash"), "Default")
        val noCommit = dialog.check("Stage the result without committing", false)
        val message = dialog.text("Message:", "", required = false)
        if (!dialog.open()) return
        val args = arrayListOf("merge")
        when (mode.selectedItem) {
            "Always create merge commit (--no-ff)" -> args += "--no-ff"
            "Fast-forward only" -> args += "--ff-only"
            "Squash" -> args += "--squash"
        }
        if (noCommit.isSelected) args += "--no-commit"
        if (message.text.isNotBlank()) {
            args += "-m"
            args += message.text.trim()
        }
        args += target
        run("Merge $label", null, GitCall(args))
    }

    fun rebaseOnto(target: String, label: String) {
        val current = snapshot.currentBranch ?: "HEAD"
        val dialog = OpDialog(project, "Rebase", "Rebase", "Rebase $current onto $label. This rewrites the commits on $current.")
        val autostash = dialog.check("Autostash local changes", true)
        val merges = dialog.check("Preserve merge commits (--rebase-merges)", false)
        val ignoreDate = dialog.check("Reset author dates (--ignore-date)", false)
        if (!dialog.open()) return
        val args = arrayListOf("rebase")
        if (autostash.isSelected) args += "--autostash"
        if (merges.isSelected) args += "--rebase-merges"
        if (ignoreDate.isSelected) args += "--ignore-date"
        args += target
        run("Rebase onto $label", null, GitCall(args))
    }

    fun cherryPick(commits: List<Commit>) {
        if (commits.isEmpty()) return
        val ordered = commits.sortedBy { snapshot.rowByHash[it.hash] ?: 0 }.reversed()
        val merges = ordered.filter { it.isMerge }
        if (merges.isNotEmpty() && merges.size != ordered.size) {
            Messages.showErrorDialog(project, "Git cannot cherry-pick merge commits and regular commits in one step. Cherry-pick the merge commits separately.", "Cherry-pick")
            return
        }
        val label = if (ordered.size == 1) ordered[0].shortHash else "${ordered.size} commits"
        val dialog = OpDialog(project, "Cherry-pick", "Cherry-pick", "Apply $label onto ${snapshot.currentBranch ?: "HEAD"}.")
        val mainline = if (merges.isNotEmpty()) {
            dialog.combo("Mainline parent:", (1..merges.maxOf { it.parents.size }).toList(), 1)
        } else {
            null
        }
        val record = dialog.check("Record the origin in the message (-x)", false)
        val noCommit = dialog.check("Stage only, do not commit (--no-commit)", false)
        val signoff = dialog.check("Add a Signed-off-by line", false)
        if (!dialog.open()) return
        val args = arrayListOf("cherry-pick")
        if (mainline != null) {
            args += "-m"
            args += mainline.selectedItem.toString()
        }
        if (record.isSelected) args += "-x"
        if (noCommit.isSelected) args += "--no-commit"
        if (signoff.isSelected) args += "--signoff"
        args.addAll(ordered.map { it.hash })
        run("Cherry-pick $label", null, GitCall(args))
    }

    fun revert(commit: Commit) {
        val dialog = OpDialog(project, "Revert Commit", "Revert", "Create a new commit that undoes ${commit.shortHash}: ${commit.subject}")
        val mainline = if (commit.isMerge) dialog.combo("Mainline parent:", (1..commit.parents.size).toList(), 1) else null
        val noCommit = dialog.check("Stage only, do not commit", false)
        if (!dialog.open()) return
        val args = arrayListOf("revert", "--no-edit")
        if (mainline != null) {
            args += "-m"
            args += mainline.selectedItem.toString()
        }
        if (noCommit.isSelected) args += "--no-commit"
        args += commit.hash
        run("Revert ${commit.shortHash}", null, GitCall(args))
    }

    fun reset(target: String, label: String) {
        val current = snapshot.currentBranch ?: "HEAD"
        val dialog = OpDialog(project, "Reset", "Reset", "Move $current to $label.")
        val mode = dialog.combo(
            "Mode:",
            listOf("Soft: keep index and files", "Mixed: keep files, clear index", "Keep: keep local changes when safe", "Hard: discard all local changes"),
            "Mixed: keep files, clear index",
        )
        if (!dialog.open()) return
        val choice = mode.selectedItem as String
        val flag = "--" + choice.substringBefore(':').lowercase()
        if (flag == "--hard" && !confirm("Hard Reset", "A hard reset permanently discards uncommitted changes. Continue?", "Reset")) return
        run("Reset to $label", null, GitCall("reset", flag, target))
    }

    fun undoLastCommit() {
        if (!confirm("Undo Last Commit", "Undo the last commit and keep its changes staged?", "Undo")) return
        run("Undo last commit", null, GitCall("reset", "--soft", "HEAD~1"))
    }

    fun amendMessage(commit: Commit) {
        if (commit.hash == snapshot.head) {
            editMessage(commit) { text ->
                run("Amend commit message", null, GitCall("commit", "--amend", "--no-verify", "-m", text))
            }
            return
        }
        if (commit.isMerge) {
            Messages.showErrorDialog(project, "The message of an older merge commit cannot be edited, because rebasing would remove the merge.", "Edit Commit Message")
            return
        }
        inspectRange(commit) { range ->
            editMessage(commit) { text ->
                if (confirmRewrite("Edit Commit Message", "Rewrite the message of ${commit.shortHash}?", range, "Rewrite")) {
                    val entries = range.entries()
                    val entry = entries.firstOrNull { it.commit.hash == commit.hash }
                    if (entry == null) {
                        Messages.showErrorDialog(project, "${commit.shortHash} could not be found in the commits to rebase, so nothing was changed.", "Edit Commit Message")
                    } else {
                        entry.action = RebaseAction.REWORD
                        entry.message = text
                        startInteractive(entries.map { if (it === entry) it.copy(body = "") else it }, range.base)
                    }
                }
            }
        }
    }

    private fun editMessage(commit: Commit, then: (String) -> Unit) {
        val loader = view.loader ?: return
        background("Loading commit message", { loader.details(commit.hash)?.message ?: commit.subject }) { message ->
            val dialog = OpDialog(project, "Edit Commit Message", "Save")
            val area = dialog.area("Message:", message, required = true)
            if (dialog.open()) then(area.text.trim())
        }
    }

    private fun confirmRewrite(title: String, question: String, range: RebaseRange, ok: String): Boolean {
        val warnings = range.warnings()
        if (warnings.isEmpty()) return true
        return confirm(title, (listOf(question) + warnings).joinToString("\n\n"), ok)
    }

    fun dropCommit(commit: Commit) {
        if (commit.isMerge) {
            Messages.showErrorDialog(project, "Merge commits cannot be dropped this way. Use Interactive Rebase or Revert.", "Drop Commit")
            return
        }
        val parent = commit.parents.firstOrNull()
        if (parent == null) {
            Messages.showErrorDialog(project, "The root commit cannot be dropped.", "Drop Commit")
            return
        }
        inspectRange(commit) { range ->
            val question = "Remove ${commit.shortHash} (${commit.subject}) from the current branch? This rewrites history."
            if (confirm("Drop Commit", (listOf(question) + range.warnings()).joinToString("\n\n"), "Drop")) {
                run("Drop ${commit.shortHash}", null, GitCall("rebase", "--autostash", "--onto", parent, commit.hash))
            }
        }
    }

    fun interactiveRebase(commit: Commit) {
        inspectRange(commit) { range ->
            val dialog = InteractiveRebaseDialog(project, range.entries(), range.warnings())
            if (dialog.showAndGet()) startInteractive(dialog.entries, range.base)
        }
    }

    private fun inspectRange(commit: Commit, then: (RebaseRange) -> Unit) {
        val runner = view.runner ?: return
        background("Preparing rebase", { RebaseRanges.inspect(runner, commit) }, then)
    }

    private fun startInteractive(entries: List<RebaseEntry>, base: String?) {
        val problem = RebaseTodo.validate(entries)
        if (problem != null) {
            Messages.showErrorDialog(project, problem, "Interactive Rebase")
            return
        }
        val gitDir = view.repository?.repositoryFiles?.worktreeGitDir?.toNioPath()
        val directory: Path
        val todoFile: Path
        try {
            directory = Files.createTempDirectory("vt-git-graph-rebase")
            var counter = 0
            val todo = RebaseTodo.build(entries) { entry ->
                val file = directory.resolve("message-${counter++}.txt")
                Files.writeString(file, entry.fullMessage)
                file.toString()
            }
            todoFile = directory.resolve("todo.txt")
            Files.writeString(todoFile, todo)
        } catch (e: IOException) {
            Messages.showErrorDialog(project, "Could not prepare the rebase: ${e.message}", "Interactive Rebase")
            return
        }
        rebaseFiles.add(RebaseFiles(gitDir, directory))
        val args = arrayListOf("rebase", "-i", "--autostash")
        args += base ?: "--root"
        val env = mapOf(
            "GIT_SEQUENCE_EDITOR" to "cp ${RebaseTodo.shellQuote(todoFile.toString())}",
            "GIT_EDITOR" to "true",
        )
        run("Interactive rebase", null, listOf(GitCall(args, env = env)))
    }

    fun createTag(commit: Commit) {
        val remotes = remoteNames()
        val dialog = OpDialog(project, "Create Tag", "Create", "Tag ${commit.shortHash}: ${commit.subject}")
        val name = dialog.text("Tag name:") { OpDialog.refName(it) }
        val message = dialog.text("Annotation:", "", required = false)
        val sign = dialog.check("Sign with GPG", false)
        val force = dialog.check("Replace an existing tag", false)
        val push = if (remotes.isNotEmpty()) dialog.check("Push after creating", false) else null
        val remote = if (remotes.isNotEmpty()) dialog.combo("Remote:", remotes, preferredRemote()) else null
        if (!dialog.open()) return
        val tag = name.text.trim()
        val args = arrayListOf("tag")
        if (sign.isSelected) args += "--sign"
        if (force.isSelected) args += "--force"
        if (message.text.isNotBlank() || sign.isSelected) {
            if (!sign.isSelected) args += "--annotate"
            args += "-m"
            args += message.text.trim().ifEmpty { tag }
        }
        args += tag
        args += commit.hash
        val calls = arrayListOf(GitCall(args))
        if (push?.isSelected == true && remote != null) {
            val target = remote.selectedItem as String
            calls.add(remoteCall(target, "push", target, "refs/tags/$tag"))
        }
        run("Create tag $tag", null, calls)
    }

    fun deleteTag(ref: GitRef) {
        val remotes = remoteNames()
        val dialog = OpDialog(project, "Delete Tag", "Delete", "Delete tag ${ref.name}.", danger = true)
        val removeRemote = if (remotes.isNotEmpty()) dialog.check("Also delete from the remote", false) else null
        val remote = if (remotes.isNotEmpty()) dialog.combo("Remote:", remotes, preferredRemote()) else null
        if (!dialog.open()) return
        val calls = arrayListOf(GitCall("tag", "--delete", ref.name))
        if (removeRemote?.isSelected == true && remote != null) {
            val target = remote.selectedItem as String
            calls.add(remoteCall(target, "push", target, "--delete", "refs/tags/${ref.name}"))
        }
        run("Delete tag ${ref.name}", null, calls)
    }

    fun pushTag(ref: GitRef) {
        val remotes = remoteNames()
        if (remotes.isEmpty()) {
            noRemotes("Push Tag")
            return
        }
        val dialog = OpDialog(project, "Push Tag", "Push", "Push tag ${ref.name}.")
        val remote = dialog.combo("Remote:", remotes, preferredRemote())
        if (!dialog.open()) return
        val target = remote.selectedItem as String
        run("Push tag ${ref.name}", "Pushed ${ref.name} to $target", remoteCall(target, "push", target, "refs/tags/${ref.name}"))
    }

    fun pushAllTags() {
        val remotes = remoteNames()
        if (remotes.isEmpty()) {
            noRemotes("Push All Tags")
            return
        }
        val dialog = OpDialog(project, "Push All Tags", "Push")
        val remote = dialog.combo("Remote:", remotes, preferredRemote())
        if (!dialog.open()) return
        val target = remote.selectedItem as String
        run("Push all tags", "Pushed all tags to $target", remoteCall(target, "push", target, "--tags"))
    }

    fun stashChanges() {
        val loader = view.loader ?: return
        background("Checking local changes", { loader.status() }) { changes ->
            if (changes.isEmpty()) {
                notifyInfo("There are no local changes to stash")
                return@background
            }
            val dialog = OpDialog(project, "Stash Changes", "Stash")
            val message = dialog.text("Message:", "", required = false)
            val untracked = dialog.check("Include untracked files", true)
            val keepIndex = dialog.check("Keep staged changes in the index", false)
            if (!dialog.open()) return@background
            val args = arrayListOf("stash", "push")
            if (untracked.isSelected) args += "--include-untracked"
            if (keepIndex.isSelected) args += "--keep-index"
            if (message.text.isNotBlank()) {
                args += "-m"
                args += message.text.trim()
            }
            run("Stash changes", null, GitCall(args))
        }
    }

    fun stashApply(name: String, restoreIndex: Boolean = false) {
        val args = arrayListOf("stash", "apply")
        if (restoreIndex) args += "--index"
        args += name
        run("Apply $name", null, GitCall(args))
    }

    fun stashPop(name: String) {
        run("Pop $name", null, GitCall("stash", "pop", name))
    }

    fun stashDrop(name: String) {
        if (!confirm("Drop Stash", "Permanently delete $name?", "Drop")) return
        run("Drop $name", null, GitCall("stash", "drop", name))
    }

    fun stashBranch(name: String) {
        val dialog = OpDialog(project, "Branch from Stash", "Create", "Create a branch from $name and apply it there.")
        val branch = dialog.text("Branch name:") { OpDialog.refName(it) }
        if (!dialog.open()) return
        run("Branch from $name", null, GitCall("stash", "branch", branch.text.trim(), name))
    }

    fun stashClear() {
        if (!confirm("Clear All Stashes", "Permanently delete every stash in this repository?", "Clear")) return
        run("Clear stashes", null, GitCall("stash", "clear"))
    }

    fun discardAll() {
        val dialog = OpDialog(project, "Discard All Changes", "Discard", "This permanently removes uncommitted changes.", danger = true)
        val untracked = dialog.check("Also delete untracked files and directories", false)
        if (!dialog.open()) return
        val calls = arrayListOf(GitCall("reset", "--hard", "HEAD"))
        if (untracked.isSelected) calls.add(GitCall("clean", "-fd"))
        run("Discard changes", null, calls)
    }

    fun cleanUntracked() {
        val dialog = OpDialog(project, "Clean Untracked Files", "Clean", "This permanently deletes untracked files.", danger = true)
        val directories = dialog.check("Include directories", true)
        val ignored = dialog.check("Include ignored files", false)
        if (!dialog.open()) return
        val args = arrayListOf("clean", "-f")
        if (directories.isSelected) args += "-d"
        if (ignored.isSelected) args += "-x"
        run("Clean untracked files", null, GitCall(args))
    }

    fun openCommitWindow() {
        val window = ToolWindowManager.getInstance(project).getToolWindow("Commit")
        if (window != null) window.activate(null) else notifyInfo("The Commit tool window is not available. Use Git | Commit instead.")
    }

    fun stageFile(change: FileChange) {
        run("Stage ${change.path}", null, GitCall("add", "--", change.path))
    }

    fun unstageFile(change: FileChange) {
        run("Unstage ${change.path}", null, GitCall(listOf("reset", "-q", "--") + listOfNotNull(change.oldPath, change.path)))
    }

    fun stageAll() {
        run("Stage all changes", null, GitCall("add", "--all"))
    }

    fun unstageAll() {
        run("Unstage all changes", null, GitCall("reset"))
    }

    fun discardFile(change: FileChange) {
        if (!confirm("Discard Changes", "Discard all uncommitted changes to ${change.path}?", "Discard")) return
        run("Discard ${change.path}", null, DiscardPlan.calls(change))
    }

    fun restoreFile(change: FileChange, revision: String, label: String) {
        if (!confirm("Restore File", "Replace ${change.path} with the version from $label?", "Restore")) return
        run("Restore ${change.path}", null, GitCall("restore", "--source=$revision", "--staged", "--worktree", "--", change.path))
    }

    fun savePatch(commit: Commit) {
        val runner = view.runner ?: return
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save Patch", "Save the commit as a patch file", "patch"), project)
        val target = wrapper.save("${commit.shortHash}.patch")?.file ?: return
        background("Saving patch", { Files.write(target.toPath(), runner.read(listOf("show", "--format=email", "--binary", commit.hash))) }) {
            notifyInfo("Saved ${target.name}")
        }
    }

    fun archive(commit: Commit) {
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor("Save Archive", "Save the commit as a zip archive", "zip"), project)
        val target = wrapper.save("${commit.shortHash}.zip")?.file ?: return
        run("Create archive", "Saved ${target.name}", GitCall("archive", "--format=zip", "-o", target.path, commit.hash))
    }

    fun applyPatch() {
        val descriptor = FileChooserDescriptorFactory.singleFile().withTitle("Apply Patch")
        val file = FileChooser.chooseFile(descriptor, project, null) ?: return
        val dialog = OpDialog(project, "Apply Patch", "Apply", "Apply ${file.name} to the working tree.")
        val threeWay = dialog.check("Fall back to a three-way merge", true)
        val index = dialog.check("Also stage the changes", false)
        if (!dialog.open()) return
        val args = arrayListOf("apply")
        if (threeWay.isSelected) args += "--3way"
        if (index.isSelected) args += "--index"
        args += file.path
        run("Apply patch", "Patch applied", GitCall(args))
    }

    fun continueOperation(operation: RepoOperation) = operationStep(operation, "--continue", "Continue")

    fun skipOperation(operation: RepoOperation) = operationStep(operation, "--skip", "Skip")

    fun abortOperation(operation: RepoOperation) {
        if (!confirm("Abort ${operation.label}", "Abort the ${operation.label.lowercase()} and restore the previous state?", "Abort")) return
        operationStep(operation, "--abort", "Abort")
    }

    private fun operationStep(operation: RepoOperation, flag: String, verb: String) {
        val command = when (operation) {
            RepoOperation.MERGE -> "merge"
            RepoOperation.REBASE -> "rebase"
            RepoOperation.CHERRY_PICK -> "cherry-pick"
            RepoOperation.REVERT -> "revert"
            RepoOperation.NONE -> return
        }
        run("$verb ${operation.label.lowercase()}", null, GitCall(command, flag))
    }

    fun resolveConflicts() {
        val runner = view.runner ?: return
        val root = runner.root
        background("Finding conflicts", {
            val paths = String(runner.read(listOf("diff", "--name-only", "--diff-filter=U", "-z")), Charsets.UTF_8)
                .split('\u0000')
                .filter { it.isNotEmpty() }
                .distinct()
            val files = paths.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(root.path, it)) }
            paths.size to files
        }) { (total, files) ->
            if (total == 0) {
                notifyInfo("No conflicted files")
                return@background
            }
            if (files.size < total) {
                notifyInfo("${total - files.size} conflicted files were deleted on one side and must be resolved with git add or git rm.")
            }
            if (files.isNotEmpty()) {
                AbstractVcsHelper.getInstance(project).showMergeDialogWithResult(files, GitVcs.getInstance(project).mergeProvider)
            }
            ApplicationManager.getApplication().executeOnPooledThread { refreshRepository() }
        }
    }

    fun bisectStart(bad: Commit?, good: Commit?) {
        val calls = arrayListOf(GitCall("bisect", "start"))
        if (bad != null) calls.add(GitCall("bisect", "bad", bad.hash))
        if (good != null) calls.add(GitCall("bisect", "good", good.hash))
        run("Start bisect", null, calls, showOutput = true)
    }

    fun bisectMark(term: String, target: String?) {
        val args = arrayListOf("bisect", term)
        if (target != null) args += target
        run("Bisect $term", null, listOf(GitCall(args)), showOutput = true)
    }

    fun bisectReset() {
        run("Reset bisect", "Bisect finished", GitCall("bisect", "reset"))
    }

    fun addRemote() {
        val dialog = OpDialog(project, "Add Remote", "Add")
        val name = dialog.text("Name:", if (snapshot.remotes.isEmpty()) "origin" else "") { OpDialog.refName(it) }
        val url = dialog.text("URL:")
        val fetch = dialog.check("Fetch after adding", true)
        if (!dialog.open()) return
        val remote = name.text.trim()
        val calls = arrayListOf(GitCall("remote", "add", remote, url.text.trim()))
        if (fetch.isSelected) calls.add(GitCall(listOf("fetch", remote), listOf(url.text.trim())))
        run("Add remote $remote", null, calls)
    }

    fun renameRemote(remote: RemoteInfo) {
        val dialog = OpDialog(project, "Rename Remote", "Rename")
        val name = dialog.text("New name:", remote.name) { OpDialog.refName(it) }
        if (!dialog.open()) return
        run("Rename remote", null, GitCall("remote", "rename", remote.name, name.text.trim()))
    }

    fun editRemoteUrl(remote: RemoteInfo) {
        val dialog = OpDialog(project, "Edit Remote", "Save")
        val fetch = dialog.text("Fetch URL:", remote.fetchUrl)
        val push = dialog.text("Push URL:", remote.pushUrl)
        if (!dialog.open()) return
        val calls = RemoteUrlPlan.calls(remote, fetch.text.trim(), push.text.trim())
        if (calls.isNotEmpty()) run("Edit remote ${remote.name}", null, calls)
    }

    fun removeRemote(remote: RemoteInfo) {
        if (!confirm("Remove Remote", "Remove ${remote.name} and its remote-tracking branches?", "Remove")) return
        run("Remove remote", null, GitCall("remote", "remove", remote.name))
    }

    fun fetchRemote(remote: RemoteInfo) {
        run("Fetch ${remote.name}", "Fetched ${remote.name}", remoteCall(remote.name, "fetch", "--prune", remote.name))
    }

    fun pruneRemote(remote: RemoteInfo) {
        run("Prune ${remote.name}", "Pruned ${remote.name}", remoteCall(remote.name, "remote", "prune", remote.name))
    }

    fun addWorktree(startPoint: String?) {
        val base = view.repository?.root?.parent?.path.orEmpty()
        val dialog = OpDialog(project, "Add Worktree", "Add", "Create a new working directory linked to this repository.")
        val path = dialog.text("Path:", if (base.isEmpty()) "" else "$base/")
        val branch = dialog.text("New branch:", "", required = false)
        val start = dialog.text("Start point:", startPoint ?: "HEAD")
        val detach = dialog.check("Detach HEAD", false)
        if (!dialog.open()) return
        val args = arrayListOf("worktree", "add")
        if (detach.isSelected) args += "--detach"
        if (branch.text.isNotBlank() && !detach.isSelected) {
            args += "-b"
            args += branch.text.trim()
        }
        args += path.text.trim()
        args += start.text.trim()
        run("Add worktree", "Worktree added", GitCall(args))
    }

    fun openWorktree(worktree: WorktreeInfo) {
        val path = Path.of(worktree.path)
        if (!Files.isDirectory(path)) {
            Messages.showErrorDialog(project, "${worktree.path} no longer exists. Prune worktrees to remove this entry.", "Open Worktree")
            return
        }
        ProjectUtil.openOrImport(path, project, true)
    }

    fun removeWorktree(worktree: WorktreeInfo) {
        val dialog = OpDialog(project, "Remove Worktree", "Remove", "Remove ${worktree.path}.", danger = true)
        val force = dialog.check("Force, even with uncommitted changes", false)
        if (!dialog.open()) return
        val args = arrayListOf("worktree", "remove")
        if (force.isSelected) args += "--force"
        args += worktree.path
        run("Remove worktree", null, GitCall(args))
    }

    fun toggleWorktreeLock(worktree: WorktreeInfo) {
        run("Update worktree lock", null, GitCall("worktree", if (worktree.locked) "unlock" else "lock", worktree.path))
    }

    fun pruneWorktrees() {
        run("Prune worktrees", "Worktrees pruned", GitCall("worktree", "prune"))
    }

    fun updateSubmodules() {
        run("Update submodules", "Submodules updated", GitCall(listOf("submodule", "update", "--init", "--recursive"), allUrls()))
    }

    fun syncSubmodules() {
        run("Sync submodules", "Submodules synced", GitCall("submodule", "sync", "--recursive"))
    }

    fun updateSubmodule(submodule: SubmoduleInfo) {
        run("Update ${submodule.path}", null, GitCall(listOf("submodule", "update", "--init", "--recursive", "--", submodule.path), allUrls()))
    }

    fun openReflog() {
        val loader = view.loader ?: return
        background("Loading reflog", { loader.reflog() }) { entries ->
            val dialog = ReflogDialog(project, entries)
            if (!dialog.showAndGet()) return@background
            val entry = dialog.selected ?: return@background
            when (dialog.choice) {
                ReflogDialog.Choice.SHOW -> view.selectCommit(entry.hash)
                ReflogDialog.Choice.CHECKOUT -> checkout(entry.hash, entry.hash.take(8))
                ReflogDialog.Choice.RESET -> reset(entry.hash, entry.hash.take(8))
            }
        }
    }

    fun garbageCollect() {
        run("Run garbage collection", "Garbage collection finished", GitCall("gc"))
    }

    private fun notifyInfo(message: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("VT Git Graph")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }

    fun localRefOf(commit: Commit): List<GitRef> = snapshot.refsByHash[commit.hash].orEmpty().filter { it.kind == RefKind.LOCAL }

    private companion object {
        const val MAX_NOTIFICATION_CHARS = 1500
    }
}

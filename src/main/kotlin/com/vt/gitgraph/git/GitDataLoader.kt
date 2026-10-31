package com.vt.gitgraph.git

import com.vt.gitgraph.graph.GraphLayout
import com.vt.gitgraph.model.CommitDetails
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.GraphSnapshot
import com.vt.gitgraph.model.LoadOptions
import com.vt.gitgraph.model.ReflogEntry
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RepoOperation
import com.vt.gitgraph.model.RepoState
import java.nio.file.Files
import java.nio.file.Path

fun interface GitReader {
    fun read(args: List<String>): ByteArray
}

class GitDataLoader(private val reader: GitReader) {
    private fun text(vararg args: String): String = String(reader.read(args.toList()), Charsets.UTF_8)

    private fun textOrNull(vararg args: String): String? = runCatching { text(*args) }.getOrNull()

    fun load(options: LoadOptions, gitDir: Path?): GraphSnapshot {
        val started = System.currentTimeMillis()
        val allRefs = GitParsers.parseRefs(text("for-each-ref", "--format=${GitParsers.REF_FORMAT}", "refs/heads", "refs/remotes", "refs/tags"))
        val head = textOrNull("rev-parse", "--verify", "-q", "HEAD")?.trim()?.ifEmpty { null }

        val allStatus = loadStatus(head != null)
        val statusFiles = if (options.showUncommitted) allStatus else emptyList()
        val conflicts = allStatus.count { it.status == 'U' }
        val stashCommits = if (options.showStashes) {
            textOrNull("stash", "list", "--format=${GitParsers.STASH_FORMAT}")?.let(GitParsers::parseStashes).orEmpty()
        } else {
            emptyList()
        }
        val stashRefs = stashCommits.map {
            val name = it.stashName ?: "stash"
            GitRef(name, name, RefKind.STASH, it.hash, subject = it.subject)
        }

        val knownRefs = allRefs.mapTo(HashSet()) { it.fullName }
        val branches = options.branches.filter { it in knownRefs }
        val logged = if (allRefs.isEmpty() && head == null) emptyList() else GitParsers.parseLog(text(*logArgs(options, branches, head != null).toTypedArray()))
        val hasMore = logged.size > options.limit
        val limited = if (hasMore) logged.subList(0, options.limit) else logged

        val visibleStashes = if (options.isHistoryFiltered) emptyList() else stashCommits
        val uncommitted = if (options.isHistoryFiltered) 0 else statusFiles.size
        val commits = GitParsers.withSpecialNodes(limited, visibleStashes, uncommitted, head, System.currentTimeMillis() / 1000)
        val graph = GraphLayout.compute(commits, dropMissingParents = options.isHistoryFiltered || options.firstParent)

        val refs = allRefs.filter { ref ->
            when (ref.kind) {
                RefKind.REMOTE -> options.showRemotes
                RefKind.TAG -> options.showTags
                else -> true
            }
        } + stashRefs

        return GraphSnapshot(
            commits = commits,
            graph = graph,
            refs = refs,
            head = head,
            currentBranch = allRefs.firstOrNull { it.current }?.name,
            statusFiles = statusFiles,
            worktrees = textOrNull("worktree", "list", "--porcelain")?.let(GitParsers::parseWorktrees).orEmpty(),
            remotes = textOrNull("remote", "-v")?.let(GitParsers::parseRemotes).orEmpty(),
            submodules = textOrNull("submodule", "status")?.let(GitParsers::parseSubmodules).orEmpty(),
            state = detectState(gitDir, conflicts),
            hasMore = hasMore,
            loadMillis = System.currentTimeMillis() - started,
            options = options,
        )
    }

    private fun logArgs(options: LoadOptions, branches: List<String>, hasHead: Boolean): List<String> {
        val args = ArrayList<String>()
        args += "log"
        args += "--no-color"
        args += "--no-show-signature"
        args += "--max-count=${options.limit + 1}"
        args += "--format=${GitParsers.LOG_FORMAT}"
        args += options.order.flag
        if (options.firstParent) args += "--first-parent"
        if (options.author.isNotBlank()) {
            args += "--regexp-ignore-case"
            args += "--author=${options.author}"
        }
        if (options.grep.isNotBlank()) {
            args += "--regexp-ignore-case"
            args += "--grep=${options.grep}"
        }
        if (options.since.isNotBlank()) args += "--since=${options.since}"
        if (options.path.isNotBlank()) args += "--parents"
        if (branches.isNotEmpty()) {
            args.addAll(branches.sorted())
        } else {
            args += "--branches"
            if (options.showTags) args += "--tags"
            if (options.showRemotes) args += "--remotes"
            if (hasHead) args += "HEAD"
        }
        args += "--"
        if (options.path.isNotBlank()) args += options.path
        return args
    }

    private fun loadStatus(hasHead: Boolean): List<FileChange> {
        val raw = textOrNull("status", "--porcelain=v1", "-z", "--untracked-files=all") ?: return emptyList()
        val files = GitParsers.parseStatus(raw)
        if (files.isEmpty() || !hasHead) return files
        val stats = textOrNull("diff", "HEAD", "--numstat", "-z", "-M")?.let(GitParsers::parseNumStat).orEmpty()
        return GitParsers.mergeStats(files, stats)
    }

    fun detectState(gitDir: Path?, conflicts: Int): RepoState {
        if (gitDir == null) return RepoState(conflicts = conflicts)
        val operation = when {
            Files.exists(gitDir.resolve("rebase-merge")) || Files.exists(gitDir.resolve("rebase-apply")) -> RepoOperation.REBASE
            Files.exists(gitDir.resolve("MERGE_HEAD")) -> RepoOperation.MERGE
            Files.exists(gitDir.resolve("CHERRY_PICK_HEAD")) -> RepoOperation.CHERRY_PICK
            Files.exists(gitDir.resolve("REVERT_HEAD")) -> RepoOperation.REVERT
            else -> RepoOperation.NONE
        }
        return RepoState(operation, Files.exists(gitDir.resolve("BISECT_LOG")), conflicts)
    }

    fun details(hash: String): CommitDetails? =
        GitParsers.parseDetails(text("show", "-s", "--no-color", "--format=${GitParsers.DETAILS_FORMAT}", hash))

    fun changes(left: String, right: String): List<FileChange> {
        val names = GitParsers.parseNameStatus(text("diff", "--name-status", "-z", "-M", left, right, "--"))
        val stats = GitParsers.parseNumStat(text("diff", "--numstat", "-z", "-M", left, right, "--"))
        return GitParsers.mergeStats(names, stats)
    }

    fun changesAgainstWorkingTree(left: String): List<FileChange> {
        val names = GitParsers.parseNameStatus(text("diff", "--name-status", "-z", "-M", left, "--"))
        val stats = GitParsers.parseNumStat(text("diff", "--numstat", "-z", "-M", left, "--"))
        return GitParsers.mergeStats(names, stats)
    }

    fun emptyTree(hash: String): String =
        if (hash.length == 64) "6ef19b41225c5369f1c104d45d8d85efa9b057b53b14b4b9b939dd74decc5321" else "4b825dc642cb6eb9a060e54bf8d69288fbee4904"

    fun fileAt(revision: String, path: String): ByteArray? =
        runCatching { reader.read(listOf("show", "$revision:$path")) }.getOrNull()

    fun reflog(limit: Int = 300): List<ReflogEntry> =
        GitParsers.parseReflog(text("reflog", "--no-color", "--max-count=$limit", "--format=${GitParsers.REFLOG_FORMAT}"))

    fun status(): List<FileChange> = loadStatus(textOrNull("rev-parse", "--verify", "-q", "HEAD") != null)
}

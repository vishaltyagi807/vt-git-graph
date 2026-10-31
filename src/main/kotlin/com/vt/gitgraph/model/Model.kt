package com.vt.gitgraph.model

const val WORKING_TREE = "WORKING_TREE"

enum class RefKind { LOCAL, REMOTE, TAG, STASH }

enum class CommitKind { NORMAL, STASH, UNCOMMITTED }

enum class LogOrder(val flag: String, val label: String) {
    DATE("--date-order", "Commit Date"),
    AUTHOR_DATE("--author-date-order", "Author Date"),
    TOPO("--topo-order", "Topological"),
}

enum class RepoOperation(val label: String) {
    NONE(""),
    MERGE("Merge"),
    REBASE("Rebase"),
    CHERRY_PICK("Cherry-pick"),
    REVERT("Revert"),
}

data class GitRef(
    val name: String,
    val fullName: String,
    val kind: RefKind,
    val hash: String,
    val upstream: String? = null,
    val ahead: Int = 0,
    val behind: Int = 0,
    val upstreamGone: Boolean = false,
    val current: Boolean = false,
    val annotated: Boolean = false,
    val subject: String = "",
) {
    val remoteName: String? get() = if (kind == RefKind.REMOTE) name.substringBefore('/') else null
    val remoteBranch: String? get() = if (kind == RefKind.REMOTE) name.substringAfter('/') else null
}

data class Commit(
    val hash: String,
    val parents: List<String>,
    val author: String,
    val authorEmail: String,
    val authorTime: Long,
    val committer: String,
    val committerEmail: String,
    val commitTime: Long,
    val subject: String,
    val kind: CommitKind = CommitKind.NORMAL,
    val stashName: String? = null,
) {
    val isMerge: Boolean get() = parents.size > 1
    val shortHash: String get() = if (kind == CommitKind.UNCOMMITTED) "*" else hash.take(8)
}

data class CommitDetails(
    val hash: String,
    val tree: String,
    val parents: List<String>,
    val author: String,
    val authorEmail: String,
    val authorTime: Long,
    val committer: String,
    val committerEmail: String,
    val commitTime: Long,
    val signatureStatus: String,
    val signer: String,
    val signingKey: String,
    val message: String,
)

data class FileChange(
    val path: String,
    val oldPath: String? = null,
    val status: Char,
    val added: Int = -1,
    val deleted: Int = -1,
    val staged: Boolean = false,
    val unstaged: Boolean = false,
) {
    val isBinary: Boolean get() = added < 0 && deleted < 0
}

data class WorktreeInfo(
    val path: String,
    val head: String?,
    val branch: String?,
    val bare: Boolean,
    val detached: Boolean,
    val locked: Boolean,
    val prunable: Boolean,
    val main: Boolean,
)

data class RemoteInfo(val name: String, val fetchUrl: String, val pushUrl: String)

data class RemoteBranch(val remote: String, val branch: String)

data class SubmoduleInfo(val path: String, val hash: String, val state: Char, val describe: String)

data class ReflogEntry(val hash: String, val selector: String, val message: String, val time: Long)

data class RepoState(
    val operation: RepoOperation = RepoOperation.NONE,
    val bisecting: Boolean = false,
    val conflicts: Int = 0,
)

data class LoadOptions(
    val limit: Int = 2000,
    val branches: Set<String> = emptySet(),
    val showRemotes: Boolean = true,
    val showTags: Boolean = true,
    val showStashes: Boolean = true,
    val showUncommitted: Boolean = true,
    val firstParent: Boolean = false,
    val order: LogOrder = LogOrder.DATE,
    val author: String = "",
    val grep: String = "",
    val path: String = "",
    val since: String = "",
) {
    val isHistoryFiltered: Boolean get() = author.isNotBlank() || grep.isNotBlank() || path.isNotBlank() || since.isNotBlank()
}

data class GraphEdge(val from: Int, val to: Int, val color: Int, val dashed: Boolean)

data class GraphRow(
    val column: Int,
    val color: Int,
    val up: List<GraphEdge>,
    val down: List<GraphEdge>,
)

data class GraphModel(val rows: List<GraphRow>, val laneCount: Int)

data class GraphSnapshot(
    val commits: List<Commit> = emptyList(),
    val graph: GraphModel = GraphModel(emptyList(), 0),
    val refs: List<GitRef> = emptyList(),
    val head: String? = null,
    val currentBranch: String? = null,
    val statusFiles: List<FileChange> = emptyList(),
    val worktrees: List<WorktreeInfo> = emptyList(),
    val remotes: List<RemoteInfo> = emptyList(),
    val submodules: List<SubmoduleInfo> = emptyList(),
    val state: RepoState = RepoState(),
    val hasMore: Boolean = false,
    val loadMillis: Long = 0,
    val options: LoadOptions = LoadOptions(),
) {
    val refsByHash: Map<String, List<GitRef>> = refs.groupBy { it.hash }
    val rowByHash: Map<String, Int> = HashMap<String, Int>(commits.size * 2).also { map ->
        commits.forEachIndexed { index, commit -> map[commit.hash] = index }
    }
    val childrenByHash: Map<String, List<String>> = HashMap<String, MutableList<String>>().also { map ->
        commits.forEach { commit ->
            if (commit.kind == CommitKind.NORMAL) {
                commit.parents.forEach { parent -> map.getOrPut(parent) { ArrayList(1) }.add(commit.hash) }
            }
        }
    }
    fun remoteBranchOf(name: String): RemoteBranch? {
        val remote = remotes.map { it.name }.filter { name.startsWith("$it/") }.maxByOrNull { it.length } ?: return null
        return RemoteBranch(remote, name.removePrefix("$remote/"))
    }

    val localBranches: List<GitRef> get() = refs.filter { it.kind == RefKind.LOCAL }
    val remoteBranches: List<GitRef> get() = refs.filter { it.kind == RefKind.REMOTE }
    val tags: List<GitRef> get() = refs.filter { it.kind == RefKind.TAG }
    val stashes: List<GitRef> get() = refs.filter { it.kind == RefKind.STASH }
    val authors: List<String> get() = commits.asSequence().filter { it.kind == CommitKind.NORMAL }.map { it.author }.distinct().sorted().toList()
}

package com.vt.gitgraph.git

import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitDetails
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.ReflogEntry
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.SubmoduleInfo
import com.vt.gitgraph.model.WORKING_TREE
import com.vt.gitgraph.model.WorktreeInfo

object GitParsers {
    private const val RS = '\u001e'
    private const val NUL = '\u0000'

    const val LOG_FORMAT = "%x1e%H%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%s"
    const val STASH_FORMAT = "$LOG_FORMAT%x00%gd"
    const val DETAILS_FORMAT = "%H%x00%T%x00%P%x00%an%x00%ae%x00%at%x00%cn%x00%ce%x00%ct%x00%G?%x00%GS%x00%GK%x00%B"
    const val REF_FORMAT = "%(refname)%00%(objectname)%00%(*objectname)%00%(symref)%00%(upstream:short)%00%(upstream:track,nobracket)%00%(HEAD)%00%(objecttype)%00%(contents:subject)"
    const val REFLOG_FORMAT = "%H%x00%gd%x00%gs%x00%ct"
    const val BODY_FORMAT = "%x1e%H%x00%b"

    private val REMOTE_LINE = Regex("""^(.*) \((fetch|push)\)( \[.*])?$""")

    fun parseBodies(text: String): Map<String, String> =
        text.split(RS).asSequence().filter { it.isNotBlank() }.mapNotNull { record ->
            val f = record.split(NUL, limit = 2)
            if (f.size < 2) null else f[0] to f[1].trim()
        }.toMap()

    fun parseLog(text: String): List<Commit> =
        text.split(RS).asSequence().filter { it.isNotBlank() }.mapNotNull { parseCommit(it.trimEnd('\n', '\r')) }.toList()

    fun parseStashes(text: String): List<Commit> =
        parseLog(text).map { it.copy(kind = CommitKind.STASH, parents = it.parents.take(1)) }

    private fun parseCommit(record: String): Commit? {
        val f = record.split(NUL)
        if (f.size < 9) return null
        return Commit(
            hash = f[0],
            parents = f[1].split(' ').filter { it.isNotEmpty() },
            author = f[2],
            authorEmail = f[3],
            authorTime = f[4].toLongOrNull() ?: 0,
            committer = f[5],
            committerEmail = f[6],
            commitTime = f[7].toLongOrNull() ?: 0,
            subject = f[8],
            stashName = f.getOrNull(9),
        )
    }

    fun parseDetails(text: String): CommitDetails? {
        val f = text.split(NUL, limit = 13)
        if (f.size < 13) return null
        return CommitDetails(
            hash = f[0],
            tree = f[1],
            parents = f[2].split(' ').filter { it.isNotEmpty() },
            author = f[3],
            authorEmail = f[4],
            authorTime = f[5].toLongOrNull() ?: 0,
            committer = f[6],
            committerEmail = f[7],
            commitTime = f[8].toLongOrNull() ?: 0,
            signatureStatus = f[9],
            signer = f[10],
            signingKey = f[11],
            message = f[12].trimEnd(),
        )
    }

    fun parseRefs(text: String): List<GitRef> = text.lineSequence().mapNotNull(::parseRef).toList()

    private fun parseRef(line: String): GitRef? {
        val f = line.split(NUL)
        if (f.size < 9) return null
        val full = f[0]
        if (f[3].isNotEmpty() || full == "refs/stash") return null
        val kind: RefKind
        val name: String
        when {
            full.startsWith("refs/heads/") -> {
                kind = RefKind.LOCAL
                name = full.removePrefix("refs/heads/")
            }
            full.startsWith("refs/remotes/") -> {
                kind = RefKind.REMOTE
                name = full.removePrefix("refs/remotes/")
            }
            full.startsWith("refs/tags/") -> {
                kind = RefKind.TAG
                name = full.removePrefix("refs/tags/")
            }
            else -> return null
        }
        val (ahead, behind, gone) = parseTrack(f[5])
        return GitRef(
            name = name,
            fullName = full,
            kind = kind,
            hash = f[2].ifEmpty { f[1] },
            upstream = f[4].ifEmpty { null },
            ahead = ahead,
            behind = behind,
            upstreamGone = gone,
            current = f[6] == "*",
            annotated = kind == RefKind.TAG && f[7] == "tag",
            subject = f[8],
        )
    }

    private fun parseTrack(track: String): Triple<Int, Int, Boolean> {
        if (track == "gone") return Triple(0, 0, true)
        var ahead = 0
        var behind = 0
        track.split(',').map { it.trim() }.forEach { part ->
            when {
                part.startsWith("ahead ") -> ahead = part.removePrefix("ahead ").toIntOrNull() ?: 0
                part.startsWith("behind ") -> behind = part.removePrefix("behind ").toIntOrNull() ?: 0
            }
        }
        return Triple(ahead, behind, false)
    }

    fun parseStatus(text: String): List<FileChange> {
        val parts = text.split(NUL)
        val result = ArrayList<FileChange>()
        var i = 0
        while (i < parts.size) {
            val entry = parts[i++]
            if (entry.length < 4) continue
            val x = entry[0]
            val y = entry[1]
            val path = entry.substring(3)
            val renamed = x == 'R' || x == 'C' || y == 'R' || y == 'C'
            val oldPath = if (renamed && i < parts.size) parts[i++] else null
            result.add(
                FileChange(
                    path = path,
                    oldPath = oldPath,
                    status = statusOf(x, y),
                    staged = x != ' ' && x != '?' && x != '!',
                    unstaged = y != ' ',
                ),
            )
        }
        return result
    }

    private fun statusOf(x: Char, y: Char): Char = when {
        x == '?' -> '?'
        x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D') -> 'U'
        x == 'R' || y == 'R' -> 'R'
        x == 'C' || y == 'C' -> 'C'
        x == 'A' -> 'A'
        x == 'D' || y == 'D' -> 'D'
        else -> 'M'
    }

    fun parseNameStatus(text: String): List<FileChange> {
        val parts = text.split(NUL)
        val result = ArrayList<FileChange>()
        var i = 0
        while (i < parts.size) {
            val code = parts[i++]
            if (code.isEmpty()) continue
            val status = code[0]
            if (status == 'R' || status == 'C') {
                if (i + 1 >= parts.size) break
                val old = parts[i++]
                val new = parts[i++]
                result.add(FileChange(path = new, oldPath = old, status = status))
            } else {
                if (i >= parts.size) break
                result.add(FileChange(path = parts[i++], status = status))
            }
        }
        return result
    }

    fun parseNumStat(text: String): Map<String, Pair<Int, Int>> {
        val parts = text.split(NUL)
        val result = HashMap<String, Pair<Int, Int>>()
        var i = 0
        while (i < parts.size) {
            val entry = parts[i++]
            if (entry.isEmpty()) continue
            val f = entry.split('\t', limit = 3)
            if (f.size < 3) continue
            val added = f[0].toIntOrNull() ?: -1
            val deleted = f[1].toIntOrNull() ?: -1
            val path = if (f[2].isEmpty()) {
                if (i + 1 >= parts.size) break
                i++
                parts[i++]
            } else {
                f[2]
            }
            result[path] = added to deleted
        }
        return result
    }

    fun mergeStats(changes: List<FileChange>, stats: Map<String, Pair<Int, Int>>): List<FileChange> =
        changes.map { change ->
            val stat = stats[change.path]
            if (stat == null) change else change.copy(added = stat.first, deleted = stat.second)
        }

    fun parseWorktrees(text: String): List<WorktreeInfo> {
        val blocks = text.split("\n\n").filter { it.isNotBlank() }
        return blocks.mapIndexedNotNull { index, block ->
            val lines = block.lines()
            val path = lines.firstOrNull { it.startsWith("worktree ") }?.removePrefix("worktree ") ?: return@mapIndexedNotNull null
            WorktreeInfo(
                path = path,
                head = lines.firstOrNull { it.startsWith("HEAD ") }?.removePrefix("HEAD "),
                branch = lines.firstOrNull { it.startsWith("branch ") }?.removePrefix("branch ")?.removePrefix("refs/heads/"),
                bare = lines.any { it == "bare" },
                detached = lines.any { it == "detached" },
                locked = lines.any { it == "locked" || it.startsWith("locked ") },
                prunable = lines.any { it == "prunable" || it.startsWith("prunable ") },
                main = index == 0,
            )
        }
    }

    fun parseRemotes(text: String): List<RemoteInfo> {
        val fetch = LinkedHashMap<String, String>()
        val push = HashMap<String, String>()
        text.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val name = line.substringBefore('\t')
            val match = REMOTE_LINE.find(line.substringAfter('\t'))
            if (match != null) {
                val url = match.groupValues[1]
                if (match.groupValues[2] == "fetch") fetch[name] = url else push[name] = url
            }
        }
        return fetch.map { (name, url) -> RemoteInfo(name, url, push[name] ?: url) }
    }

    fun parseSubmodules(text: String): List<SubmoduleInfo> =
        text.lineSequence().filter { it.length > 2 }.mapNotNull { line ->
            val state = line[0]
            val body = line.substring(1).trim()
            val hash = body.substringBefore(' ')
            val rest = body.substringAfter(' ', "")
            if (rest.isEmpty()) return@mapNotNull null
            val path = rest.substringBefore(" (")
            val describe = rest.substringAfter(" (", "").removeSuffix(")")
            SubmoduleInfo(path, hash, state, describe)
        }.toList()

    fun parseReflog(text: String): List<ReflogEntry> =
        text.lineSequence().mapNotNull { line ->
            val f = line.split(NUL)
            if (f.size < 4) null else ReflogEntry(f[0], f[1], f[2], f[3].toLongOrNull() ?: 0)
        }.toList()

    fun withSpecialNodes(
        commits: List<Commit>,
        stashes: List<Commit>,
        uncommittedCount: Int,
        head: String?,
        now: Long,
    ): List<Commit> {
        val known = HashSet<String>(commits.size * 2)
        commits.forEach { known.add(it.hash) }
        val byParent = stashes.filter { it.parents.firstOrNull() in known }.groupBy { it.parents.first() }
        val result = ArrayList<Commit>(commits.size + stashes.size + 1)
        if (uncommittedCount > 0) {
            result.add(
                Commit(
                    hash = WORKING_TREE,
                    parents = if (head != null && head in known) listOf(head) else emptyList(),
                    author = "",
                    authorEmail = "",
                    authorTime = now,
                    committer = "",
                    committerEmail = "",
                    commitTime = now,
                    subject = if (uncommittedCount == 1) "Uncommitted Changes (1 file)" else "Uncommitted Changes ($uncommittedCount files)",
                    kind = CommitKind.UNCOMMITTED,
                ),
            )
        }
        commits.forEach { commit ->
            byParent[commit.hash]?.let { result.addAll(it) }
            result.add(commit)
        }
        return result
    }
}

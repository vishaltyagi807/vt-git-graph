package com.vt.gitgraph

import com.intellij.openapi.vcs.VcsException
import com.vt.gitgraph.git.GitCall
import com.vt.gitgraph.git.GitDataLoader
import com.vt.gitgraph.git.GitParsers
import com.vt.gitgraph.git.GitReader
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.GraphSnapshot
import com.vt.gitgraph.model.LoadOptions
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.RepoState
import com.vt.gitgraph.ops.DiscardPlan
import com.vt.gitgraph.ops.RebaseEntry
import com.vt.gitgraph.ops.RebaseRanges
import com.vt.gitgraph.ops.RemoteUrlPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitOperationPlansTest {
    private val nul = "\u0000"
    private val rs = "\u001e"

    private fun record(hash: String, parents: String, subject: String) =
        rs + listOf(hash, parents, "Ann", "ann@x", "100", "Bob", "bob@x", "200", subject).joinToString(nul) + "\n"

    private fun commit(hash: String, vararg parents: String) =
        Commit(hash, parents.toList(), "Ann", "ann@x", 100, "Bob", "bob@x", 200, "subject $hash")

    private fun reader(answer: (List<String>) -> String) = GitReader { args -> answer(args).toByteArray() }

    @Test
    fun rangeRejectsCommitOutsideCurrentBranch() {
        val git = reader { args ->
            if (args.first() == "merge-base") throw VcsException("exit 1")
            error("no further git calls are allowed for a commit outside the branch: $args")
        }
        val failure = assertThrows(IllegalStateException::class.java) { RebaseRanges.inspect(git, commit("c", "p")) }
        assertTrue(failure.message!!.contains("not part of the current branch"))
    }

    @Test
    fun rangeReportsMergesRemotesAndKeepsBodies() {
        val git = reader { args ->
            when {
                args.first() == "merge-base" -> ""
                args.first() == "for-each-ref" -> "refs/remotes/origin/main\n"
                args.any { it.startsWith("--format=%x1e%H%x00%b") } -> "${rs}a${nul}Body of a\n$rs" + "m${nul}\n"
                else -> record("a", "p", "A") + record("m", "a x", "Merge")
            }
        }
        val range = RebaseRanges.inspect(git, commit("a", "p"))
        assertEquals("p", range.base)
        assertTrue(range.hasMerges)
        assertTrue(range.published)
        assertEquals(2, range.warnings().size)
        val entries = range.entries()
        assertEquals(listOf("a"), entries.map { it.commit.hash })
        assertEquals("A\n\nBody of a", entries[0].fullMessage)
    }

    @Test
    fun rangeFromRootCommitHasNoBase() {
        val git = reader { args ->
            when {
                args.first() == "merge-base" -> ""
                args.first() == "for-each-ref" -> ""
                args.any { it.contains("%b") } -> ""
                else -> record("r", "", "Root")
            }
        }
        val range = RebaseRanges.inspect(git, commit("r"))
        assertNull(range.base)
        assertFalse(range.published)
        assertTrue(range.warnings().isEmpty())
    }

    @Test
    fun rewordingKeepsTheBodyUnlessReplaced() {
        val entry = RebaseEntry(commit("a"), message = "New subject", body = "Keep me")
        assertEquals("New subject\n\nKeep me", entry.fullMessage)
        assertEquals("New subject", entry.copy(body = "").fullMessage)
    }

    @Test
    fun discardPlanMatchesFileStatus() {
        assertEquals(listOf(GitCall("clean", "-f", "--", "new")), DiscardPlan.calls(FileChange("new", status = '?')))
        assertEquals(listOf(GitCall("rm", "-f", "--", "added")), DiscardPlan.calls(FileChange("added", status = 'A')))
        assertEquals(
            listOf(GitCall("rm", "-f", "--", "to"), GitCall("restore", "--source=HEAD", "--staged", "--worktree", "--", "from")),
            DiscardPlan.calls(FileChange("to", "from", 'R')),
        )
        assertEquals(listOf(GitCall("restore", "--source=HEAD", "--staged", "--worktree", "--", "m")), DiscardPlan.calls(FileChange("m", status = 'M')))
    }

    @Test
    fun remoteUrlPlanLeavesPushUrlFollowingFetch() {
        val same = RemoteInfo("origin", "a", "a")
        assertEquals(listOf(GitCall("remote", "set-url", "origin", "b")), RemoteUrlPlan.calls(same, "b", "a"))
        assertEquals(listOf(GitCall("remote", "set-url", "origin", "b")), RemoteUrlPlan.calls(same, "b", "b"))
        assertEquals(listOf(GitCall("remote", "set-url", "--push", "origin", "c")), RemoteUrlPlan.calls(same, "a", "c"))
        assertTrue(RemoteUrlPlan.calls(same, "a", "a").isEmpty())
    }

    @Test
    fun remoteUrlPlanClearsSeparatePushUrl() {
        val separate = RemoteInfo("origin", "a", "p")
        assertEquals(listOf(GitCall("config", "--unset-all", "remote.origin.pushurl")), RemoteUrlPlan.calls(separate, "a", "a"))
        assertTrue(RemoteUrlPlan.calls(separate, "a", "p").isEmpty())
    }

    @Test
    fun remoteBranchSplittingHandlesSlashesAndLocalUpstreams() {
        val snapshot = GraphSnapshot(remotes = listOf(RemoteInfo("origin", "u", "u"), RemoteInfo("team/fork", "u", "u")))
        assertEquals("feature/x", snapshot.remoteBranchOf("origin/feature/x")!!.branch)
        assertEquals("team/fork", snapshot.remoteBranchOf("team/fork/main")!!.remote)
        assertNull(snapshot.remoteBranchOf("main"))
    }

    @Test
    fun remotesWithPartialCloneFilterAreKept() {
        val remotes = GitParsers.parseRemotes("origin\thttps://x/a.git (fetch) [blob:none]\norigin\thttps://x/a.git (push)\n")
        assertEquals("https://x/a.git", remotes.single().fetchUrl)
        assertEquals("https://x/a.git", remotes.single().pushUrl)
    }

    private fun repoReader(log: MutableList<List<String>>, status: String) = reader { args ->
        log.add(args)
        when (args.first()) {
            "for-each-ref" -> listOf("refs/heads/main", "h", "", "", "", "", "*", "commit", "s").joinToString(nul) + "\n"
            "rev-parse" -> "h\n"
            "status" -> status
            "log" -> record("h", "p other", "Merge")
            else -> ""
        }
    }

    @Test
    fun loaderIgnoresFilteredBranchThatNoLongerExists() {
        val calls = ArrayList<List<String>>()
        val options = LoadOptions(branches = setOf("refs/heads/deleted"))
        GitDataLoader(repoReader(calls, "")).load(options, null)
        val log = calls.first { it.first() == "log" }
        assertFalse(log.contains("refs/heads/deleted"))
        assertTrue(log.contains("--branches"))
    }

    @Test
    fun loaderReportsConflictsEvenWhenUncommittedChangesAreHidden() {
        val snapshot = GitDataLoader(repoReader(ArrayList(), "UU clash.txt$nul")).load(LoadOptions(showUncommitted = false), null)
        assertEquals(RepoState(conflicts = 1), snapshot.state)
        assertTrue(snapshot.statusFiles.isEmpty())
    }

    @Test
    fun firstParentHistoryDoesNotOpenLanesForHiddenParents() {
        val snapshot = GitDataLoader(repoReader(ArrayList(), "")).load(LoadOptions(firstParent = true), null)
        assertEquals(1, snapshot.graph.laneCount)
    }
}

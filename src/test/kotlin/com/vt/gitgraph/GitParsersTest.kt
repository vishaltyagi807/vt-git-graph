package com.vt.gitgraph

import com.vt.gitgraph.git.GitParsers
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.WORKING_TREE
import com.vt.gitgraph.ops.RebaseAction
import com.vt.gitgraph.ops.RebaseEntry
import com.vt.gitgraph.ops.RebaseTodo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitParsersTest {
    private val nul = "\u0000"
    private val rs = "\u001e"

    private fun record(hash: String, parents: String, subject: String) =
        listOf(hash, parents, "Ann", "ann@x", "100", "Bob", "bob@x", "200", subject).joinToString(nul)

    @Test
    fun parsesLogRecords() {
        val text = "$rs${record("aaa", "bbb ccc", "Merge: a | b")}\n$rs${record("bbb", "", "Root")}\n"
        val commits = GitParsers.parseLog(text)
        assertEquals(2, commits.size)
        assertEquals(listOf("bbb", "ccc"), commits[0].parents)
        assertTrue(commits[0].isMerge)
        assertEquals("Merge: a | b", commits[0].subject)
        assertEquals(100L, commits[0].authorTime)
        assertEquals(200L, commits[0].commitTime)
        assertTrue(commits[1].parents.isEmpty())
    }

    @Test
    fun stashKeepsOnlyBaseParent() {
        val text = "$rs${record("s1", "base idx", "WIP on main")}${nul}stash@{0}\n"
        val stash = GitParsers.parseStashes(text).single()
        assertEquals(CommitKind.STASH, stash.kind)
        assertEquals(listOf("base"), stash.parents)
        assertEquals("stash@{0}", stash.stashName)
    }

    @Test
    fun parsesRefsAndSkipsSymbolicHead() {
        val lines = listOf(
            listOf("refs/heads/main", "h1", "", "", "origin/main", "ahead 2, behind 3", "*", "commit", "s"),
            listOf("refs/remotes/origin/HEAD", "h1", "", "refs/remotes/origin/main", "", "", " ", "commit", "s"),
            listOf("refs/remotes/origin/main", "h1", "", "", "", "", " ", "commit", "s"),
            listOf("refs/tags/v1", "tagobj", "h9", "", "", "", " ", "tag", "Release"),
            listOf("refs/heads/old", "h2", "", "", "origin/old", "gone", " ", "commit", "s"),
        ).joinToString("\n") { it.joinToString(nul) }
        val refs = GitParsers.parseRefs(lines)
        assertEquals(4, refs.size)
        val main = refs[0]
        assertEquals(RefKind.LOCAL, main.kind)
        assertTrue(main.current)
        assertEquals(2, main.ahead)
        assertEquals(3, main.behind)
        val tag = refs.first { it.kind == RefKind.TAG }
        assertEquals("h9", tag.hash)
        assertTrue(tag.annotated)
        assertTrue(refs.first { it.name == "old" }.upstreamGone)
        assertEquals("origin", refs.first { it.kind == RefKind.REMOTE }.remoteName)
    }

    @Test
    fun parsesStatusWithRenames() {
        val text = listOf("M  staged.txt", " M unstaged.txt", "R  new.txt", "old.txt", "?? fresh.txt", "UU clash.txt", "").joinToString(nul)
        val files = GitParsers.parseStatus(text)
        assertEquals(listOf("staged.txt", "unstaged.txt", "new.txt", "fresh.txt", "clash.txt"), files.map { it.path })
        assertTrue(files[0].staged)
        assertFalse(files[0].unstaged)
        assertTrue(files[1].unstaged)
        assertEquals("old.txt", files[2].oldPath)
        assertEquals('R', files[2].status)
        assertEquals('?', files[3].status)
        assertEquals('U', files[4].status)
    }

    @Test
    fun parsesNameStatusAndNumStatWithRenames() {
        val names = GitParsers.parseNameStatus(listOf("M", "a.txt", "R090", "old.txt", "new.txt", "A", "b.bin", "").joinToString(nul))
        assertEquals(listOf("a.txt", "new.txt", "b.bin"), names.map { it.path })
        assertEquals("old.txt", names[1].oldPath)
        val stats = GitParsers.parseNumStat("1\t2\ta.txt${nul}3\t4\t${nul}old.txt${nul}new.txt$nul-\t-\tb.bin$nul")
        assertEquals(1 to 2, stats["a.txt"])
        assertEquals(3 to 4, stats["new.txt"])
        assertEquals(-1 to -1, stats["b.bin"])
        val merged = GitParsers.mergeStats(names, stats)
        assertEquals(3, merged[1].added)
        assertTrue(merged[2].isBinary)
    }

    @Test
    fun parsesWorktreesRemotesSubmodulesAndDetails() {
        val worktrees = GitParsers.parseWorktrees("worktree /a\nHEAD h1\nbranch refs/heads/main\n\nworktree /b\nHEAD h2\ndetached\nlocked reason\n")
        assertEquals(2, worktrees.size)
        assertTrue(worktrees[0].main)
        assertEquals("main", worktrees[0].branch)
        assertTrue(worktrees[1].detached)
        assertTrue(worktrees[1].locked)

        val remotes = GitParsers.parseRemotes("origin\thttps://x/a.git (fetch)\norigin\tgit@x:a.git (push)\n")
        assertEquals("https://x/a.git", remotes.single().fetchUrl)
        assertEquals("git@x:a.git", remotes.single().pushUrl)

        val submodules = GitParsers.parseSubmodules(" abc lib/one (v1.0)\n-def lib/two\n")
        assertEquals("lib/one", submodules[0].path)
        assertEquals("v1.0", submodules[0].describe)
        assertEquals('-', submodules[1].state)

        val fields = listOf("h", "t", "p1 p2", "A", "a@x", "1", "C", "c@x", "2", "G", "Signer", "KEY", "Subject\n\nBody\n")
        val details = GitParsers.parseDetails(fields.joinToString(nul))
        assertNotNull(details)
        assertEquals("Subject\n\nBody", details!!.message)
        assertEquals(listOf("p1", "p2"), details.parents)
        assertNull(GitParsers.parseDetails("too${nul}short"))
    }

    @Test
    fun placesSpecialNodes() {
        val commits = GitParsers.parseLog("$rs${record("h", "b", "Head")}\n$rs${record("b", "", "Base")}\n")
        val stashes = GitParsers.parseStashes("$rs${record("s", "b idx", "WIP")}${nul}stash@{0}\n$rs${record("lost", "missing", "Lost")}${nul}stash@{1}\n")
        val result = GitParsers.withSpecialNodes(commits, stashes, 3, "h", 500)
        assertEquals(listOf(WORKING_TREE, "h", "s", "b"), result.map { it.hash })
        assertEquals(listOf("h"), result[0].parents)
        assertEquals("Uncommitted Changes (3 files)", result[0].subject)
    }

    @Test
    fun buildsRebaseTodo() {
        val commits = GitParsers.parseLog("$rs${record("a", "", "A")}\n$rs${record("b", "a", "B")}\n$rs${record("c", "b", "C")}\n$rs${record("d", "c", "D")}\n")
        val entries = listOf(
            RebaseEntry(commits[0]),
            RebaseEntry(commits[1], RebaseAction.REWORD, "New"),
            RebaseEntry(commits[2], RebaseAction.SQUASH),
            RebaseEntry(commits[3], RebaseAction.DROP),
        )
        assertNull(RebaseTodo.validate(entries))
        val todo = RebaseTodo.build(entries) { "/tmp/it's.txt" }
        assertEquals(
            "pick a\npick b\nexec git commit --amend --no-verify -F '/tmp/it'\\''s.txt'\nsquash c\ndrop d\n",
            todo,
        )
        assertNotNull(RebaseTodo.validate(listOf(RebaseEntry(commits[0], RebaseAction.FIXUP))))
        assertNotNull(RebaseTodo.validate(listOf(RebaseEntry(commits[0], RebaseAction.DROP), RebaseEntry(commits[1], RebaseAction.SQUASH))))
        assertNotNull(RebaseTodo.validate(listOf(RebaseEntry(commits[0], RebaseAction.REWORD, " "))))
    }
}

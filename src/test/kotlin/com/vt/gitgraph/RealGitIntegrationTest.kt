package com.vt.gitgraph

import com.vt.gitgraph.git.GitCall
import com.vt.gitgraph.git.GitParsers
import com.vt.gitgraph.git.GitReader
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.ops.DiscardPlan
import com.vt.gitgraph.ops.RebaseAction
import com.vt.gitgraph.ops.RebaseRanges
import com.vt.gitgraph.ops.RebaseTodo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RealGitIntegrationTest {
    private lateinit var repo: File
    private lateinit var scratch: File

    private fun git(args: List<String>, env: Map<String, String> = emptyMap()): String {
        val builder = ProcessBuilder(listOf("git", "-c", "user.name=Integration Test", "-c", "user.email=integration@example.invalid", "-c", "commit.gpgsign=false") + args)
            .directory(repo)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("GIT_CONFIG_GLOBAL", File.createTempFile("gitconfig", null, scratch).path)
            put("GIT_CONFIG_NOSYSTEM", "1")
            put("GIT_AUTHOR_NAME", "Integration Test")
            put("GIT_AUTHOR_EMAIL", "integration@example.invalid")
            put("GIT_COMMITTER_NAME", "Integration Test")
            put("GIT_COMMITTER_EMAIL", "integration@example.invalid")
            putAll(env)
        }
        val process = builder.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git $args failed: $output" }
        return output
    }

    private fun git(vararg args: String) = git(args.toList())

    private fun call(call: GitCall) = git(call.args, call.env)

    private val reader = GitReader { args -> git(args).toByteArray() }

    private fun commitFile(name: String, message: String) {
        File(repo, name).writeText("$message\n")
        git("add", name)
        git("commit", "-m", message)
    }

    private fun history(): List<Commit> = GitParsers.parseLog(git("log", "--format=${GitParsers.LOG_FORMAT}"))

    @Before
    fun setUp() {
        assumeTrue("Enable with -PrealGitTests=true", System.getProperty("vt.real.git") == "true")
        scratch = Files.createTempDirectory("vt-git-graph-it").toFile()
        repo = File(scratch, "repo").also { it.mkdir() }
        git("init", "-q", "-b", "main")
        commitFile("a.txt", "first")
        commitFile("b.txt", "second")
        commitFile("c.txt", "third")
    }

    @After
    fun tearDown() {
        if (::scratch.isInitialized) scratch.deleteRecursively()
    }

    @Test
    fun commitOnAnotherBranchIsRejectedBeforeAnyRewrite() {
        git("checkout", "-q", "-b", "other", "HEAD~1")
        commitFile("other.txt", "other work")
        val outside = history().first()
        git("checkout", "-q", "main")
        val before = git("rev-parse", "HEAD")
        assertThrows(IllegalStateException::class.java) { RebaseRanges.inspect(reader, outside) }
        assertEquals(before, git("rev-parse", "HEAD"))
    }

    @Test
    fun rewordKeepsTheBodyAndOtherCommits() {
        git("commit", "--amend", "-q", "-m", "third\n\nImportant body")
        commitFile("d.txt", "fourth")
        val target = history()[1]
        val range = RebaseRanges.inspect(reader, target)
        val entries = range.entries()
        entries.first { it.commit.hash == target.hash }.apply {
            action = RebaseAction.REWORD
            message = "third reworded"
        }
        val messageFile = File(scratch, "message.txt")
        val todoFile = File(scratch, "todo.txt")
        todoFile.writeText(
            RebaseTodo.build(entries) { entry ->
                messageFile.writeText(entry.fullMessage)
                messageFile.path
            },
        )
        call(
            GitCall(
                listOf("rebase", "-i", "--autostash", range.base!!),
                env = mapOf("GIT_SEQUENCE_EDITOR" to "cp ${RebaseTodo.shellQuote(todoFile.path)}", "GIT_EDITOR" to "true"),
            ),
        )
        assertEquals(listOf("fourth", "third reworded", "second", "first"), history().map { it.subject })
        assertTrue(git("log", "-1", "--format=%B", "HEAD~1").contains("Important body"))
    }

    @Test
    fun discardRemovesAStagedNewFile() {
        File(repo, "new.txt").writeText("new\n")
        git("add", "new.txt")
        DiscardPlan.calls(FileChange("new.txt", status = 'A')).forEach(::call)
        assertFalse(File(repo, "new.txt").exists())
        assertEquals("", git("status", "--porcelain"))
    }

    @Test
    fun discardRestoresAStagedRename() {
        git("mv", "a.txt", "renamed.txt")
        val change = GitParsers.parseStatus(git("status", "--porcelain=v1", "-z")).single()
        assertEquals('R', change.status)
        DiscardPlan.calls(change).forEach(::call)
        assertTrue(File(repo, "a.txt").exists())
        assertFalse(File(repo, "renamed.txt").exists())
        assertEquals("", git("status", "--porcelain"))
    }
}

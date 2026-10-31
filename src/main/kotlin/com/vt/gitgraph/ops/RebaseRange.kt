package com.vt.gitgraph.ops

import com.vt.gitgraph.git.GitParsers
import com.vt.gitgraph.git.GitReader
import com.vt.gitgraph.model.Commit

data class RebaseRange(
    val commits: List<Commit>,
    val bodies: Map<String, String>,
    val base: String?,
    val published: Boolean,
) {
    val hasMerges: Boolean get() = commits.any { it.isMerge }

    fun entries(): MutableList<RebaseEntry> =
        commits.filter { !it.isMerge }.map { RebaseEntry(it, body = bodies[it.hash].orEmpty()) }.toMutableList()

    fun warnings(): List<String> = buildList {
        if (hasMerges) add("This range contains merge commits, which will be flattened into a linear history.")
        if (published) add("These commits exist on a remote, so rewriting them will require a force push.")
    }
}

object RebaseRanges {
    fun inspect(reader: GitReader, commit: Commit): RebaseRange {
        try {
            reader.read(listOf("merge-base", "--is-ancestor", commit.hash, "HEAD"))
        } catch (e: Exception) {
            if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
            throw IllegalStateException("${commit.shortHash} is not part of the current branch, so it cannot be changed from here.", e)
        }
        val parent = commit.parents.firstOrNull()
        val range = if (parent == null) "HEAD" else "$parent..HEAD"
        val commits = GitParsers.parseLog(text(reader, "log", "--reverse", "--no-show-signature", "--format=${GitParsers.LOG_FORMAT}", range))
        val bodies = GitParsers.parseBodies(text(reader, "log", "--no-show-signature", "--format=${GitParsers.BODY_FORMAT}", range))
        val published = try {
            text(reader, "for-each-ref", "--contains", commit.hash, "--format=%(refname)", "refs/remotes").isNotBlank()
        } catch (e: com.intellij.openapi.vcs.VcsException) {
            false
        }
        return RebaseRange(commits, bodies, parent, published)
    }

    private fun text(reader: GitReader, vararg args: String): String = String(reader.read(args.toList()), Charsets.UTF_8)
}

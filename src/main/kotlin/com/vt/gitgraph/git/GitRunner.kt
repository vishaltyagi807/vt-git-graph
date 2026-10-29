package com.vt.gitgraph.git

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitBinaryHandler
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.config.GitExecutableManager

data class GitCall(
    val args: List<String>,
    val urls: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
) {
    constructor(vararg args: String) : this(args.toList())
}

data class GitResult(val success: Boolean, val output: String, val error: String) {
    val combined: String get() = listOf(output, error).filter { it.isNotBlank() }.joinToString("\n")
}

class GitRunner(private val project: Project, val root: VirtualFile) : GitReader {
    override fun read(args: List<String>): ByteArray {
        val command = COMMANDS[args.first()]
        if (command == null) {
            val result = raw(GitCall(args))
            if (!result.success) throw VcsException(result.error)
            return result.output.toByteArray(Charsets.UTF_8)
        }
        val handler = GitBinaryHandler(project, root, command)
        handler.setSilent(true)
        handler.setStdoutSuppressed(true)
        handler.addCustomEnvironmentVariable("GIT_OPTIONAL_LOCKS", "0")
        handler.addParameters(args.drop(1))
        return handler.run()
    }

    fun write(call: GitCall): GitResult {
        val command = COMMANDS[call.args.first()] ?: return raw(call)
        val handler = GitLineHandler(project, root, command)
        handler.addParameters(call.args.drop(1))
        handler.addCustomEnvironmentVariable(GitCommand.GIT_EDITOR_ENV, "true")
        call.env.forEach { (key, value) -> handler.addCustomEnvironmentVariable(key, value) }
        if (call.urls.isNotEmpty()) handler.setUrls(call.urls)
        val result = Git.getInstance().runCommand(handler)
        return GitResult(result.success(), result.getOutputAsJoinedString(), result.getErrorOutputAsJoinedString())
    }

    private fun raw(call: GitCall): GitResult {
        val executable = GitExecutableManager.getInstance().getPathToGit(project)
        val line = GeneralCommandLine(listOf(executable) + call.args)
            .withWorkDirectory(root.path)
            .withCharset(Charsets.UTF_8)
            .withEnvironment("GIT_EDITOR", "true")
            .withEnvironment(call.env)
        val handler = CapturingProcessHandler(line)
        val indicator = ProgressManager.getGlobalProgressIndicator()
        val output = if (indicator != null) handler.runProcessWithProgressIndicator(indicator, RAW_TIMEOUT_MS) else handler.runProcess(RAW_TIMEOUT_MS)
        return GitResult(output.exitCode == 0 && !output.isTimeout && !output.isCancelled, output.stdout, output.stderr)
    }

    private companion object {
        const val RAW_TIMEOUT_MS = 600_000
        val COMMANDS: Map<String, GitCommand> = mapOf(
            "add" to GitCommand.ADD,
            "branch" to GitCommand.BRANCH,
            "cat-file" to GitCommand.CAT_FILE,
            "checkout" to GitCommand.CHECKOUT,
            "cherry-pick" to GitCommand.CHERRY_PICK,
            "commit" to GitCommand.COMMIT,
            "config" to GitCommand.CONFIG,
            "diff" to GitCommand.DIFF,
            "fetch" to GitCommand.FETCH,
            "for-each-ref" to GitCommand.FOR_EACH_REF,
            "log" to GitCommand.LOG,
            "merge" to GitCommand.MERGE,
            "merge-base" to GitCommand.MERGE_BASE,
            "pull" to GitCommand.PULL,
            "push" to GitCommand.PUSH,
            "rebase" to GitCommand.REBASE,
            "reflog" to GitCommand.REF_LOG,
            "remote" to GitCommand.REMOTE,
            "reset" to GitCommand.RESET,
            "restore" to GitCommand.RESTORE,
            "rev-list" to GitCommand.REV_LIST,
            "rev-parse" to GitCommand.REV_PARSE,
            "revert" to GitCommand.REVERT,
            "rm" to GitCommand.RM,
            "show" to GitCommand.SHOW,
            "stash" to GitCommand.STASH,
            "status" to GitCommand.STATUS,
            "submodule" to GitCommand.SUBMODULE,
            "tag" to GitCommand.TAG,
            "worktree" to GitCommand.WORKTREE,
        )
    }
}

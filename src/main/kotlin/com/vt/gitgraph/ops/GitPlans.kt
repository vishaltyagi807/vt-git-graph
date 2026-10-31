package com.vt.gitgraph.ops

import com.vt.gitgraph.git.GitCall
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.RemoteInfo

object DiscardPlan {
    fun calls(change: FileChange): List<GitCall> {
        val oldPath = change.oldPath
        return when {
            change.status == '?' -> listOf(GitCall("clean", "-f", "--", change.path))
            change.status == 'A' || change.status == 'C' -> listOf(GitCall("rm", "-f", "--", change.path))
            change.status == 'R' && oldPath != null -> listOf(
                GitCall("rm", "-f", "--", change.path),
                GitCall("restore", "--source=HEAD", "--staged", "--worktree", "--", oldPath),
            )
            else -> listOf(GitCall("restore", "--source=HEAD", "--staged", "--worktree", "--", change.path))
        }
    }
}

object RemoteUrlPlan {
    fun calls(remote: RemoteInfo, fetch: String, push: String): List<GitCall> {
        val calls = ArrayList<GitCall>()
        val hadSeparatePush = remote.pushUrl != remote.fetchUrl
        if (fetch != remote.fetchUrl) calls.add(GitCall("remote", "set-url", remote.name, fetch))
        when {
            !hadSeparatePush && push == remote.pushUrl -> Unit
            push == fetch -> if (hadSeparatePush) calls.add(GitCall("config", "--unset-all", "remote.${remote.name}.pushurl"))
            push != remote.pushUrl -> calls.add(GitCall("remote", "set-url", "--push", remote.name, push))
        }
        return calls
    }
}

package com.vt.gitgraph.ops

import com.vt.gitgraph.model.Commit

enum class RebaseAction(val label: String) {
    PICK("Pick"),
    REWORD("Reword"),
    EDIT("Edit"),
    SQUASH("Squash"),
    FIXUP("Fixup"),
    DROP("Drop"),
}

data class RebaseEntry(
    val commit: Commit,
    var action: RebaseAction = RebaseAction.PICK,
    var message: String = commit.subject,
    val body: String = "",
) {
    val fullMessage: String get() = listOf(message.trim(), body.trim()).filter { it.isNotEmpty() }.joinToString("\n\n")
}

object RebaseTodo {
    fun validate(entries: List<RebaseEntry>): String? {
        val first = entries.firstOrNull { it.action != RebaseAction.DROP } ?: return null
        if (first.action == RebaseAction.SQUASH || first.action == RebaseAction.FIXUP) {
            return "The first kept commit cannot be squashed or fixed up, because it has no earlier commit to combine with."
        }
        if (entries.any { it.action == RebaseAction.REWORD && it.message.isBlank() }) {
            return "A reworded commit needs a message."
        }
        return null
    }

    fun build(entries: List<RebaseEntry>, messageFile: (RebaseEntry) -> String): String = buildString {
        entries.forEach { entry ->
            val hash = entry.commit.hash
            when (entry.action) {
                RebaseAction.PICK -> appendLine("pick $hash")
                RebaseAction.EDIT -> appendLine("edit $hash")
                RebaseAction.SQUASH -> appendLine("squash $hash")
                RebaseAction.FIXUP -> appendLine("fixup $hash")
                RebaseAction.DROP -> appendLine("drop $hash")
                RebaseAction.REWORD -> {
                    appendLine("pick $hash")
                    appendLine("exec git commit --amend --no-verify -F ${shellQuote(messageFile(entry))}")
                }
            }
        }
    }

    fun shellQuote(value: String): String = "'" + value.replace("\\", "/").replace("'", "'\\''") + "'"
}

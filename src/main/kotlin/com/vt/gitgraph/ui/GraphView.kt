package com.vt.gitgraph.ui

import com.intellij.openapi.project.Project
import com.vt.gitgraph.git.GitDataLoader
import com.vt.gitgraph.git.GitRunner
import com.vt.gitgraph.model.GraphSnapshot
import git4idea.repo.GitRepository

interface GraphView {
    val project: Project
    val snapshot: GraphSnapshot
    val repository: GitRepository?
    val runner: GitRunner?
    val loader: GitDataLoader?

    fun selectCommit(hash: String)
    fun compare(left: String, right: String)
    fun compareWithWorkingTree(hash: String)
    fun filterByPath(path: String)
    fun filterByBranch(fullName: String)
    fun reload()
}

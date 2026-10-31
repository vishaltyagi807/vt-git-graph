package com.vt.gitgraph.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.vt.gitgraph.model.LogOrder

@Service(Service.Level.APP)
@State(name = "VtGitGraphSettings", storages = [Storage("vtGitGraph.xml")])
class GraphSettings : PersistentStateComponent<GraphSettings.Data> {
    class Data {
        var showRemotes: Boolean = true
        var showTags: Boolean = true
        var showStashes: Boolean = true
        var showUncommitted: Boolean = true
        var firstParent: Boolean = false
        var order: String = LogOrder.DATE.name
        var relativeDates: Boolean = true
        var compactRows: Boolean = false
        var showAuthorColumn: Boolean = true
        var showDateColumn: Boolean = true
        var showHashColumn: Boolean = true
        var showSidebar: Boolean = true
        var showDetails: Boolean = true
        var pageSize: Int = 2000
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
    }

    companion object {
        val instance: GraphSettings
            get() = ApplicationManager.getApplication().getService(GraphSettings::class.java)
        val data: Data get() = instance.state
    }
}

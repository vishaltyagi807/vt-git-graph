package com.vt.gitgraph

import com.intellij.testFramework.PlatformTestUtil
import com.intellij.icons.AllIcons
import com.intellij.ide.ui.laf.IntelliJLaf
import com.intellij.ide.ui.laf.UiThemeProviderListManager
import com.intellij.ide.ui.laf.darcula.DarculaLaf
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.IconLoader
import com.intellij.testFramework.EdtTestUtil
import com.intellij.testFramework.TestApplicationManager
import com.intellij.ui.JBColor
import com.vt.gitgraph.git.GitDataLoader
import com.vt.gitgraph.git.GitParsers
import com.vt.gitgraph.graph.GraphLayout
import com.vt.gitgraph.model.Commit
import com.vt.gitgraph.model.CommitKind
import com.vt.gitgraph.model.FileChange
import com.vt.gitgraph.model.GitRef
import com.vt.gitgraph.model.GraphSnapshot
import com.vt.gitgraph.model.LoadOptions
import com.vt.gitgraph.model.RefKind
import com.vt.gitgraph.model.RemoteInfo
import com.vt.gitgraph.model.WorktreeInfo
import com.vt.gitgraph.ui.GitGraphPanel
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.UIManager

class PreviewRenderTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun startApplication() {
            TestApplicationManager.getInstance()
            IconLoader.activate()
        }
    }

    private val project get() = ProjectManager.getInstance().defaultProject

    private val outputDir: String? get() = System.getProperty("vt.preview.dir")?.takeIf { it.isNotEmpty() }

    private fun commit(hash: String, subject: String, author: String, minutesAgo: Long, vararg parents: String): Commit {
        val time = System.currentTimeMillis() / 1000 - minutesAgo * 60
        return Commit(hash.padEnd(40, 'a'), parents.map { it.padEnd(40, 'a') }, author, "${author.substringBefore(' ').lowercase()}@example.com", time, author, "${author.substringBefore(' ').lowercase()}@example.com", time, subject)
    }

    private fun sample(): GraphSnapshot {
        val base = listOf(
            commit("f10", "Add commit graph virtualisation for very large repositories", "Asha Rao", 12, "f09", "e03"),
            commit("e03", "Paint ref chips with branch colours", "Liam Chen", 45, "e02"),
            commit("f09", "Fix rename detection in the numstat parser", "Asha Rao", 90, "f08"),
            commit("d05", "Spike: interactive rebase dialog", "Noor Haddad", 130, "d04"),
            commit("e02", "Add author avatars to the commit table", "Liam Chen", 200, "e01"),
            commit("f08", "Merge branch 'release/1.2' into main", "Maya Singh", 310, "f07", "r02"),
            commit("r02", "Bump version to 1.2.0", "Maya Singh", 400, "r01"),
            commit("d04", "Add todo file builder", "Noor Haddad", 520, "d03"),
            commit("f07", "Stream git log output instead of buffering", "Asha Rao", 700, "f06"),
            commit("e01", "Introduce lane colour palette", "Liam Chen", 900, "f06"),
            commit("r01", "Update changelog for 1.2", "Maya Singh", 1200, "f06"),
            commit("f06", "Merge pull request #42 from feature/sidebar", "Asha Rao", 1600, "f05", "s02"),
            commit("s02", "Group branches into folders in the sidebar", "Tomás Vega", 2100, "s01"),
            commit("d03", "Extract rebase entry model", "Noor Haddad", 2500, "f04"),
            commit("f05", "Handle detached HEAD in the footer", "Maya Singh", 3000, "f04"),
            commit("s01", "Add sidebar skeleton", "Tomás Vega", 4000, "f04"),
            commit("f04", "Support stash nodes in the layout", "Asha Rao", 5200, "f03"),
            commit("f03", "Add bisect banner", "Liam Chen", 7000, "f02"),
            commit("h01", "Hotfix: guard against empty parent list", "Maya Singh", 8000, "f02"),
            commit("f02", "Parse worktree porcelain output", "Asha Rao", 11000, "f01"),
            commit("f01", "Initial commit", "Asha Rao", 20000),
        )
        val stash = commit("c01", "WIP on main: experiment with curved edges", "Asha Rao", 30, "f09").copy(kind = CommitKind.STASH, stashName = "stash@{0}")
        val status = listOf(
            FileChange("src/main/kotlin/Graph.kt", status = 'M', added = 24, deleted = 6, unstaged = true),
            FileChange("src/main/kotlin/Chips.kt", status = 'A', added = 110, deleted = 0, staged = true),
            FileChange("README.md", status = '?', unstaged = true),
        )
        fun full(short: String) = short.padEnd(40, 'a')
        val refs = listOf(
            GitRef("main", "refs/heads/main", RefKind.LOCAL, full("f10"), "origin/main", ahead = 2, current = true),
            GitRef("origin/main", "refs/remotes/origin/main", RefKind.REMOTE, full("f10")),
            GitRef("feature/chips", "refs/heads/feature/chips", RefKind.LOCAL, full("e03"), "origin/feature/chips"),
            GitRef("origin/feature/chips", "refs/remotes/origin/feature/chips", RefKind.REMOTE, full("e03")),
            GitRef("feature/rebase", "refs/heads/feature/rebase", RefKind.LOCAL, full("d05")),
            GitRef("origin/release/1.2", "refs/remotes/origin/release/1.2", RefKind.REMOTE, full("r02")),
            GitRef("v1.2.0", "refs/tags/v1.2.0", RefKind.TAG, full("f08"), annotated = true, subject = "Release 1.2.0"),
            GitRef("v1.1.0", "refs/tags/v1.1.0", RefKind.TAG, full("f04")),
            GitRef("hotfix/guard", "refs/heads/hotfix/guard", RefKind.LOCAL, full("h01"), "origin/hotfix/guard", behind = 1),
            GitRef("stash@{0}", "stash@{0}", RefKind.STASH, stash.hash, subject = stash.subject),
        )
        val commits = GitParsers.withSpecialNodes(base, listOf(stash), status.size, full("f10"), System.currentTimeMillis() / 1000)
        return GraphSnapshot(
            commits = commits,
            graph = GraphLayout.compute(commits, false),
            refs = refs,
            head = full("f10"),
            currentBranch = "main",
            statusFiles = status,
            worktrees = listOf(WorktreeInfo("/work/vt-git-graph", full("f10"), "main", false, false, false, false, true)),
            remotes = listOf(RemoteInfo("origin", "git@example.com:vt/graph.git", "git@example.com:vt/graph.git")),
            loadMillis = 84,
        )
    }

    private fun layoutTree(container: Container) {
        container.doLayout()
        container.components.forEach { if (it is Container) layoutTree(it) }
    }

    private fun render(panel: GitGraphPanel, name: String) {
        val directory = outputDir ?: return
        panel.setSize(1680, 920)
        repeat(3) {
            layoutTree(panel)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
        val image = BufferedImage(panel.width, panel.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        panel.paint(graphics)
        graphics.dispose()
        File(directory).mkdirs()
        ImageIO.write(image, "png", File(directory, "$name.png"))
    }

    private fun settle() {
        repeat(20) {
            Thread.sleep(50)
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        }
    }

    private fun useTheme(dark: Boolean) {
        JBColor.setDark(dark)
        val laf = if (dark) DarculaLaf() else IntelliJLaf()
        UIManager.setLookAndFeel(laf)
        val themes = ApplicationManager.getApplication().getService(UiThemeProviderListManager::class.java).getLaFs().toList()
        val wanted = if (dark) "Dark" else "Light"
        val theme = themes.firstOrNull { it.name == wanted } ?: themes.firstOrNull { it.isDark == dark }
        println("Preview theme: ${theme?.name} of ${themes.map { it.name }}")
        theme?.installTheme(UIManager.getLookAndFeelDefaults())
        UIManager.put("Tree.expandedIcon", AllIcons.General.ChevronDown)
        UIManager.put("Tree.collapsedIcon", AllIcons.General.ChevronRight)
    }

    private fun sampleLoader(): GitDataLoader {
        val nul = "\u0000"
        val names = listOf("M", "src/main/kotlin/com/vt/gitgraph/graph/GraphLayout.kt", "M", "src/main/kotlin/com/vt/gitgraph/ui/CommitTable.kt", "A", "src/main/kotlin/com/vt/gitgraph/ui/GraphPainter.kt", "R092", "docs/old-notes.md", "docs/architecture.md", "D", "src/test/resources/legacy.txt", "M", "build.gradle.kts", "").joinToString(nul)
        val stats = listOf("48\t12\tsrc/main/kotlin/com/vt/gitgraph/graph/GraphLayout.kt", "120\t31\tsrc/main/kotlin/com/vt/gitgraph/ui/CommitTable.kt", "164\t0\tsrc/main/kotlin/com/vt/gitgraph/ui/GraphPainter.kt", "6\t2\t", "docs/old-notes.md", "docs/architecture.md", "0\t40\tsrc/test/resources/legacy.txt", "3\t1\tbuild.gradle.kts", "").joinToString(nul)
        val detail = listOf("f10".padEnd(40, 'a'), "tree", "f09".padEnd(40, 'a') + " " + "e03".padEnd(40, 'a'), "Asha Rao", "asha@example.com", (System.currentTimeMillis() / 1000 - 720).toString(), "Maya Singh", "maya@example.com", (System.currentTimeMillis() / 1000 - 600).toString(), "G", "Asha Rao <asha@example.com>", "4AEE18F83AFDEB23", "Add commit graph virtualisation for very large repositories\n\nOnly the rows inside the viewport are painted, so a repository with\nhundreds of thousands of commits scrolls as smoothly as a small one.\n\nCloses #128\n").joinToString(nul)
        return GitDataLoader { args ->
            when {
                args.first() == "diff" && "--name-status" in args -> names
                args.first() == "diff" -> stats
                args.first() == "show" -> detail
                else -> ""
            }.toByteArray()
        }
    }

    @Test
    fun testRendersSampleGraph() = EdtTestUtil.runInEdtAndWait<Throwable> {
        useTheme(false)
        val panel = GitGraphPanel(project)
        try {
            panel.useLoader(sampleLoader())
            panel.applySnapshot(sample())
            panel.selectRow(1)
            settle()
            render(panel, "sample-light")
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    @Test
    fun testRendersSampleGraphDark() = EdtTestUtil.runInEdtAndWait<Throwable> {
        useTheme(true)
        val panel = GitGraphPanel(project)
        try {
            panel.useLoader(sampleLoader())
            panel.applySnapshot(sample())
            panel.selectRow(1)
            settle()
            render(panel, "sample-dark")
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    @Test
    fun testRendersRealRepository() = EdtTestUtil.runInEdtAndWait<Throwable> {
        val repo = System.getProperty("vt.preview.repo")?.takeIf { it.isNotEmpty() } ?: return@runInEdtAndWait
        val loader = GitDataLoader { args ->
            val allowed = setOf("for-each-ref", "rev-parse", "status", "stash", "log", "worktree", "remote", "submodule", "show", "diff")
            check(args.first() in allowed)
            val process = ProcessBuilder(listOf("git", "-C", repo) + args)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .also { it.environment()["GIT_OPTIONAL_LOCKS"] = "0" }
                .start()
            val bytes = process.inputStream.readBytes()
            check(process.waitFor() == 0)
            bytes
        }
        val snapshot = loader.load(LoadOptions(limit = 2000), File(repo, ".git").toPath())
        assertTrue(snapshot.commits.isNotEmpty())
        useTheme(true)
        val panel = GitGraphPanel(project)
        try {
            panel.useLoader(loader)
            panel.applySnapshot(snapshot)
            panel.selectRow(snapshot.commits.indexOfFirst { it.kind == CommitKind.NORMAL }.coerceAtLeast(0))
            settle()
            render(panel, "real-repo")
        } finally {
            com.intellij.openapi.util.Disposer.dispose(panel)
        }
    }

    @Test
    fun testDialogButtonsKeepTheirLabels() = EdtTestUtil.runInEdtAndWait<Throwable> {
        val normal = com.vt.gitgraph.ui.OpDialog(project, "Merge", "Merge")
        val danger = com.vt.gitgraph.ui.OpDialog(project, "Delete", "Delete", danger = true)
        try {
            org.junit.Assert.assertEquals("Merge", normal.okLabel)
            org.junit.Assert.assertEquals("Delete", danger.okLabel)
            assertTrue(danger.cancelIsDefault)
            assertTrue(!normal.cancelIsDefault)
        } finally {
            com.intellij.openapi.util.Disposer.dispose(normal.disposable)
            com.intellij.openapi.util.Disposer.dispose(danger.disposable)
        }
    }

    @Test
    fun testPluginServicesAreAvailable() = EdtTestUtil.runInEdtAndWait<Throwable> {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        assertNotNull(manager.getAction("VtGitGraph.OpenInEditor"))
        val toolWindowClass = Class.forName("com.vt.gitgraph.ui.GitGraphToolWindowFactory")
        assertNotNull(toolWindowClass)
    }
}

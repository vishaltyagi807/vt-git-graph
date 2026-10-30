package com.vt.gitgraph.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color

object Palette {
    private val lanes = listOf(
        JBColor(0x0284C7, 0x38BDF8),
        JBColor(0xDB2777, 0xF472B6),
        JBColor(0x16A34A, 0x4ADE80),
        JBColor(0xEA580C, 0xFB923C),
        JBColor(0x7C3AED, 0xA78BFA),
        JBColor(0x0D9488, 0x2DD4BF),
        JBColor(0xDC2626, 0xF87171),
        JBColor(0x4F46E5, 0x818CF8),
        JBColor(0xCA8A04, 0xFACC15),
        JBColor(0xC026D3, 0xE879F9),
        JBColor(0x65A30D, 0xA3E635),
        JBColor(0x0891B2, 0x22D3EE),
    )

    val tag = JBColor(0xB45309, 0xFBBF24)
    val stash = JBColor(0x7C3AED, 0xC4B5FD)
    val added = JBColor(0x15803D, 0x4ADE80)
    val deleted = JBColor(0xB91C1C, 0xF87171)
    val modified = JBColor(0x1D4ED8, 0x60A5FA)
    val renamed = JBColor(0x7C3AED, 0xC4B5FD)
    val conflict = JBColor(0xC2410C, 0xFB923C)
    val uncommitted = JBColor(0x64748B, 0x94A3B8)
    val muted = JBColor(0x64748B, 0x8B95A5)
    val hover = JBColor(Color(15, 23, 42, 13), Color(255, 255, 255, 13))
    val match = JBColor(Color(250, 204, 21, 130), Color(250, 204, 21, 90))
    val currentMatch = JBColor(Color(249, 115, 22, 170), Color(249, 115, 22, 140))
    val divider = JBColor(Color(15, 23, 42, 24), Color(255, 255, 255, 22))
    val bannerConflict = JBColor(Color(255, 237, 213), Color(67, 40, 20))
    val bannerInfo = JBColor(Color(224, 242, 254), Color(20, 48, 70))

    fun lane(index: Int): Color = lanes[Math.floorMod(index, lanes.size)]

    fun avatar(name: String): Color = lanes[Math.floorMod(name.hashCode(), lanes.size)]

    fun alpha(color: Color, alpha: Int): Color = Color(color.red, color.green, color.blue, alpha)

    fun contrast(color: Color): Color {
        val luminance = (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255
        return if (luminance > 0.6) Color(15, 23, 42) else Color.WHITE
    }

    fun rowBackground(selected: Boolean, focused: Boolean): Color =
        if (selected) UIUtil.getTableSelectionBackground(focused) else UIUtil.getTableBackground()

    fun statusColor(status: Char): Color = when (status) {
        'A', '?' -> added
        'D' -> deleted
        'R', 'C' -> renamed
        'U' -> conflict
        else -> modified
    }

    fun statusLabel(status: Char): String = when (status) {
        'A' -> "Added"
        'D' -> "Deleted"
        'R' -> "Renamed"
        'C' -> "Copied"
        'T' -> "Type changed"
        'U' -> "Conflicted"
        '?' -> "Untracked"
        else -> "Modified"
    }
}

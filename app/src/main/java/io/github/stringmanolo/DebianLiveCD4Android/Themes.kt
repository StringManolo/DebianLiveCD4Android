package io.github.stringmanolo.DebianLiveCD4Android

import android.graphics.Color
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import java.util.Properties

class TermTheme(
    val id: String,
    val name: String,
    val bg: String,
    val fg: String,
    val cursor: String,
    /** 16 ANSI colours: 0-7 normal, 8-15 bright */
    val ansi: List<String>
) {
    val bgInt: Int get() = Color.parseColor(bg)
    val fgInt: Int get() = Color.parseColor(fg)

    fun properties(): Properties = Properties().apply {
        setProperty("foreground", fg)
        setProperty("background", bg)
        setProperty("cursor", cursor)
        ansi.forEachIndexed { i, c -> setProperty("color$i", c) }
    }
}

object Themes {
    val ALL = listOf(
        TermTheme("dark", "Dark (default)", "#0B0E11", "#C9D1D9", "#C9D1D9", listOf(
            "#1B222A", "#F85149", "#3FB950", "#D29922", "#58A6FF", "#BC8CFF", "#39C5CF", "#B1BAC4",
            "#6E7681", "#FF7B72", "#56D364", "#E3B341", "#79C0FF", "#D2A8FF", "#56D4DD", "#F0F6FC")),
        TermTheme("solarized", "Solarized Dark", "#002B36", "#839496", "#93A1A1", listOf(
            "#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
            "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3")),
        TermTheme("dracula", "Dracula", "#282A36", "#F8F8F2", "#F8F8F2", listOf(
            "#21222C", "#FF5555", "#50FA7B", "#F1FA8C", "#BD93F9", "#FF79C6", "#8BE9FD", "#F8F8F2",
            "#6272A4", "#FF6E6E", "#69FF94", "#FFFFA5", "#D6ACFF", "#FF92DF", "#A4FFFF", "#FFFFFF")),
        TermTheme("nord", "Nord", "#2E3440", "#D8DEE9", "#D8DEE9", listOf(
            "#3B4252", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#88C0D0", "#E5E9F0",
            "#4C566A", "#BF616A", "#A3BE8C", "#EBCB8B", "#81A1C1", "#B48EAD", "#8FBCBB", "#ECEFF4")),
        TermTheme("gruvbox", "Gruvbox Dark", "#282828", "#EBDBB2", "#EBDBB2", listOf(
            "#282828", "#CC241D", "#98971A", "#D79921", "#458588", "#B16286", "#689D6A", "#A89984",
            "#928374", "#FB4934", "#B8BB26", "#FABD2F", "#83A598", "#D3869B", "#8EC07C", "#EBDBB2")),
        TermTheme("matrix", "Matrix", "#000000", "#00FF41", "#00FF41", listOf(
            "#000000", "#008F11", "#00FF41", "#9BFF9B", "#003B00", "#00B33C", "#00FFAA", "#B7FFB7",
            "#1A1A1A", "#00C030", "#33FF66", "#CCFFCC", "#006600", "#00E050", "#66FFCC", "#FFFFFF")),
        TermTheme("light", "Light", "#FAFAFA", "#24292F", "#24292F", listOf(
            "#24292F", "#CF222E", "#116329", "#4D2D00", "#0969DA", "#8250DF", "#1B7C83", "#6E7781",
            "#57606A", "#A40E26", "#1A7F37", "#633C01", "#218BFF", "#A475F9", "#3192AA", "#8C959F"))
    )

    fun byId(id: String): TermTheme = ALL.firstOrNull { it.id == id } ?: ALL[0]

    /** The colour scheme is process-wide in Termux's emulator; reset() makes a running emulator pick it up. */
    fun apply(theme: TermTheme, session: TerminalSession?) {
        TerminalColors.COLOR_SCHEME.updateWith(theme.properties())
        session?.emulator?.mColors?.reset()
    }
}

fun blend(a: Int, b: Int, t: Float): Int {
    fun mix(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
    return Color.rgb(
        mix(Color.red(a), Color.red(b)),
        mix(Color.green(a), Color.green(b)),
        mix(Color.blue(a), Color.blue(b))
    )
}

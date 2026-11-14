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
            "#57606A", "#A40E26", "#1A7F37", "#633C01", "#218BFF", "#A475F9", "#3192AA", "#8C959F")),

        // ---- inspired by Vim's built-in colour schemes (approximate palettes)
        TermTheme("vim-desert", "Vim: desert", "#333333", "#FFFFFF", "#FFD700", listOf(
            "#4D4D4D", "#CD5C5C", "#98FB98", "#F0E68C", "#87CEEB", "#CD96CD", "#5F9EA0", "#DDDDDD",
            "#666666", "#FFA0A0", "#B4FFB4", "#FFFF8C", "#A6D8F0", "#FFB6FF", "#7FDDD4", "#FFFFFF")),
        TermTheme("vim-elflord", "Vim: elflord", "#000000", "#00FFFF", "#00FFFF", listOf(
            "#000000", "#FF0000", "#00FF00", "#FFFF00", "#6060FF", "#FF60FF", "#00FFFF", "#C0C0C0",
            "#808080", "#FF6060", "#60FF60", "#FFFF60", "#8080FF", "#FF80FF", "#80FFFF", "#FFFFFF")),
        TermTheme("vim-slate", "Vim: slate", "#262626", "#D3D3D3", "#FFFFFF", listOf(
            "#262626", "#CD5C5C", "#8FBC8F", "#F0E68C", "#6495ED", "#BA55D3", "#5F9EA0", "#D3D3D3",
            "#696969", "#F08080", "#98FB98", "#FFFF9E", "#87CEFA", "#DA70D6", "#7FFFD4", "#FFFFFF")),
        TermTheme("vim-evening", "Vim: evening", "#00005F", "#FFFFFF", "#FFFFFF", listOf(
            "#00005F", "#FF6060", "#98FB98", "#FFFF60", "#87CEEB", "#FFA0FF", "#00CDCD", "#D0D0D0",
            "#5F5F87", "#FF8080", "#B4FFB4", "#FFFF9E", "#A6D8F0", "#FFC0FF", "#40FFFF", "#FFFFFF")),
        TermTheme("vim-ron", "Vim: ron", "#000000", "#FFFFFF", "#FFFFFF", listOf(
            "#000000", "#FF0000", "#00FF00", "#FFFF00", "#0000FF", "#FF00FF", "#00FFFF", "#C0C0C0",
            "#808080", "#FF5555", "#55FF55", "#FFFF55", "#5555FF", "#FF55FF", "#55FFFF", "#FFFFFF")),
        TermTheme("vim-torte", "Vim: torte", "#000000", "#FFFFFF", "#FFFFFF", listOf(
            "#000000", "#FF6060", "#80FF80", "#FFFF80", "#8080FF", "#FF80FF", "#80FFFF", "#D0D0D0",
            "#808080", "#FF8080", "#A0FFA0", "#FFFFA0", "#A0A0FF", "#FFA0FF", "#A0FFFF", "#FFFFFF")),
        TermTheme("vim-blue", "Vim: blue", "#000080", "#FFD700", "#FFFFFF", listOf(
            "#000080", "#FF6060", "#90EE90", "#FFD700", "#87CEEB", "#EE82EE", "#40E0D0", "#E0E0E0",
            "#4040A0", "#FF8080", "#B4F0B4", "#FFEA70", "#A6D8F0", "#FFA0FF", "#80F0E0", "#FFFFFF")),
        TermTheme("vim-delek", "Vim: delek (light)", "#FFFFFF", "#000000", "#000000", listOf(
            "#000000", "#B22222", "#006400", "#8B6508", "#00008B", "#8B008B", "#008B8B", "#808080",
            "#404040", "#FF0000", "#008000", "#B8860B", "#0000FF", "#FF00FF", "#00CED1", "#C0C0C0")),
        TermTheme("vim-morning", "Vim: morning (light)", "#E4E4E4", "#000000", "#000000", listOf(
            "#000000", "#A52A2A", "#2E8B57", "#8B7500", "#00008B", "#8B008B", "#008B8B", "#808080",
            "#404040", "#CD0000", "#3CB371", "#B8860B", "#0000CD", "#CD00CD", "#00CDCD", "#C0C0C0")),
        TermTheme("vim-peachpuff", "Vim: peachpuff (light)", "#FFDAB9", "#000000", "#000000", listOf(
            "#000000", "#B22222", "#2E8B57", "#8B6914", "#0000CD", "#8B008B", "#008B8B", "#696969",
            "#404040", "#FF0000", "#3CB371", "#B8860B", "#4169E1", "#FF00FF", "#20B2AA", "#A9A9A9")),
        // ---- popular editor / terminal schemes
        TermTheme("monokai", "Monokai", "#272822", "#F8F8F2", "#F8F8F0", listOf(
            "#272822", "#F92672", "#A6E22E", "#F4BF75", "#66D9EF", "#AE81FF", "#A1EFE4", "#F8F8F2",
            "#75715E", "#F92672", "#A6E22E", "#F4BF75", "#66D9EF", "#AE81FF", "#A1EFE4", "#F9F8F5")),
        TermTheme("tokyo-night", "Tokyo Night", "#1A1B26", "#C0CAF5", "#C0CAF5", listOf(
            "#15161E", "#F7768E", "#9ECE6A", "#E0AF68", "#7AA2F7", "#BB9AF7", "#7DCFFF", "#A9B1D6",
            "#414868", "#F7768E", "#9ECE6A", "#E0AF68", "#7AA2F7", "#BB9AF7", "#7DCFFF", "#C0CAF5")),
        TermTheme("one-dark", "One Dark", "#282C34", "#ABB2BF", "#528BFF", listOf(
            "#282C34", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#ABB2BF",
            "#545862", "#E06C75", "#98C379", "#E5C07B", "#61AFEF", "#C678DD", "#56B6C2", "#C8CCD4")),
        TermTheme("catppuccin", "Catppuccin Mocha", "#1E1E2E", "#CDD6F4", "#F5E0DC", listOf(
            "#45475A", "#F38BA8", "#A6E3A1", "#F9E2AF", "#89B4FA", "#F5C2E7", "#94E2D5", "#BAC2DE",
            "#585B70", "#F38BA8", "#A6E3A1", "#F9E2AF", "#89B4FA", "#F5C2E7", "#94E2D5", "#A6ADC8")),
        TermTheme("zenburn", "Zenburn", "#3F3F3F", "#DCDCCC", "#DCDCCC", listOf(
            "#3F3F3F", "#CC9393", "#7F9F7F", "#F0DFAF", "#8CD0D3", "#DC8CC3", "#93E0E3", "#DCDCCC",
            "#709080", "#DCA3A3", "#BFEBBF", "#F0EFD0", "#8CD0D3", "#EC93D3", "#93E0E3", "#FFFFFF")),
        TermTheme("tomorrow-night", "Tomorrow Night", "#1D1F21", "#C5C8C6", "#AEAFAD", listOf(
            "#1D1F21", "#CC6666", "#B5BD68", "#F0C674", "#81A2BE", "#B294BB", "#8ABEB7", "#C5C8C6",
            "#969896", "#CC6666", "#B5BD68", "#F0C674", "#81A2BE", "#B294BB", "#8ABEB7", "#FFFFFF")),
        TermTheme("solarized-light", "Solarized Light", "#FDF6E3", "#657B83", "#586E75", listOf(
            "#073642", "#DC322F", "#859900", "#B58900", "#268BD2", "#D33682", "#2AA198", "#EEE8D5",
            "#002B36", "#CB4B16", "#586E75", "#657B83", "#839496", "#6C71C4", "#93A1A1", "#FDF6E3")),
        TermTheme("gruvbox-light", "Gruvbox Light", "#FBF1C7", "#3C3836", "#3C3836", listOf(
            "#FBF1C7", "#CC241D", "#98971A", "#D79921", "#458588", "#B16286", "#689D6A", "#7C6F64",
            "#928374", "#9D0006", "#79740E", "#B57614", "#076678", "#8F3F71", "#427B58", "#3C3836")),
        TermTheme("tango", "Ubuntu / Tango", "#300A24", "#EEEEEC", "#EEEEEC", listOf(
            "#2E3436", "#CC0000", "#4E9A06", "#C4A000", "#3465A4", "#75507B", "#06989A", "#D3D7CF",
            "#555753", "#EF2929", "#8AE234", "#FCE94F", "#729FCF", "#AD7FA8", "#34E2E2", "#EEEEEC")),
        TermTheme("amber", "Retro amber", "#1A0F00", "#FFB000", "#FFB000", listOf(
            "#1A0F00", "#CC5500", "#CC8800", "#FFB000", "#B37700", "#CC6600", "#E69900", "#FFCC66",
            "#4D3300", "#FF6600", "#FFAA00", "#FFD24D", "#D99100", "#FF8000", "#FFBB33", "#FFE0A0"))
    )

    /** "custom" is built from the user's colours: background,foreground,cursor,color0..color15 (19 comma-separated #RRGGBB). */
    fun byId(id: String): TermTheme {
        if (id == "custom") {
            val v = Cfg.customTheme.split(",")
            if (v.size == 19 && v.all { runCatching { Color.parseColor(it) }.isSuccess }) {
                return TermTheme("custom", "Custom", v[0], v[1], v[2], v.subList(3, 19))
            }
        }
        return ALL.firstOrNull { it.id == id } ?: ALL[0]
    }

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

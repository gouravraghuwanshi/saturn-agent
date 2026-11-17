package io.github.pimobile.ui

/**
 * A built-in slash command handled by the app (pi's interactive built-ins
 * don't exist in RPC mode, so each maps to RPC, the pimobile-app extension,
 * or a native screen). [args] commands insert "/name " from the palette so the
 * user can type arguments; the others run when picked.
 */
data class Builtin(
    val name: String,
    val description: String,
    val tag: String = "pi",
    val args: Boolean = false,
    val run: (String) -> Unit,
)

fun paletteOf(builtins: List<Builtin>): List<SlashCommand> = slashCommands(
    builtins.map { b -> SlashCommand(b.name, b.description, b.tag, run = if (b.args) null else ({ b.run("") })) }
)

/** Runs [text] if it is one of [builtins] ("/name args"). Returns false otherwise. */
fun dispatchSlash(text: String, builtins: List<Builtin>): Boolean {
    if (!text.startsWith("/")) return false
    val name = text.drop(1).substringBefore(' ')
    val b = builtins.firstOrNull { it.name == name } ?: return false
    b.run(text.drop(1 + name.length).trim())
    return true
}

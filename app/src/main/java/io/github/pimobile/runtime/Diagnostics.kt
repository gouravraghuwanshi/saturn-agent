package io.github.pimobile.runtime

import java.io.File

/** Exercises the bundled toolchain end-to-end, without needing an LLM. */
object Diagnostics {
    private val SCRIPT = """
        echo "bash  ${'$'}BASH_VERSION"
        echo "node  ${'$'}(node --version)"
        git --version
        rg --version | head -1
        curl --version | head -1
        echo "npm   ${'$'}(npm --version)"
        node -e "require('child_process').exec('echo node shell ok', (e,o) => console.log(e ? 'FAIL ' + e.message : o.trim()))"
        curl -sS -o /dev/null -w "https %{http_code}\n" https://registry.npmjs.org/
        cd "${'$'}TMPDIR" && rm -rf diag && git clone -q --depth 1 https://github.com/octocat/Hello-World.git diag && echo "git clone ok"
        rg -q Hello diag && echo "ripgrep ok"
    """.trimIndent()

    /** Blocking; returns the exit code. */
    fun run(rt: PiRuntime, onLine: (String) -> Unit): Int {
        rt.prepare(onLine)
        val pb = ProcessBuilder(File(rt.bin, "bash").path, "-c", SCRIPT).directory(rt.home).redirectErrorStream(true)
        pb.environment().putAll(rt.env())
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine(onLine)
        return p.waitFor()
    }
}

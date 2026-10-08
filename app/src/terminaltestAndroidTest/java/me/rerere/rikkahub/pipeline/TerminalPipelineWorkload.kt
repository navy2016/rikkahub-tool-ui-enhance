package me.rerere.rikkahub.pipeline

/** Identical child for the direct-PTY preflight and full production manager/page tests. */
internal object TerminalPipelineWorkload {
    val script = """
        stty -echo -onlcr
        printf '\033[?25lREADY\r\n'
        while IFS= read -r line; do
          if [ "${'$'}line" = SEED ]; then
            i=0; while [ "${'$'}i" -lt 1100 ]; do printf 'history-%04d 中文\r\n' "${'$'}i"; i=${'$'}((i+1)); done
            printf 'SEED_DONE\r\n'
          else
            printf 'ECHO:%s\r\n' "${'$'}line"
          fi
        done
    """.trimIndent()
    val command = "sh -c '" + script.replace("'", "'\"'\"'") + "'"
    const val READY = "\u001B[?25lREADY\r\n"
}

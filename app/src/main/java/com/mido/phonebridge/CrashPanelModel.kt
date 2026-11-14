package com.mido.phonebridge

/**
 * What the Home "crashed / froze" panel shows. One record at a time, and label, detail and clip
 * name always come from the same record.
 *
 * A crash wins over a stall: it names an exception and a line, a stall is only a stack sampled
 * from outside. The stall stays on disk and shows once the crash record is gone (an upgrade clears
 * both), and it can be dismissed on its own.
 */
internal data class CrashPanelModel(
    val isStall: Boolean,
    val label: String,
    val detail: String,
    val clipName: String,
    val headline: String,
) {
    companion object {
        private val FRAME = Regex("""^\s*at\s+(?:[\w$]+\.)*([\w$]+\.[\w$<>]+)(\(.*\))\s*$""")

        /**
         * The line that names the failure. A bare stack frame is shortened to `Frozen in Class.method (File:line)`, with a space to break at:
         * the full package path has no break points and splits in the middle of a word on a narrow screen.
         */
        internal fun headlineOf(detail: String): String {
            val line = detail.lineSequence()
                .firstOrNull { it.contains("Exception") || it.startsWith("    at ") }?.trim()
                ?: return "no detail recorded"
            val frame = FRAME.matchEntire(line) ?: return line
            return "Frozen in ${frame.groupValues[1]} ${frame.groupValues[2]}"
        }

        fun of(crash: String?, stall: String?): CrashPanelModel? {
            val isStall = crash == null
            val detail = crash ?: stall ?: return null
            val headline = headlineOf(detail)
            return CrashPanelModel(
                isStall = isStall,
                label = if (isStall) "The screen froze" else "The app crashed",
                detail = detail,
                clipName = if (isStall) "STMP stall" else "STMP crash",
                headline = headline,
            )
        }
    }
}

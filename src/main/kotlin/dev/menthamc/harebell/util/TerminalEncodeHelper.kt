package dev.menthamc.harebell.util;

import java.nio.charset.Charset

object TerminalEncodeHelper {
    fun detectAndSetEncoding() {
        val systemEncoding = Charset.defaultCharset().displayName()
        val terminalEncoding = System.getenv("LANG")?.substringBefore('.')?.takeIf { it.isNotBlank() }
        val consoleCharset = runCatching { System.console()?.charset() }.getOrNull()
        val consoleEncoding = consoleCharset?.displayName()

        val isUtf8 = systemEncoding.equals("UTF-8", ignoreCase = true) ||
                terminalEncoding?.contains("UTF-8", ignoreCase = true) == true ||
                consoleEncoding?.contains("UTF-8", ignoreCase = true) == true

        val targetCharset = consoleCharset ?: if (isUtf8) Charsets.UTF_8 else null

        if (targetCharset != null) {
            System.setProperty("file.encoding", targetCharset.name())
            System.setProperty("sun.stdout.encoding", targetCharset.name())
            System.setProperty("sun.stderr.encoding", targetCharset.name())
            System.setOut(java.io.PrintStream(System.out, true, targetCharset))
            System.setErr(java.io.PrintStream(System.err, true, targetCharset))
        }
    }
}

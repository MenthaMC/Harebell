package dev.menthamc.harebell.util;

import java.nio.charset.Charset

object TerminalEncodeHelper {
     fun detectAndSetEncoding() {
        val systemEncoding = Charset.defaultCharset().displayName()
        val terminalEncoding = System.getenv("LANG")?.substringBefore('.')?.takeIf { it.isNotBlank() }

        val isUtf8 = systemEncoding.equals("UTF-8", ignoreCase = true) ||
                terminalEncoding?.contains("UTF-8", ignoreCase = true) == true

        if (isUtf8) {
            System.setProperty("file.encoding", "UTF-8")

            System.setOut(java.io.PrintStream(System.out, true, "UTF-8"))
            System.setErr(java.io.PrintStream(System.err, true, "UTF-8"))
        }
    }
}

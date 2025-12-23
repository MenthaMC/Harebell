package dev.menthamc.harebell

import dev.menthamc.harebell.data.*
import dev.menthamc.harebell.util.TerminalEncodeHelper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

private const val REPO_URL = "https://github.com/MenthaMC/Harebell"
private const val ANSI_RESET = "\u001B[0m"
private val ANSI_ENABLED = System.getenv("NO_COLOR") == null
private val ANSI_REGEX = Regex("\u001B\\[[;?0-9]*[ -/]*[@-~]")

fun main(args: Array<String>) = CliMain.main(args)

object CliMain {
    @JvmStatic
    fun main(args: Array<String>) {
        TerminalEncodeHelper.detectAndSetEncoding()
        val configStore = LauncherConfigStore()
        var config = configStore.load()
        val configSourcePath = configStore.configSourcePath()
        val hasConfigFile = configSourcePath != null
        val hasInstallProp = System.getProperty("installDir")?.isNotBlank() == true
        val language = Language.fromCode(config.language) ?: promptLanguage()
        val branchInput = System.getProperty("branch")
            ?: config.lastSelectedReleaseTag
        val installDir = System.getProperty("installDir")
            ?: config.installDir
        val javaPath = System.getProperty("javaPath")
            ?: config.javaPath
        val mem = System.getProperty("mem")
            ?: config.maxMemory
        val extraJvm = System.getProperty("jvmArgs")
            ?: config.extraJvmArgs
        val jarName = System.getProperty("jarName")
            ?: config.jarName
        val repoOwner = System.getProperty("repoOwner")
            ?: config.repoOwner
        val repoName = System.getProperty("repoName")
            ?: config.repoName

        val repoTarget = if (repoOwner == null || repoName == null) {
            RepoInit(language).init()
        } else {
            RepoTarget(repoOwner, repoName)
        }

        val apiClient = GithubApiClient(repoTarget = repoTarget)
        printIntro(
            repoUrl = REPO_URL,
            installDir = installDir,
            jarName = jarName,
            showDir = hasConfigFile || hasInstallProp,
            language = language
        )
        configSourcePath?.let {
            cliInfo(tr(language, "已找到配置文件: ${it.toAbsolutePath()}", "Configuration file found: ${it.toAbsolutePath()}"))
        }
            ?: cliInfo(tr(language, "未找到配置文件，将使用默认配置并生成 harebell.json", "No configuration file found, will use default configuration and generate harebell.json"))

        if (installDir.isBlank()) {
            cliError(tr(language, "缺少下载目录：请提供 -DinstallDir=目录 或在配置文件/参数中指定", "Missing download directory: Please provide -DinstallDir=directory or specify in config file/parameters"))
            return
        }

        val releases = try {
            cliStep(tr(language, "获取 Release 列表...", "Fetching Release list..."))
            apiClient.listReleases(limit = 50).filterNot { it.draft }
        } catch (e: Exception) {
            cliError(tr(language, "获取 Release 列表失败: ${e.message}", "Failed to fetch Release list: ${e.message}"))
            return
        }

        val normalizedBranch = branchInput?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("latest", ignoreCase = true) }
        val release = normalizedBranch?.let { branch ->
            releases.firstOrNull { it.targetCommitish?.equals(branch, ignoreCase = true) == true }
        } ?: releases.firstOrNull()
        if (release == null) {
            cliError(tr(language, "未找到任何 Release", "No Release found"))
            return
        }
        val releaseTag = release.tagName
        val selectedBranch = release.targetCommitish?.takeIf { it.isNotBlank() } ?: normalizedBranch
        cliInfo(tr(language, "最新版本: $releaseTag", "Latest version: $releaseTag"))

        val asset = chooseJarAsset(release, repoTarget)
            ?: run {
                cliError(tr(language, "该 Release 下没有 jar 资源，请检查 GitHub 页面", "No jar assets found in this Release, please check GitHub page"))
                return
            }

        val targetName = normalizeJarName(jarName, asset.name)
        val target = Paths.get(installDir).resolve(targetName)

        var finalHash = config.jarHash
        var needDownload = true
        if (Files.exists(target) && config.jarHash.isNotBlank()) {
            val currentHash = sha256(target)
            if (currentHash.equals(config.jarHash, ignoreCase = true)) {
                cliInfo(tr(language, "本地 hash 与配置一致，跳过下载: $targetName", "Local hash matches configuration, skipping download: $targetName"))
                needDownload = false
                finalHash = currentHash
            } else {
                cliInfo(tr(language, "本地 hash 与配置不一致，执行更新: $targetName", "Local hash does not match configuration, performing update: $targetName"))
            }
        }

        if (needDownload) {
            val proxyChoice = apiClient.resolveDownloadUrl(asset) { timing ->
                val speedText = timing.bytesPerSec?.let { formatSpeed(it) } ?: "fail"
                cliInfo(tr(language, "测速: ${timing.source} -> $speedText", "Speed test: ${timing.source} -> $speedText"))
            }

            try {
                val timingsText = proxyChoice.timings
                    .sortedWith(compareBy<ProxyTiming> { !it.ok }
                        .thenByDescending { it.bytesPerSec ?: 0 }
                        .thenBy { it.elapsedMs ?: Long.MAX_VALUE })
                    .joinToString(", ") { t ->
                        val speed = t.bytesPerSec?.let { formatSpeed(it) } ?: "fail"
                        "${t.source}=$speed"
                    }
                cliInfo(tr(language, "测速: $timingsText", "Speed test: $timingsText"))
                cliStep(tr(language, "下载: $targetName (源文件: ${asset.name})", "Downloading: $targetName (source file: ${asset.name})"))
                proxyChoice.proxyHost?.let {
                    cliInfo(tr(language, "使用下载源: ${proxyChoice.source} -> $it", "Using download source: ${proxyChoice.source} -> $it"))
                }
                apiClient.downloadAsset(
                    asset = asset,
                    target = target,
                    downloadUrl = proxyChoice.url,
                    onProgress = { downloaded, total, _ ->
                        val totalText = total?.let { "/ ${formatBytes(it)}" } ?: ""
                        val pct = total?.let { (downloaded * 100 / it).coerceIn(0, 100) }
                        val pctText = pct?.let { " ($it%)" } ?: ""
                        cliProgress(tr(language, "下载进度: ${formatBytes(downloaded)}$totalText$pctText", "Download progress: ${formatBytes(downloaded)}$totalText$pctText"))
                    }
                )
                cliOk(tr(language, "下载完成: $targetName", "Download completed: $targetName"))
                finalHash = sha256(target)
            } catch (e: Exception) {
                cliError(tr(language, "下载失败: ${e.message}", "Download failed: ${e.message}"))
                return
            }
        }

        config = config.copy(
            installDir = installDir,
            javaPath = javaPath,
            maxMemory = mem,
            jarName = targetName,
            jarHash = finalHash ?: "",
            language = language.code,
            lastSelectedReleaseTag = selectedBranch,
            repoOwner = repoTarget.owner,
            repoName = repoTarget.repo
        )
        configStore.save(config)

        val javaCmd = javaPath.ifBlank { "java" }
        val argsList = mutableListOf<String>()
        argsList += javaCmd
        val memValue = config.maxMemory.trim()
        if (memValue.isNotEmpty()) {
            argsList += "-Xms$memValue"
            argsList += "-Xmx$memValue"
        }
        argsList += extraJvm.split(Regex("\\s+")).filter { it.isNotBlank() }
        argsList += "-jar"
        argsList += target.toAbsolutePath().toString()
        argsList += config.serverArgs.split(Regex("\\s+")).filter { it.isNotBlank() }

        cliStep(tr(language, "启动: ${argsList.joinToString(" ")}", "Launching: ${argsList.joinToString(" ")}"))
        try {
            val pb = ProcessBuilder(argsList)
                .directory(Paths.get(installDir).toFile())
                .inheritIO()
            val proc = pb.start()
            val exit = proc.waitFor()
            cliInfo(tr(language, "进程退出，代码=$exit", "Process exited with code=$exit"))
        } catch (e: Exception) {
            cliError(tr(language, "启动失败: ${e.message}", "Launch failed: ${e.message}"))
        }
    }
}

@Suppress("UNUSED_PARAMETER")
private fun printIntro(repoUrl: String, installDir: String, jarName: String, showDir: Boolean, language: Language) {
    val palette = if (ANSI_ENABLED) {
        listOf(
            "\u001B[38;5;45m",
            "\u001B[38;5;81m",
            "\u001B[38;5;117m",
            "\u001B[38;5;153m",
            "\u001B[38;5;189m",
            "\u001B[38;5;219m"
        )
    } else {
        listOf("")
    }
    val letters = listOf(
        arrayOf("██╗  ██╗", "██║  ██║", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf(" █████╗ ", "██╔══██╗", "███████║", "██╔══██║", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf("██████╗ ", "██╔══██╗", "██████╔╝", "██╔══██╗", "██║  ██║", "╚═╝  ╚═╝"),
        arrayOf("███████╗", "██╔════╝", "█████╗  ", "██╔══╝  ", "███████╗", "╚══════╝"),
        arrayOf("██████╗ ", "██╔══██╗", "██████╔╝", "██╔══██╗", "██████╔╝", "╚═════╝ "),
        arrayOf("███████╗", "██╔════╝", "█████╗  ", "██╔══╝  ", "███████╗", "╚══════╝"),
        arrayOf("██╗     ", "██║     ", "██║     ", "██║     ", "███████╗", "╚══════╝"),
        arrayOf("██╗     ", "██║     ", "██║     ", "██║     ", "███████╗", "╚══════╝")
    )
    val rows = letters.first().size
    val spacing = "  "
    for (row in 0 until rows) {
        val line = buildString {
            letters.forEachIndexed { idx, letter ->
                val color = palette[idx % palette.size]
                append(colorize(letter[row], color))
                if (idx != letters.lastIndex) append(spacing)
            }
        }
        println(line)
    }

    println()
    val infoLines = mutableListOf<String>()
    infoLines += accent(tr(language, "Harebell 更新程序已准备就绪", "Harebell Update Program Ready"), "\u001B[38;5;183m")
    infoLines += tr(language, "了解更多: $repoUrl", "Learn more: $repoUrl")
    printBox(infoLines)
}

private fun promptLanguage(): Language {
    while (true) {
        println("请选择语言 / Please select a language:")
        println("1. 中文")
        println("2. English")
        print("请输入选项编号 / Please enter the option number: ")
        val raw = readlnOrNull()?.trim()
        when (raw) {
            "1" -> return Language.ZH_CN
            "2" -> return Language.EN
        }
        Language.fromCode(raw)?.let { return it }
        println("无效的选择 / Invalid selection")
    }
}

private fun printBox(lines: List<String>) {
    if (lines.isEmpty()) return
    val contentWidth = lines.maxOf { visibleLength(it) }
    val innerWidth = contentWidth + 2
    val borderColor = if (ANSI_ENABLED) "\u001B[38;5;105m" else ""
    val reset = if (ANSI_ENABLED) ANSI_RESET else ""
    val horizontal = "─".repeat(innerWidth)
    println(borderColor + "┌$horizontal┐" + reset)
    lines.forEach { line ->
        val pad = contentWidth - visibleLength(line)
        println(borderColor + "│ " + reset + line + " ".repeat(pad) + borderColor + " │" + reset)
    }
    println(borderColor + "└$horizontal┘" + reset)
}

private fun visibleLength(text: String): Int {
    val cleaned = ANSI_REGEX.replace(text, "")
    return cleaned.sumOf { chDisplayWidth(it) }
}


private fun chDisplayWidth(ch: Char): Int = when {
    ch.code in 0x3400..0x4DBF -> 2 // CJK Extension A
    ch.code in 0x4E00..0x9FFF -> 2 // CJK Unified
    ch.code in 0x3040..0x30FF -> 2 // Hiragana/Katakana
    ch.code in 0x3000..0x303F -> 2 // CJK punctuation
    ch.code in 0xFF00..0xFFEF -> 2 // Fullwidth forms
    Character.getType(ch) == Character.NON_SPACING_MARK.toInt() -> 0
    else -> 1
}

private fun accent(text: String, color: String = "\u001B[38;5;111m"): String =
    colorize(text, color)

private fun colorize(text: String, color: String): String =
    if (ANSI_ENABLED && color.isNotEmpty()) "$color$text$ANSI_RESET" else text

private fun chooseJarAsset(release: GithubRelease, repoTarget: RepoTarget): GithubAsset? {
    val jars = release.assets.filter { it.name.endsWith(".jar", ignoreCase = true) }
    val preferred = jars.firstOrNull {
        val n = it.name.lowercase()
        "paperclip" in n || "server" in n || repoTarget.repo.lowercase() in n || repoTarget.owner.lowercase() in n
    }
    return preferred ?: jars.firstOrNull()
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val fmt = if (unitIndex == 0 || value >= 100) "%.0f" else "%.1f"
    return fmt.format(value) + " " + units[unitIndex]
}

private fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val units = listOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSec.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    val fmt = if (idx == 0 || value >= 100) "%.0f" else "%.1f"
    return fmt.format(value) + " " + units[idx]
}

private fun normalizeJarName(desired: String?, assetName: String): String {
    val clean = desired?.trim().orEmpty()
    if (clean.isEmpty()) return assetName
    val assetExt = assetName.substringAfterLast('.', "")
    return if (assetExt.isNotEmpty() && !clean.contains('.')) {
        "$clean.$assetExt"
    } else clean
}

private fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buf = ByteArray(8192)
        while (true) {
            val read = input.read(buf)
            if (read <= 0) break
            digest.update(buf, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun cliStep(msg: String) = println("[*] $msg")
private fun cliInfo(msg: String) = println("[>] $msg")
private fun cliOk(msg: String) = println("[✓] $msg")
private fun cliError(msg: String) = println("[!] $msg")

@Volatile
private var lastProgress: String? = null
private fun cliProgress(msg: String) {
    if (msg == lastProgress) return
    lastProgress = msg
    println(msg)
}

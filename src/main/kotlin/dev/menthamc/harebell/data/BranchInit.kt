package dev.menthamc.harebell.data

import dev.menthamc.harebell.Language
import dev.menthamc.harebell.tr
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BranchInit(private val language: Language, private val repoTarget: RepoTarget) {
    private val httpClient = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private var allBranches: List<BranchInfo>? = null
    private var defaultBranchName: String? = null

    private fun msg(zh: String, en: String) = tr(language, zh, en)

    fun init(): String {
        println(msg("正在获取分支列表...", "Fetching branch list..."))
        val branches = getAllBranches()
        if (branches.isEmpty()) {
            println(msg("无法获取分支列表或仓库没有分支", "Unable to fetch branch list or repository has no branches"))
            return "unknown"
        }

        val defaultBranch = getDefaultBranchName()
        var currentPage = 0
        val pageSize = 9

        while (true) {
            val totalPages = Math.ceil(branches.size.toDouble() / pageSize).toInt()

            displayPage(branches, currentPage, pageSize, defaultBranch, totalPages)

            print(
                msg(
                    "请选择分支编号，输入 'n' 下一页，'p' 上一页，直接回车选择默认分支 [$defaultBranch]: ",
                    "Select branch number, 'n' for next page, 'p' for previous page, press Enter for default [$defaultBranch]: "
                )
            )

            val input = readlnOrNull()?.trim()

            when {
                input.isNullOrEmpty() -> return defaultBranch
                input.equals("n", ignoreCase = true) -> {
                    if (currentPage < totalPages - 1) currentPage++ else {
                        println(msg("已经是最后一页", "Already on the last page"))
                    }
                }

                input.equals("p", ignoreCase = true) -> {
                    if (currentPage > 0) currentPage-- else {
                        println(msg("已经是第一页", "Already on the first page"))
                    }
                }

                input.toIntOrNull() != null -> {
                    val pageNum = input.toInt()
                    val startIndex = currentPage * pageSize
                    val endIndex = Math.min(startIndex + pageSize, branches.size)
                    val pageBranches = branches.subList(startIndex, endIndex)

                    val branchIndex = pageNum - 1
                    if (branchIndex in pageBranches.indices) {
                        return pageBranches[branchIndex].name
                    } else {
                        println(msg("无效的分支编号", "Invalid branch number"))
                    }
                }

                else -> println(msg("无效输入", "Invalid input"))
            }
        }
    }

    private fun getAllBranches(): List<BranchInfo> {
        if (allBranches != null) {
            return allBranches!!
        }

        val branches = fetchBranches()
        allBranches = branches
        return branches
    }

    private fun getDefaultBranchName(): String {
        if (defaultBranchName != null) {
            return defaultBranchName!!
        }

        try {
            val repoApiUrl = "https://api.github.com/repos/${repoTarget.owner}/${repoTarget.repo}"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(repoApiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val responseBody = response.body()
                val repoInfo = json.decodeFromString<RepoInfo>(responseBody)
                defaultBranchName = repoInfo.defaultBranch
                return defaultBranchName!!
            } else {
                println(
                    msg(
                        "获取仓库信息失败: HTTP ${response.statusCode()}",
                        "Failed to fetch repository info: HTTP ${response.statusCode()}"
                    )
                )
                val fallbackBranch = findFallbackDefaultBranch(getAllBranches())
                defaultBranchName = fallbackBranch
                return fallbackBranch
            }
        } catch (e: Exception) {
            println(msg("获取默认分支时发生错误: ${e.message}", "Error fetching default branch: ${e.message}"))
            val fallbackBranch = findFallbackDefaultBranch(getAllBranches())
            defaultBranchName = fallbackBranch
            return fallbackBranch
        }
    }

    private fun findFallbackDefaultBranch(branches: List<BranchInfo>): String {
        return branches.find { it.name.equals("main", ignoreCase = true) }?.name
            ?: branches.find { it.name.equals("master", ignoreCase = true) }?.name
            ?: branches.firstOrNull()?.name
            ?: "unknown"
    }

    private fun displayPage(
        branches: List<BranchInfo>,
        currentPage: Int,
        pageSize: Int,
        defaultBranch: String,
        totalPages: Int
    ) {
        println(
            msg(
                "\n=== 分支列表 - 第 ${currentPage + 1} 页 (共 $totalPages 页) ===",
                "\n=== Branch List - Page ${currentPage + 1} of $totalPages ==="
            )
        )

        if (currentPage == 0) {
            val defaultBranchInfo = branches.find { it.name == defaultBranch }
            val otherBranches = branches.filter { it.name != defaultBranch }

            if (defaultBranchInfo != null) {
                println("1. ${defaultBranchInfo.name} (默认分支)")
                val remainingBranches = otherBranches.take(pageSize - 1)
                remainingBranches.forEachIndexed { index, branch ->
                    println("${index + 2}. ${branch.name}")
                }
            } else {
                val pageBranches = otherBranches.take(pageSize)
                pageBranches.forEachIndexed { index, branch ->
                    println("${index + 1}. ${branch.name}")
                }
            }
        } else {
            val otherBranches = branches.filter { it.name != defaultBranch }
            val startIndex = currentPage * pageSize - 1
            val endIndex = minOf(startIndex + pageSize, otherBranches.size)

            if (startIndex < otherBranches.size) {
                val pageBranches = otherBranches.subList(startIndex, endIndex)
                pageBranches.forEachIndexed { index, branch ->
                    println("${index + 1}. ${branch.name}")
                }
            }
        }
    }

    private fun fetchBranches(): List<BranchInfo> {
        try {
            val apiUrl = "https://api.github.com/repos/${repoTarget.owner}/${repoTarget.repo}/branches"
            val request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/vnd.github.v3+json")
                .GET()
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val responseBody = response.body()
                val branches = json.decodeFromString<List<BranchInfo>>(responseBody)
                return branches
            } else {
                println(
                    msg(
                        "获取分支失败: HTTP ${response.statusCode()}",
                        "Failed to fetch branches: HTTP ${response.statusCode()}"
                    )
                )
                return emptyList()
            }
        } catch (e: Exception) {
            println(msg("获取分支时发生错误: ${e.message}", "Error fetching branches: ${e.message}"))
            return emptyList()
        }
    }

    @kotlinx.serialization.Serializable
    data class BranchInfo(
        val name: String,
        val commit: CommitInfo? = null
    )

    @kotlinx.serialization.Serializable
    data class CommitInfo(
        val sha: String? = null,
        val url: String? = null
    )

    @kotlinx.serialization.Serializable
    data class RepoInfo(
        val default_branch: String
    ) {
        val defaultBranch: String
            get() = default_branch
    }
}

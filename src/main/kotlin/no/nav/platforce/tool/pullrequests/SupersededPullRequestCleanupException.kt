package no.nav.platforce.tool.pullrequests

class SupersededPullRequestCleanupException(
    val pullRequestUrl: String,
    val closedUrls: List<String>,
    cause: Exception,
) : IllegalStateException(
        buildString {
            appendLine("Replacement pull request created: $pullRequestUrl")
            appendLine("Superseded pull request cleanup is unfinished: ${cause.message}")
            if (closedUrls.isNotEmpty()) appendLine("Confirmed closed:\n${closedUrls.joinToString("\n")}")
            append("Review existing PRs before retrying; the replacement exists and some older PRs may already have been closed.")
        },
        cause,
    )

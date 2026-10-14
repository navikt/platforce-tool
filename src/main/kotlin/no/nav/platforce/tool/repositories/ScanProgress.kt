package no.nav.platforce.tool.repositories

data class ScanProgress(
    val total: Int = 0,
    val done: Int = 0,
    val running: Boolean = false,
    val error: String? = null,
)

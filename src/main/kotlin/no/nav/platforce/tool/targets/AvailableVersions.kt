package no.nav.platforce.tool.targets

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.maven.artifact.versioning.ComparableVersion
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

data class AvailableVersion(
    val version: String,
    val prerelease: Boolean,
    val majorUpgrade: Boolean,
)

data class AvailableVersions(
    val key: String,
    val currentVersion: String,
    val versions: List<AvailableVersion>,
)

class AvailableVersionsService(
    httpClient: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class CacheKey(
        val username: String,
        val coordinate: String,
    )

    private data class CachedVersions(
        val expiresAt: Long,
        val versions: List<String>,
    )

    private val client =
        httpClient
            .newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val cache = LinkedHashMap<CacheKey, CachedVersions>()
    private val repositories = listOf("https://repo.maven.apache.org/maven2", "https://packages.confluent.io/maven")
    private val coordinatePattern = Regex("""[A-Za-z0-9_][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_][A-Za-z0-9_-]*)*:[A-Za-z0-9_][A-Za-z0-9_.-]*""")
    private val versionPattern = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,199}""")
    private val prereleasePattern =
        Regex("""(?i)(?:^|[.\-+_])(?:alpha|beta|rc|cr|m|milestone|snapshot|dev|ea|eap|preview|a|b)\d*(?:$|[.\-+_])""")

    fun available(
        username: String,
        key: String,
        currentVersion: String,
    ): AvailableVersions {
        require(key.length <= 300 && coordinatePattern.matches(key)) { "Use a Maven coordinate in group:artifact format" }
        require(versionPattern.matches(currentVersion) && !currentVersion.endsWith('+') && !currentVersion.startsWith("latest.")) {
            "Use a concrete current version"
        }
        val cacheKey = CacheKey(username, key)
        val cached = synchronized(cache) { cache[cacheKey]?.takeIf { now() < it.expiresAt } }
        val versions =
            cached?.versions ?: loadVersions(key).also { loaded ->
                synchronized(cache) {
                    cache.entries.removeIf { now() >= it.value.expiresAt }
                    cache[cacheKey] = CachedVersions(now() + TimeUnit.MINUTES.toMillis(5), loaded)
                    while (cache.size > 256) cache.remove(cache.keys.first())
                }
            }
        val current = ComparableVersion(currentVersion)
        val currentMajor = major(currentVersion)
        return AvailableVersions(
            key,
            currentVersion,
            versions
                .filter { ComparableVersion(it) > current }
                .sortedWith(compareByDescending<String> { ComparableVersion(it) }.thenByDescending { it })
                .map {
                    AvailableVersion(
                        it,
                        prereleasePattern.containsMatchIn(it),
                        currentMajor != null && major(it) != null && major(it) != currentMajor,
                    )
                },
        )
    }

    private fun major(version: String): String? =
        Regex("""^\d+""")
            .find(version)
            ?.value
            ?.trimStart('0')
            ?.ifEmpty { "0" }

    private fun loadVersions(key: String): List<String> {
        val (group, artifact) = key.split(':')
        for (repository in repositories) {
            val url = repository.toHttpUrl().newBuilder()
            group.split('.').forEach(url::addPathSegment)
            url.addPathSegment(artifact).addPathSegment("maven-metadata.xml")
            client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
                if (response.code == 404) return@use
                if (!response.isSuccessful) throw IOException("Version lookup failed at ${url.build().host}: HTTP ${response.code}")
                val bytes = response.body.byteStream().readNBytes(2 * 1024 * 1024 + 1)
                if (bytes.size > 2 * 1024 * 1024) throw IOException("Version metadata is too large")
                return parseVersions(bytes)
            }
        }
        throw IOException("No published Maven metadata found for $key in Maven Central or Confluent")
    }

    private fun parseVersions(bytes: ByteArray): List<String> {
        val factory =
            DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
        val document =
            try {
                factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            } catch (failure: SAXException) {
                throw IOException("Invalid Maven version metadata", failure)
            }
        if (document.documentElement.tagName != "metadata") throw IOException("Invalid Maven version metadata")
        val nodes =
            XPathFactory
                .newInstance()
                .newXPath()
                .evaluate("/metadata/versioning/versions/version", document, XPathConstants.NODESET) as NodeList
        return (0 until nodes.length)
            .map { nodes.item(it).textContent.trim() }
            .filter { versionPattern.matches(it) }
            .distinct()
    }
}

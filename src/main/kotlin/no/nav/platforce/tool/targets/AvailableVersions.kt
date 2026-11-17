package no.nav.platforce.tool.targets

import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import no.nav.platforce.tool.node.compareNodeVersions
import no.nav.platforce.tool.node.nodeTargetVersion
import no.nav.platforce.tool.node.validNodePackage
import no.nav.platforce.tool.node.validNodeVersion
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.maven.artifact.versioning.ComparableVersion
import org.w3c.dom.NodeList
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

data class AvailableVersion(
    val version: String,
    val prerelease: Boolean,
    val majorUpgrade: Boolean,
    val releaseDate: String? = null,
    val young: Boolean = false,
    val releaseDateError: String? = null,
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
        val metadata: VersionMetadata,
    )

    private data class VersionMetadata(
        val artifactUrl: HttpUrl,
        val versions: List<String>,
        val nodeDates: Map<String, ReleaseDate> = emptyMap(),
    )

    private data class ReleaseDate(
        val timestamp: Long?,
        val error: String? = null,
    )

    private data class CachedReleaseDate(
        val expiresAt: Long,
        val date: ReleaseDate,
    )

    private val client =
        httpClient
            .newBuilder()
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    private val cache = LinkedHashMap<CacheKey, CachedVersions>()
    private val releaseDates = LinkedHashMap<CacheKey, CachedReleaseDate>()
    private val dateDispatcher = Dispatchers.IO.limitedParallelism(8)
    private val log = KotlinLogging.logger {}
    private val repositories = listOf("https://repo.maven.apache.org/maven2", "https://packages.confluent.io/maven")
    private val coordinatePattern = Regex("""[A-Za-z0-9_][A-Za-z0-9_-]*(?:\.[A-Za-z0-9_][A-Za-z0-9_-]*)*:[A-Za-z0-9_][A-Za-z0-9_.-]*""")
    private val versionPattern = Regex("""[A-Za-z0-9][A-Za-z0-9._+-]{0,199}""")
    private val prereleasePattern =
        Regex("""(?i)(?:^|[.\-+_])(?:alpha|beta|rc|cr|m|milestone|snapshot|dev|ea|eap|preview|a|b)\d*(?:$|[.\-+_])""")

    fun available(
        username: String,
        key: String,
        currentVersion: String,
        kind: String = "dependency",
    ): AvailableVersions {
        require(kind in setOf("dependency", "platform_bom", "plugin", "node")) { "Unsupported target kind" }
        val node = kind == "node"
        val currentValue = if (node) nodeTargetVersion(currentVersion) else currentVersion
        val coordinate = if (kind == "plugin") "$key:$key.gradle.plugin" else key
        require(if (node) validNodePackage(key) else key.length <= 300 && coordinatePattern.matches(coordinate)) {
            "Use a valid ${if (node) "public npm package name" else "Maven coordinate or plugin ID"}"
        }
        require(
            if (node) {
                currentValue.length <= 200 && validNodeVersion(currentValue)
            } else {
                versionPattern.matches(currentValue) && !currentValue.endsWith('+') && !currentValue.startsWith("latest.")
            },
        ) {
            "Use a concrete current version"
        }
        val cacheKey = CacheKey(username, "$kind:$coordinate")
        val cached = synchronized(cache) { cache[cacheKey]?.takeIf { now() < it.expiresAt } }
        val metadata =
            cached?.metadata ?: (if (node) loadNodeVersions(key) else loadVersions(coordinate, kind == "plugin")).also { loaded ->
                synchronized(cache) {
                    cache.entries.removeIf { now() >= it.value.expiresAt }
                    cache[cacheKey] = CachedVersions(now() + TimeUnit.MINUTES.toMillis(5), loaded)
                    while (cache.size > 256) cache.remove(cache.keys.first())
                }
            }
        val ordering = if (node) Comparator(::compareNodeVersions) else compareBy<String> { ComparableVersion(it) }
        val currentMajor = major(currentValue)
        val candidates =
            metadata.versions
                .filter { ordering.compare(it, currentValue) > 0 }
                .sortedWith(ordering.reversed().thenByDescending { it })
        val versions =
            runBlocking {
                candidates
                    .map { version ->
                        async(dateDispatcher) {
                            val date =
                                if (node) {
                                    metadata.nodeDates.getValue(version)
                                } else {
                                    releaseDate(username, metadata.artifactUrl, coordinate.substringAfter(':'), version)
                                }
                            AvailableVersion(
                                version,
                                if (node) '-' in version.substringBefore('+') else prereleasePattern.containsMatchIn(version),
                                currentMajor != null && major(version) != null && major(version) != currentMajor,
                                date.timestamp?.let { Instant.ofEpochMilli(it).toString() },
                                date.timestamp?.let { it > now() - TimeUnit.DAYS.toMillis(7) } ?: false,
                                date.error,
                            )
                        }
                    }.awaitAll()
            }
        return AvailableVersions(
            key,
            currentValue,
            versions,
        )
    }

    private fun loadNodeVersions(key: String): VersionMetadata {
        val url = "https://registry.npmjs.org".toHttpUrl().newBuilder().addPathSegment(key).build()
        val document =
            client
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .build(),
                ).execute()
                .use { response ->
                    if (!response.isSuccessful) throw IOException("Version lookup failed at registry.npmjs.org: HTTP ${response.code}")
                    val bytes = response.body.byteStream().readNBytes(16 * 1024 * 1024 + 1)
                    if (bytes.size > 16 * 1024 * 1024) throw IOException("npm version metadata is too large")
                    try {
                        JsonParser.parseString(bytes.toString(Charsets.UTF_8))
                    } catch (failure: JsonParseException) {
                        throw IOException("Invalid npm version metadata", failure)
                    }
                }
        if (!document.isJsonObject) throw IOException("Invalid npm version metadata")
        val versions = document.asJsonObject.get("versions")
        if (versions?.isJsonObject != true) throw IOException("Missing npm versions metadata")
        val dates =
            document.asJsonObject
                .get("time")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
        val published = versions.asJsonObject.keySet().filter { it.length <= 200 && validNodeVersion(it) }
        val releaseDates =
            published.associateWith { version ->
                if (!versions.asJsonObject.get(version).isJsonObject) throw IOException("Invalid npm version metadata for $key@$version")
                val date = dates?.get(version)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                val timestamp =
                    try {
                        date?.let { Instant.parse(it).toEpochMilli() }
                    } catch (failure: DateTimeParseException) {
                        log.warn(failure) { "Invalid npm release date for $key@$version" }
                        null
                    } catch (failure: ArithmeticException) {
                        log.warn(failure) { "npm release date out of range for $key@$version" }
                        null
                    }
                if (timestamp == null) {
                    log.warn { "Release date unavailable for $key@$version" }
                    ReleaseDate(null, "Release date unavailable: missing or invalid npm publication time")
                } else {
                    ReleaseDate(timestamp)
                }
            }
        return VersionMetadata(url, published, releaseDates)
    }

    private fun releaseDate(
        username: String,
        artifactUrl: HttpUrl,
        artifact: String,
        version: String,
    ): ReleaseDate {
        val url =
            artifactUrl
                .newBuilder()
                .addPathSegment(version)
                .addPathSegment("$artifact-$version.pom")
                .build()
        val key = CacheKey(username, url.toString())
        synchronized(releaseDates) { releaseDates[key]?.takeIf { now() < it.expiresAt } }?.let { return it.date }
        val date =
            try {
                client
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .head()
                            .build(),
                    ).execute()
                    .use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val timestamp =
                            response.headers.getDate("Last-Modified")?.time
                                ?: throw IOException("Missing or invalid Last-Modified header")
                        ReleaseDate(timestamp)
                    }
            } catch (failure: IOException) {
                log.warn(failure) { "Release date lookup failed for $artifact:$version at ${url.host}" }
                ReleaseDate(null, "Release date unavailable: ${failure.message}")
            }
        synchronized(releaseDates) {
            releaseDates.entries.removeIf { now() >= it.value.expiresAt }
            releaseDates[key] = CachedReleaseDate(now() + TimeUnit.MINUTES.toMillis(5), date)
            while (releaseDates.size > 4096) releaseDates.remove(releaseDates.keys.first())
        }
        return date
    }

    private fun major(version: String): String? =
        Regex("""^\d+""")
            .find(version)
            ?.value
            ?.trimStart('0')
            ?.ifEmpty { "0" }

    private fun loadVersions(
        key: String,
        plugin: Boolean,
    ): VersionMetadata {
        val (group, artifact) = key.split(':')
        val sources = if (plugin) listOf("https://plugins.gradle.org/m2") else repositories
        for (repository in sources) {
            val url = repository.toHttpUrl().newBuilder()
            group.split('.').forEach(url::addPathSegment)
            val artifactUrl = url.addPathSegment(artifact).build()
            url.addPathSegment("maven-metadata.xml")
            client.newCall(Request.Builder().url(url.build()).build()).execute().use { response ->
                if (response.code == 404) return@use
                if (!response.isSuccessful) throw IOException("Version lookup failed at ${url.build().host}: HTTP ${response.code}")
                val bytes = response.body.byteStream().readNBytes(2 * 1024 * 1024 + 1)
                if (bytes.size > 2 * 1024 * 1024) throw IOException("Version metadata is too large")
                return VersionMetadata(artifactUrl, parseVersions(bytes))
            }
        }
        throw IOException("No published Maven metadata found for $key in ${if (plugin) "Plugin Portal" else "Maven Central or Confluent"}")
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

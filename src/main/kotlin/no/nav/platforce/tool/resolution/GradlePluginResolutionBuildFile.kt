package no.nav.platforce.tool.resolution

import com.google.gson.Gson

/**
 * Resolves marker POMs as ordinary dependencies. No configured plugin is applied,
 * and only resolutionResult metadata is accessed (never artifacts or classpaths).
 */
class GradlePluginResolutionBuildFile {
    fun create(plugins: Map<String, String>): String {
        require(plugins.all { (id, version) -> unsupportedPluginReason(id, version) == null })
        return createModules(plugins.toSortedMap().mapValues { (id, version) -> "$id:$id.gradle.plugin:$version" })
    }

    fun createModules(modules: Map<String, String>): String {
        require(modules.values.all { it.matches(Regex("""[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9][A-Za-z0-9._+-]*""")) })
        val json = Gson().toJson(modules).replace("\\", "\\\\").replace("'", "\\'")
        return """
            plugins { id 'java' }
            repositories {
                mavenCentral()
                maven { url = uri('https://plugins.gradle.org/m2/') }
                maven { url = uri('https://packages.confluent.io/maven/') }
            }
            def configuredPlugins = new groovy.json.JsonSlurper().parseText('$json')
            def pluginConfigurations = [:]
            configuredPlugins.eachWithIndex { entry, index ->
                def configuration = configurations.create("pluginMetadata_" + index)
                dependencies.add(configuration.name, entry.value)
                pluginConfigurations[entry.key] = configuration
            }
            tasks.register('platforceResolve') {
                doLast {
                    def started = System.nanoTime()
                    def timings = []
                    def writeTimings = {
                        file('gradle-configuration-timings.json').text = groovy.json.JsonOutput.toJson([
                            taskElapsedMs: (System.nanoTime() - started) / 1000000.0,
                            executedGradleVersion: gradle.gradleVersion, configurations: timings
                        ])
                    }
                    writeTimings()
                    def unresolved = file('gradle-unresolved-dependencies.txt')
                    unresolved.text = ''
                    def unresolvedNode = { dependency ->
                        def requested = dependency.requested
                        def reason = dependency.failure?.message ?: 'Unknown resolution failure'
                        unresolved << requested.displayName + ': ' + reason + '\n'
                        return [group: requested.group, name: requested.module,
                            version: '<unresolved>', requestedVersion: requested.version,
                            dependencies: [], resolutionError: reason]
                    }
                    def buildNode
                    buildNode = { component, requested, path ->
                        def module = component.moduleVersion
                        def node = [group: module?.group,
                            name: module?.name ?: component.id.displayName,
                            version: module?.version ?: '<unresolved>',
                            requestedVersion: requested?.version, dependencies: []]
                        if (path.contains(component.id.displayName)) return node
                        def nextPath = path + component.id.displayName
                        component.dependencies.each { dependency ->
                            if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                if (!dependency.constraint) {
                                    node.dependencies << buildNode(dependency.selected, dependency.requested, nextPath)
                                }
                            } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                node.dependencies << unresolvedNode(dependency)
                            }
                        }
                        return node
                    }
                    def individual = [:]
                    pluginConfigurations.each { id, configuration ->
                        def configurationStarted = System.nanoTime()
                        println("Resolving plugin metadata: " + id + ':' + configuredPlugins[id])
                        def timing = [configuration: configuration.name, kind: 'plugin',
                            dependency: id, status: 'RUNNING']
                        timings << timing
                        writeTimings()
                        def roots = []
                        try {
                            configuration.incoming.resolutionResult.root.dependencies.each { dependency ->
                                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    roots << buildNode(dependency.selected, dependency.requested, [])
                                } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                    roots << unresolvedNode(dependency)
                                }
                            }
                        } catch (Exception failure) {
                            unresolved << id + ': ' + failure.message + '\n'
                            roots = [[group: id, name: id + '.gradle.plugin', version: '<unresolved>',
                                requestedVersion: configuredPlugins[id], dependencies: [],
                                resolutionError: failure.message ?: failure.class.simpleName]]
                        }
                        individual[id] = roots
                        timing.rootCount = roots.size()
                        timing.durationMs = (System.nanoTime() - configurationStarted) / 1000000.0
                        timing.status = 'READY'
                        writeTimings()
                        println("Finished plugin metadata: " + id + ':' + configuredPlugins[id])
                    }
                    file('resolution.json').text = groovy.json.JsonOutput.toJson([roots: [], individual: individual])
                    writeTimings()
                }
            }
            """.trimIndent()
    }
}

fun unsupportedPluginReason(
    id: String,
    version: String,
): String? =
    when {
        !id.matches(Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*")) -> "Invalid published plugin ID"

        id.startsWith("org.gradle.") || id in corePlugins -> "Gradle core plugins have no independently versioned published marker"

        version.isBlank() -> "A published plugin marker requires an explicit version"

        !version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*")) ||
            version.startsWith("latest.", ignoreCase = true) -> "Only explicit, literal plugin marker versions are supported"

        else -> null
    }

private val corePlugins =
    setOf(
        "java",
        "java-library",
        "java-platform",
        "application",
        "groovy",
        "scala",
        "antlr",
        "base",
        "distribution",
        "war",
        "ear",
        "maven-publish",
        "ivy-publish",
        "signing",
        "checkstyle",
        "pmd",
        "jacoco",
        "idea",
        "eclipse",
        "eclipse-wtp",
        "project-report",
        "build-dashboard",
        "build-init",
        "wrapper",
        "version-catalog",
        "jvm-test-suite",
        "jvm-toolchain-management",
        "test-report-aggregation",
        "jacoco-report-aggregation",
    )

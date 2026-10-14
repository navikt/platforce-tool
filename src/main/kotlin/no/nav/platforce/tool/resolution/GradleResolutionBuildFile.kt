package no.nav.platforce.tool.resolution

import no.nav.platforce.tool.targets.TargetVersionsState

class GradleResolutionBuildFile {
    fun create(state: TargetVersionsState): String =
        buildString {
            appendLine(
                """
                plugins {
                    id 'java'
                }

                repositories {
                    mavenCentral()
                    maven {
                        url = uri("https://packages.confluent.io/maven/")
                    }
                }

                configurations {
                    targetResolution
                }

                dependencies {
                """.trimIndent(),
            )

            state.dependencies
                .toSortedMap()
                .forEach { (key, version) ->
                    requireValidDependencyKey(key)

                    appendLine(
                        """    targetResolution "${escape(key)}:$version"""",
                    )
                }

            appendLine(
                """
                }
                """.trimIndent(),
            )

            state.dependencies
                .toSortedMap()
                .entries
                .forEachIndexed { index, (key, version) ->
                    requireValidDependencyKey(key)

                    val configurationName =
                        "targetResolution_$index"

                    appendLine(
                        """
                        def $configurationName =
                            configurations.create("$configurationName")

                        $configurationName.ext.targetDependencyKey =
                            "${escape(key)}"

                        dependencies.add(
                            "$configurationName",
                            "${escape(key)}:$version"
                        )
                        """.trimIndent(),
                    )
                }

            appendLine(
                """
                import groovy.json.JsonOutput

                tasks.register("platforceResolve") {
                    doLast {
                        def taskStarted = System.nanoTime()
                        def configurationTimings = []
                        def timingReport = file("gradle-configuration-timings.json")
                        def writeTimings = {
                            timingReport.text = JsonOutput.prettyPrint(JsonOutput.toJson([
                                taskElapsedMs: (System.nanoTime() - taskStarted) / 1000000.0,
                                executedGradleVersion: gradle.gradleVersion,
                                configurations: configurationTimings
                            ]))
                        }
                        def unresolvedReport =
                            file("gradle-unresolved-dependencies.txt")
                
                        unresolvedReport.parentFile.mkdirs()
                        unresolvedReport.text = ""
                
                        def reportUnresolved = { dependency, context ->
                            def requested = dependency.requested
                        
                            unresolvedReport << "UNRESOLVED DEPENDENCY\n" +
                                "Context: " + context + "\n" +
                                "Group: " + requested.group + "\n" +
                                "Module: " + requested.module + "\n" +
                                "Version: " + requested.version + "\n" +
                                "Display: " + requested.displayName + "\n" +
                                "Reason: " + (dependency.failure?.message ?: "unknown") + "\n" +
                                "----------------------------------------\n"
                        }
                    
                        def buildNode

                        buildNode = { component, requested, path ->
                            def moduleVersion = component.moduleVersion

                            def node = [
                                group: moduleVersion?.group,
                                name: moduleVersion?.name ?: component.id.displayName,
                                version: moduleVersion?.version,
                                requestedVersion: requested?.version,
                                requested: requested?.displayName,
                                dependencies: []
                            ]

                            def componentId = component.id.displayName

                            if (path.contains(componentId)) {
                                return node
                            }

                            def newPath = path + componentId

                            component.dependencies.each { dependency ->
                                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    node.dependencies << buildNode(
                                        dependency.selected,
                                        dependency.requested,
                                        newPath
                                    )
                                } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                    reportUnresolved(
                                        dependency,
                                        "dependency of ${'$'}{component.id.displayName}"
                                    )
                                    
                                    def unresolvedRequested = dependency.requested
                            
                                    node.dependencies << [
                                        group: unresolvedRequested.group,
                                        name: unresolvedRequested.module,
                                        version: "<unresolved>",
                                        requestedVersion: unresolvedRequested.version,
                                        requested: unresolvedRequested.displayName,
                                        dependencies: [],
                                        unresolved: true
                                    ]
                                }
                            }

                            return node
                        }

                        def resolveConfiguration = { configuration ->
                            def configurationStarted = System.nanoTime()
                            def configurationStatus = "FAILED"
                            def rootCount = 0
                            try {
                            def resolutionResult =
                                configuration.incoming.resolutionResult
                        
                            def roots = []
                        
                            resolutionResult.root.dependencies.each { dependency ->
                                if (dependency instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    roots << buildNode(
                                        dependency.selected,
                                        dependency.requested,
                                        []
                                    )
                                } else if (dependency instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult) {
                                    reportUnresolved(
                                        dependency,
                                        "root dependency in ${'$'}{configuration.name}"
                                    )
                                    
                                    def unresolvedRequested = dependency.requested
                        
                                    roots << [
                                        group: unresolvedRequested.group,
                                        name: unresolvedRequested.module,
                                        version: "<unresolved>",
                                        requestedVersion: unresolvedRequested.version,
                                        requested: unresolvedRequested.displayName,
                                        dependencies: [],
                                        unresolved: true
                                    ]
                                }
                            }
                        
                            rootCount = roots.size()
                            configurationStatus = "READY"
                            return roots
                            } finally {
                                configurationTimings << [
                                    configuration: configuration.name,
                                    kind: configuration.name == "targetResolution" ? "combined" : "individual",
                                    dependency: configuration.name == "targetResolution" ? null : configuration.ext.targetDependencyKey,
                                    durationMs: (System.nanoTime() - configurationStarted) / 1000000.0,
                                    rootCount: rootCount,
                                    status: configurationStatus
                                ]
                                writeTimings()
                            }
                        }

                        def roots =
                            resolveConfiguration(
                                configurations.targetResolution
                            )

                        def individual = [:]

                        configurations
                            .findAll { configuration ->
                                configuration.name.startsWith("targetResolution_")
                            }
                            .each { configuration ->
                                def key =
                                    configuration.ext.targetDependencyKey

                                if (key != null) {
                                    individual[key] =
                                        resolveConfiguration(configuration)
                                }
                            }

                        def result = [
                            roots: roots,
                            individual: individual
                        ]

                        file("${'$'}projectDir/resolution.json").text =
                            JsonOutput.prettyPrint(
                                JsonOutput.toJson(result)
                            )
                        writeTimings()
                    }
                }
                """.trimIndent(),
            )
        }

    private fun requireValidDependencyKey(key: String) {
        require(
            key.matches(
                Regex("""[^:\s]+:[^:\s]+"""),
            ),
        ) {
            "Invalid Maven dependency: $key"
        }
    }

    private fun escape(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
}

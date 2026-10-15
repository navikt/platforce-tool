package no.nav.platforce.tool.resolution

object GradleBomResolutionScript {
    fun create(): String =
        """
        tasks.named('platforceResolve') {
            doLast {
                def bomStarted = System.nanoTime()
                def sequence = 0
                def resolveBomGraph = { bomKey, bomVersion, enforced, dependencyKey, dependencyVersion ->
                    def configuration = configurations.create("bomResolution_" + sequence++)
                    def notation = bomKey + ':' + bomVersion
                    dependencies.add(configuration.name,
                        enforced ? dependencies.enforcedPlatform(notation) : dependencies.platform(notation))
                    dependencies.add(configuration.name, dependencyKey + ':' + dependencyVersion)
                    def started = System.nanoTime()
                    def result = configuration.incoming.resolutionResult
                    def unresolved = result.allDependencies.findAll {
                        it instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult
                    }
                    if (!unresolved.empty) {
                        throw new GradleException("Incomplete BOM resolution for " + notation + ": " +
                            unresolved.collect { it.attempted.displayName + ': ' + it.failure.message }.join('; '))
                    }
                    def visit
                    def visitEdges
                    visitEdges = { edges, path ->
                        edges.findAll { edge ->
                            !edge.constraint &&
                                edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult
                        }.collectMany { edge ->
                            def category = edge.resolvedVariant.attributes.getAttribute(
                                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE)?.name
                            if (category in ['platform', 'enforced-platform']) {
                                def id = edge.selected.id.displayName
                                return path.contains(id) ? [] : visitEdges(edge.selected.dependencies, path + id)
                            }
                            return [visit(edge.selected, edge.requested, path)]
                        }
                    }
                    visit = { component, requested, path ->
                        def version = component.moduleVersion
                        def node = [
                            group: version?.group,
                            name: version?.name ?: component.id.displayName,
                            version: version?.version,
                            requestedVersion: requested?.version,
                            dependencies: []
                        ]
                        def id = component.id.displayName
                        if (!path.contains(id)) {
                            node.dependencies = visitEdges(component.dependencies, path + id)
                        }
                        return node
                    }
                    def roots = visitEdges(result.root.dependencies, [])
                    def timingFile = file('gradle-configuration-timings.json')
                    def timing = new groovy.json.JsonSlurper().parse(timingFile)
                    timing.configurations << [
                        configuration: configuration.name,
                        kind: 'bom-coverage',
                        dependency: dependencyKey,
                        bom: notation,
                        enforcedPlatform: enforced,
                        durationMs: (System.nanoTime() - started) / 1000000.0,
                        rootCount: roots.size(),
                        status: 'READY'
                    ]
                    timingFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(timing))
                    return [roots: roots]
                }
                def resolutionFile = file('resolution.json')
                def report = new groovy.json.JsonSlurper().parse(resolutionFile)
                report.platformBoms = [:]
                configuredPlatformBoms.each { bomKey, bomVersion ->
                    def coverage = []
                    configuredTargetDependencies.each { dependencyKey, dependencyVersion ->
                        [false, true].each { enforced ->
                            coverage << [
                                dependency: dependencyKey,
                                targetVersion: dependencyVersion,
                                enforcedPlatform: enforced,
                                roots: resolveBomGraph(bomKey, bomVersion, enforced, dependencyKey, dependencyVersion).roots
                            ]
                        }
                    }
                    report.platformBoms[bomKey] = [version: bomVersion, managed: [], coverage: coverage]
                }
                resolutionFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(report))
                def timingFile = file('gradle-configuration-timings.json')
                def timing = new groovy.json.JsonSlurper().parse(timingFile)
                timing.taskElapsedMs += (System.nanoTime() - bomStarted) / 1000000.0
                timingFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(timing))
            }
        }
        """.trimIndent()
}

package no.nav.platforce.tool.resolution

object GradleBomResolutionScript {
    fun create(): String =
        """
        def bomMetadata = [:]
        dependencies.components.all { details ->
            details.allVariants {
                withDependencyConstraints { constraints ->
                    def keys = constraints.collect { it.group + ':' + it.name }
                    synchronized (bomMetadata) {
                        def id = details.id.toString()
                        bomMetadata[id] = ((bomMetadata[id] ?: []) + keys).unique()
                    }
                }
            }
        }

        tasks.named('platforceResolve') {
            doLast {
                def bomStarted = System.nanoTime()
                def sequence = 0
                def resolveBomGraph = { bomKey, bomVersion, enforced, dependencyKey, dependencyVersion ->
                    def configuration = configurations.create("bomResolution_" + sequence++)
                    def notation = bomKey + ':' + bomVersion
                    dependencies.add(configuration.name,
                        enforced ? dependencies.enforcedPlatform(notation) : dependencies.platform(notation))
                    if (dependencyKey != null) {
                        def dependency = dependencies.create(
                            dependencyKey + (dependencyVersion == null ? '' : ':' + dependencyVersion))
                        if (dependencyVersion == null) dependency.transitive = false
                        dependencies.add(configuration.name, dependency)
                    }
                    def started = System.nanoTime()
                    def result = configuration.incoming.resolutionResult
                    def unresolved = result.allDependencies.findAll {
                        it instanceof org.gradle.api.artifacts.result.UnresolvedDependencyResult
                    }
                    if (!unresolved.empty) {
                        throw new GradleException("Incomplete BOM resolution for " + notation + ": " +
                            unresolved.collect { it.attempted.displayName + ': ' + it.failure.message }.join('; '))
                    }
                    if (dependencyKey == null && result.allDependencies.any { edge ->
                        edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult &&
                            !edge.constraint &&
                            !(edge.resolvedVariant.attributes.getAttribute(
                                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE)?.name in ['platform', 'enforced-platform'])
                    }) {
                        throw new GradleException("Unsupported BOM with ordinary dependencies: " + notation)
                    }
                    def visit
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
                            component.dependencies.each { edge ->
                                if (!edge.constraint &&
                                    edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult) {
                                    def category = edge.resolvedVariant.attributes.getAttribute(
                                        org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE)?.name
                                    if (!(category in ['platform', 'enforced-platform'])) {
                                        node.dependencies << visit(edge.selected, edge.requested, path + id)
                                    }
                                }
                            }
                        }
                        return node
                    }
                    def roots = result.root.dependencies.findAll { edge ->
                        edge instanceof org.gradle.api.artifacts.result.ResolvedDependencyResult &&
                            edge.selected.moduleVersion?.toString() != notation &&
                            !(edge.resolvedVariant.attributes.getAttribute(
                                org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE)?.name in ['platform', 'enforced-platform'])
                    }.collect { edge -> visit(edge.selected, edge.requested, []) }
                    def keys = result.allComponents.collectMany { component ->
                        synchronized (bomMetadata) {
                            return bomMetadata[component.id.toString()] ?: []
                        }
                    }.unique().sort()
                    def timingFile = file('gradle-configuration-timings.json')
                    def timing = new groovy.json.JsonSlurper().parse(timingFile)
                    timing.configurations << [
                        configuration: configuration.name,
                        kind: dependencyVersion != null ? 'bom-coverage' : dependencyKey != null ? 'bom-managed' : 'bom-catalog',
                        dependency: dependencyKey,
                        bom: notation,
                        enforcedPlatform: enforced,
                        durationMs: (System.nanoTime() - started) / 1000000.0,
                        rootCount: roots.size(),
                        status: 'READY'
                    ]
                    timingFile.text = groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(timing))
                    return [roots: roots, keys: keys]
                }
                def resolutionFile = file('resolution.json')
                def report = new groovy.json.JsonSlurper().parse(resolutionFile)
                report.platformBoms = [:]
                configuredPlatformBoms.each { bomKey, bomVersion ->
                    def metadata = resolveBomGraph(bomKey, bomVersion, false, null, null)
                    def managed = metadata.keys.collect { key ->
                        def graph = resolveBomGraph(bomKey, bomVersion, false, key, null)
                        def node = graph.roots.find { it.group + ':' + it.name == key }
                        if (node == null || node.version == null) {
                            throw new GradleException("No effective managed version for " + key + " in " + bomKey)
                        }
                        return node + [dependencies: []]
                    }
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
                    report.platformBoms[bomKey] = [version: bomVersion, managed: managed, coverage: coverage]
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

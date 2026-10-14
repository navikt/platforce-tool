@file:Suppress("ktlint:standard:filename", "ktlint:standard:property-naming")

package no.nav.platforce.tool

const val env_NAIS_CLUSTER_NAME = "NAIS_CLUSTER_NAME"

const val secret_PLATFORCE_TOOLING_APP_ID = "PLATFORCE_TOOLING_APP_ID"
const val secret_PLATFORCE_TOOLING_PRIVATE_KEY = "PLATFORCE_TOOLING_PRIVATE_KEY"

/**
 * Shortcut for fetching environment variables
 */
fun env(name: String): String = System.getenv(name) ?: throw NullPointerException("Missing env $name")

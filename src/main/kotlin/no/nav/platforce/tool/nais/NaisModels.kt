package no.nav.platforce.tool.nais

data class TeamRepositories(
    val team: String,
    val role: String,
    val repositories: List<String>,
)

data class UserRepositoriesResponse(
    val email: String,
    val teams: List<TeamRepositories>,
)

data class GraphQLRequest(
    val query: String,
    val variables: Map<String, Any?> = emptyMap(),
)

package no.nav.platforce.tool.notes

import no.nav.sf.keytool.db.PostgresDatabase

class RepositoryNotesStore(
    private val userId: String = "default",
    private val team: String? = null,
) {
    fun get(): Map<String, String> = PostgresDatabase.getRepositoryNotes(userId)

    fun save(
        repository: String,
        note: String,
    ) {
        if (note.isBlank()) {
            PostgresDatabase.deleteRepositoryNote(
                userId,
                repository,
            )
            return
        }

        PostgresDatabase.saveRepositoryNote(
            userId = userId,
            team = team,
            repository = repository,
            note = note,
        )
    }
}

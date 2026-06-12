package no.nav.sf.keytool.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import mu.KotlinLogging
import no.nav.platforce.tool.db.REPOSITORY_NOTES
import no.nav.platforce.tool.db.RepositoryNotesTable
import no.nav.platforce.tool.db.TARGET_VERSIONS
import no.nav.platforce.tool.db.TargetVersion
import no.nav.platforce.tool.db.TargetVersionsTable
import no.nav.platforce.tool.db.toTargetVersion
import no.nav.platforce.tool.dependencies.TargetVersions
import no.nav.platforce.tool.env
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

const val NAIS_DB_JDBC_URL = "NAIS_DATABASE_PLATFORCE_TOOL_PLATFORCE_TOOL_JDBC_URL"

object PostgresDatabase {
    private val log = KotlinLogging.logger { }

    private val dbJdbcUrl = env(NAIS_DB_JDBC_URL)

    // Note: exposed Database connect prepares for connections but does not actually open connections
    // That is handled via transaction {} ensuring connections are opened and closed properly
    val database = Database.connect(HikariDataSource(hikariConfig()))

    private fun hikariConfig(): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = dbJdbcUrl // "jdbc:postgresql://localhost:$dbPort/$dbName" // This is where the cloud db proxy is located in the pod
            driverClassName = "org.postgresql.Driver"
            minimumIdle = 1
            maxLifetime = 26000
            maximumPoolSize = 10
            connectionTimeout = 250
            idleTimeout = 10000
            isAutoCommit = false
            // Isolation level that ensure the same snapshot of db during one transaction:
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        }

    fun createTargetVersionsTable(dropFirst: Boolean = false) {
        transaction {
            if (dropFirst) {
                log.info { "Dropping table $TARGET_VERSIONS" }
                val dropStatement =
                    TransactionManager.current().connection.prepareStatement("DROP TABLE $TARGET_VERSIONS", false)
                dropStatement.executeUpdate()
                log.info { "Drop performed" }
            }

            log.info { "Creating table $TARGET_VERSIONS" }
            SchemaUtils.create(TargetVersionsTable)
        }
    }

    fun createRepositoryNotesTable(dropFirst: Boolean = false) {
        transaction {
            if (dropFirst) {
                log.info { "Dropping table $REPOSITORY_NOTES" }
                val dropStatement =
                    TransactionManager.current().connection.prepareStatement("DROP TABLE $REPOSITORY_NOTES", false)
                dropStatement.executeUpdate()
                log.info { "Drop performed" }
            }

            log.info { "Creating table $REPOSITORY_NOTES" }
            SchemaUtils.create(RepositoryNotesTable)
        }
    }

    fun getForUser(userId: String): List<TargetVersion> =
        transaction(database) {
            TargetVersionsTable
                .selectAll()
                .where { TargetVersionsTable.userId eq userId }
                .map { it.toTargetVersion() }
        }

    fun replaceForUser(
        userId: String,
        team: String?,
        versions: List<TargetVersion>,
    ) {
        transaction(database) {
            TargetVersionsTable.deleteWhere {
                TargetVersionsTable.userId eq userId
            }

            versions.forEach { version ->
                TargetVersionsTable.insert {
                    it[TargetVersionsTable.userId] = userId
                    it[TargetVersionsTable.team] = team
                    it[TargetVersionsTable.type] = version.type.name
                    it[TargetVersionsTable.key] = version.key
                    it[TargetVersionsTable.version] = version.version
                    it[TargetVersionsTable.createdAt] = version.createdAt
                    it[TargetVersionsTable.updatedAt] = version.updatedAt
                }
            }
        }
    }

    fun getRepositoryNotes(userId: String): Map<String, String> =
        transaction(database) {
            RepositoryNotesTable
                .selectAll()
                .where {
                    RepositoryNotesTable.userId eq userId
                }.associate {
                    it[RepositoryNotesTable.repository] to
                        it[RepositoryNotesTable.note]
                }
        }

    fun saveRepositoryNote(
        userId: String,
        team: String?,
        repository: String,
        note: String,
    ) {
        transaction(database) {
            RepositoryNotesTable.deleteWhere {
                (RepositoryNotesTable.userId eq userId) and
                    (RepositoryNotesTable.repository eq repository)
            }

            RepositoryNotesTable.insert {
                it[RepositoryNotesTable.userId] = userId
                it[RepositoryNotesTable.team] = team
                it[RepositoryNotesTable.repository] = repository
                it[RepositoryNotesTable.note] = note
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    fun deleteRepositoryNote(
        userId: String,
        repository: String,
    ) {
        transaction(database) {
            RepositoryNotesTable.deleteWhere {
                (RepositoryNotesTable.userId eq userId) and
                    (RepositoryNotesTable.repository eq repository)
            }
        }
    }
}

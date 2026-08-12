package kr.inmc.titleforge.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.badge.Rarity
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.stat.Stats
import org.bukkit.Material
import java.io.File
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import java.util.logging.Logger

/**
 * HikariCP 기반 SQLite / MySQL(MariaDB 드라이버) 저장소.
 *
 * 모든 메서드는 블로킹이며 [kr.inmc.titleforge.util.Sched.async] 안에서만 호출된다.
 */
class SqlStorage(
    private val settings: Settings.StorageSettings,
    private val dataFolder: File,
    private val logger: Logger,
) : Storage {

    private lateinit var dataSource: HikariDataSource

    private val sqlite: Boolean get() = settings.type == Settings.StorageType.SQLITE

    // ── 방언 ────────────────────────────────────────────────────────────
    private val insertIgnore: String get() = if (sqlite) "INSERT OR IGNORE INTO" else "INSERT IGNORE INTO"
    private fun text(length: Int): String = if (sqlite) "TEXT" else "VARCHAR($length)"

    override fun init() {
        val config = HikariConfig().apply {
            poolName = "TitleForge-Pool"
            if (sqlite) {
                if (!dataFolder.exists()) dataFolder.mkdirs()
                driverClassName = "org.sqlite.JDBC"
                jdbcUrl = "jdbc:sqlite:${File(dataFolder, settings.sqliteFile).absolutePath}"
                maximumPoolSize = 1
                addDataSourceProperty("journal_mode", "WAL")
            } else {
                driverClassName = "org.mariadb.jdbc.Driver"
                val suffix = if (settings.properties.isBlank()) "" else "?${settings.properties}"
                jdbcUrl = "jdbc:mariadb://${settings.host}:${settings.port}/${settings.database}$suffix"
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.poolSize
            }
            connectionTimeout = 10_000
            leakDetectionThreshold = 30_000
        }
        dataSource = HikariDataSource(config)
        createSchema()
    }

    override fun close() {
        if (::dataSource.isInitialized && !dataSource.isClosed) dataSource.close()
    }

    private inline fun <T> connection(block: (Connection) -> T): T = dataSource.connection.use(block)

    private fun createSchema() = connection { conn ->
        conn.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tf_badge (
                    type ${text(16)} NOT NULL,
                    id ${text(32)} NOT NULL,
                    display_name ${text(255)} NOT NULL,
                    lore TEXT,
                    rarity ${text(16)} NOT NULL,
                    icon ${text(64)} NOT NULL,
                    permission ${text(128)},
                    hidden INT NOT NULL DEFAULT 0,
                    sort_order INT NOT NULL DEFAULT 0,
                    stats_equip TEXT,
                    stats_own TEXT,
                    PRIMARY KEY (type, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tf_player (
                    uuid ${text(36)} NOT NULL,
                    name ${text(16)} NOT NULL,
                    nickname ${text(64)},
                    nickname_changed_at BIGINT NOT NULL DEFAULT 0,
                    equip_stat ${text(32)},
                    equip_display ${text(32)},
                    equip_seal ${text(32)},
                    updated_at BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid)
                )
                """.trimIndent(),
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS tf_owned (
                    uuid ${text(36)} NOT NULL,
                    type ${text(16)} NOT NULL,
                    badge_id ${text(32)} NOT NULL,
                    obtained_at BIGINT NOT NULL DEFAULT 0,
                    expires_at BIGINT NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, type, badge_id)
                )
                """.trimIndent(),
            )
            // 기존 설치 마이그레이션: 컬럼이 이미 있으면 실패하므로 조용히 무시한다.
            for (sql in MIGRATIONS) {
                runCatching { st.executeUpdate(sql) }
            }
            // MySQL 은 CREATE INDEX IF NOT EXISTS 를 지원하지 않으므로 실패를 무시한다.
            for (sql in INDEX_STATEMENTS) {
                runCatching { st.executeUpdate(if (sqlite) sql else sql.replace("IF NOT EXISTS ", "")) }
            }
        }
    }

    // ── Badge ──────────────────────────────────────────────────────────

    override fun loadBadges(): List<Badge> = connection { conn ->
        conn.prepareStatement("SELECT * FROM tf_badge").use { st ->
            st.executeQuery().use { rs ->
                val result = ArrayList<Badge>()
                while (rs.next()) {
                    readBadge(rs)?.let(result::add)
                }
                result
            }
        }
    }

    private fun readBadge(rs: ResultSet): Badge? {
        val type = BadgeType.of(rs.getString("type")) ?: return null
        return Badge(
            type = type,
            id = rs.getString("id"),
            displayName = rs.getString("display_name") ?: "",
            lore = rs.getString("lore")?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList(),
            rarity = Rarity.of(rs.getString("rarity") ?: "") ?: Rarity.COMMON,
            icon = Material.matchMaterial(rs.getString("icon") ?: "") ?: Material.NAME_TAG,
            permission = rs.getString("permission") ?: "",
            hidden = rs.getInt("hidden") != 0,
            order = rs.getInt("sort_order"),
            equipStats = Stats.deserialize(rs.getString("stats_equip")),
            ownStats = Stats.deserialize(rs.getString("stats_own")),
        ).normalized()
    }

    override fun saveBadge(badge: Badge) {
        val normalized = badge.normalized()
        connection { conn ->
            conn.prepareStatement(
                """
                REPLACE INTO tf_badge
                (type, id, display_name, lore, rarity, icon, permission, hidden, sort_order, stats_equip, stats_own)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { st ->
                st.setString(1, normalized.type.id)
                st.setString(2, normalized.id)
                st.setString(3, normalized.displayName)
                st.setString(4, normalized.lore.joinToString("\n"))
                st.setString(5, normalized.rarity.id)
                st.setString(6, normalized.icon.name)
                st.setString(7, normalized.permission)
                st.setInt(8, if (normalized.hidden) 1 else 0)
                st.setInt(9, normalized.order)
                st.setString(10, Stats.serialize(normalized.equipStats))
                st.setString(11, Stats.serialize(normalized.ownStats))
                st.executeUpdate()
            }
        }
    }

    override fun deleteBadge(type: BadgeType, id: String) = connection { conn ->
        conn.prepareStatement("DELETE FROM tf_badge WHERE type = ? AND id = ?").use { st ->
            st.setString(1, type.id)
            st.setString(2, id)
            st.executeUpdate()
        }
        Unit
    }

    override fun purgeOwnership(type: BadgeType, id: String) = connection { conn ->
        conn.prepareStatement("DELETE FROM tf_owned WHERE type = ? AND badge_id = ?").use { st ->
            st.setString(1, type.id)
            st.setString(2, id)
            st.executeUpdate()
        }
        val column = if (type == BadgeType.SEAL) "equip_seal" else null
        if (column != null) {
            conn.prepareStatement("UPDATE tf_player SET $column = NULL WHERE $column = ?").use { st ->
                st.setString(1, id)
                st.executeUpdate()
            }
        } else {
            for (col in listOf("equip_stat", "equip_display")) {
                conn.prepareStatement("UPDATE tf_player SET $col = NULL WHERE $col = ?").use { st ->
                    st.setString(1, id)
                    st.executeUpdate()
                }
            }
        }
        Unit
    }

    // ── Profile ────────────────────────────────────────────────────────

    override fun loadProfile(uuid: UUID, name: String): PlayerProfile = connection { conn ->
        val profile = PlayerProfile(uuid, name)
        conn.prepareStatement("SELECT * FROM tf_player WHERE uuid = ?").use { st ->
            st.setString(1, uuid.toString())
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    profile.nickname = rs.getString("nickname")?.takeIf { it.isNotBlank() }
                    profile.nicknameChangedAt = rs.getLong("nickname_changed_at")
                    profile.statTitle = rs.getString("equip_stat")?.takeIf { it.isNotBlank() }
                    profile.displayTitle = rs.getString("equip_display")?.takeIf { it.isNotBlank() }
                    profile.seal = rs.getString("equip_seal")?.takeIf { it.isNotBlank() }
                }
            }
        }
        conn.prepareStatement(
            "SELECT type, badge_id, obtained_at, expires_at FROM tf_owned WHERE uuid = ?",
        ).use { st ->
            st.setString(1, uuid.toString())
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    val type = BadgeType.of(rs.getString("type")) ?: continue
                    profile.grant(
                        type = type,
                        id = rs.getString("badge_id"),
                        timestamp = rs.getLong("obtained_at"),
                        expiresAt = rs.getLong("expires_at"),
                    )
                }
            }
        }
        profile.refreshExpiryCache()
        profile.consumeDirty()
        profile
    }

    override fun saveProfile(profile: PlayerProfile) = saveProfiles(listOf(profile))

    override fun saveProfiles(profiles: Collection<PlayerProfile>) {
        if (profiles.isEmpty()) return
        connection { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    """
                    REPLACE INTO tf_player
                    (uuid, name, nickname, nickname_changed_at, equip_stat, equip_display, equip_seal, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { st ->
                    val now = System.currentTimeMillis()
                    for (profile in profiles) {
                        st.setString(1, profile.uuid.toString())
                        st.setString(2, profile.name)
                        st.setString(3, profile.nickname)
                        st.setLong(4, profile.nicknameChangedAt)
                        st.setString(5, profile.statTitle)
                        st.setString(6, profile.displayTitle)
                        st.setString(7, profile.seal)
                        st.setLong(8, now)
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                // 보유 목록은 트랜잭션 안에서 통째로 교체한다. (회수 반영 + N+1 조회 회피)
                conn.prepareStatement("DELETE FROM tf_owned WHERE uuid = ?").use { st ->
                    for (profile in profiles) {
                        st.setString(1, profile.uuid.toString())
                        st.addBatch()
                    }
                    st.executeBatch()
                }
                conn.prepareStatement(
                    "$insertIgnore tf_owned (uuid, type, badge_id, obtained_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                ).use { st ->
                    for (profile in profiles) {
                        for (type in BadgeType.entries) {
                            for (id in profile.owned(type)) {
                                st.setString(1, profile.uuid.toString())
                                st.setString(2, type.id)
                                st.setString(3, id)
                                st.setLong(4, profile.obtainedAt(type, id))
                                st.setLong(5, profile.expiryOf(type, id))
                                st.addBatch()
                            }
                        }
                    }
                    st.executeBatch()
                }
                conn.commit()
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                logger.severe("프로필 저장 실패: ${ex.message}")
                throw ex
            } finally {
                conn.autoCommit = previousAutoCommit
            }
        }
    }

    override fun findUuidByName(name: String): UUID? = connection { conn ->
        conn.prepareStatement("SELECT uuid FROM tf_player WHERE LOWER(name) = LOWER(?) LIMIT 1").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs ->
                if (rs.next()) runCatching { UUID.fromString(rs.getString(1)) }.getOrNull() else null
            }
        }
    }

    override fun isNicknameTaken(nickname: String, except: UUID?): Boolean = connection { conn ->
        conn.prepareStatement("SELECT uuid FROM tf_player WHERE LOWER(nickname) = LOWER(?) LIMIT 5").use { st ->
            st.setString(1, nickname)
            st.executeQuery().use { rs ->
                var taken = false
                while (rs.next()) {
                    val owner = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull()
                    if (owner != null && owner != except) {
                        taken = true
                        break
                    }
                }
                taken
            }
        }
    }

    override fun grantToAll(type: BadgeType, id: String, expiresAt: Long): Int = connection { conn ->
        conn.prepareStatement(
            "$insertIgnore tf_owned (uuid, type, badge_id, obtained_at, expires_at) " +
                "SELECT uuid, ?, ?, ?, ? FROM tf_player",
        ).use { st ->
            st.setString(1, type.id)
            st.setString(2, id)
            st.setLong(3, System.currentTimeMillis())
            st.setLong(4, expiresAt)
            st.executeUpdate()
        }
    }

    // ── 순위 ───────────────────────────────────────────────────────────

    /** 만료되지 않은 보유만 센다. */
    private val liveOwnership = "o.type = ? AND (o.expires_at = 0 OR o.expires_at > ?)"

    override fun topCollectors(type: BadgeType, limit: Int): List<RankEntry> = connection { conn ->
        conn.prepareStatement(
            """
            SELECT o.uuid AS uuid, COALESCE(p.name, '?') AS name, COUNT(*) AS amount
            FROM tf_owned o LEFT JOIN tf_player p ON p.uuid = o.uuid
            WHERE $liveOwnership
            GROUP BY o.uuid, p.name
            ORDER BY amount DESC, name ASC
            LIMIT ?
            """.trimIndent(),
        ).use { st ->
            st.setString(1, type.id)
            st.setLong(2, System.currentTimeMillis())
            st.setInt(3, limit.coerceIn(1, 200))
            st.executeQuery().use { rs ->
                val result = ArrayList<RankEntry>()
                var rank = 0
                while (rs.next()) {
                    rank++
                    val uuid = runCatching { UUID.fromString(rs.getString("uuid")) }.getOrNull() ?: continue
                    result += RankEntry(uuid, rs.getString("name") ?: "?", rs.getInt("amount"), rank)
                }
                result
            }
        }
    }

    override fun rankOf(type: BadgeType, uuid: UUID): RankEntry? = connection { conn ->
        val now = System.currentTimeMillis()
        val count = conn.prepareStatement(
            "SELECT COUNT(*) FROM tf_owned o WHERE o.uuid = ? AND $liveOwnership",
        ).use { st ->
            st.setString(1, uuid.toString())
            st.setString(2, type.id)
            st.setLong(3, now)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
        if (count == 0) {
            null
        } else {
            // 나보다 많이 가진 사람 수 + 1 = 내 순위
            val higher = conn.prepareStatement(
                """
                SELECT COUNT(*) FROM (
                    SELECT o.uuid FROM tf_owned o
                    WHERE $liveOwnership
                    GROUP BY o.uuid HAVING COUNT(*) > ?
                ) ranked
                """.trimIndent(),
            ).use { st ->
                st.setString(1, type.id)
                st.setLong(2, now)
                st.setInt(3, count)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            val name = conn.prepareStatement("SELECT name FROM tf_player WHERE uuid = ?").use { st ->
                st.setString(1, uuid.toString())
                st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
            RankEntry(uuid, name ?: "?", count, higher + 1)
        }
    }

    override fun collectorCount(type: BadgeType): Int = connection { conn ->
        conn.prepareStatement(
            """
            SELECT COUNT(*) FROM (
                SELECT o.uuid FROM tf_owned o WHERE $liveOwnership GROUP BY o.uuid
            ) ranked
            """.trimIndent(),
        ).use { st ->
            st.setString(1, type.id)
            st.setLong(2, System.currentTimeMillis())
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

    private companion object {
        val MIGRATIONS = listOf(
            "ALTER TABLE tf_owned ADD COLUMN expires_at BIGINT NOT NULL DEFAULT 0",
        )

        val INDEX_STATEMENTS = listOf(
            "CREATE INDEX IF NOT EXISTS idx_tf_player_name ON tf_player (name)",
            "CREATE INDEX IF NOT EXISTS idx_tf_player_nickname ON tf_player (nickname)",
            "CREATE INDEX IF NOT EXISTS idx_tf_owned_badge ON tf_owned (type, badge_id)",
            "CREATE INDEX IF NOT EXISTS idx_tf_owned_rank ON tf_owned (type, uuid)",
        )
    }
}

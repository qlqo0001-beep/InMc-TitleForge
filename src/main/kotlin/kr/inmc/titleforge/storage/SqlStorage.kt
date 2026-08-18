package kr.inmc.titleforge.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.badge.Rarity
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.nickname.NicknameNormalizer
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
                // 커넥션이 1개뿐이라 쓰기가 몰리면 즉시 잠금 대기에 들어간다.
                // busy_timeout 이 없으면 SQLITE_BUSY 로 즉시 실패하므로 짧게라도 대기하게 한다.
                addDataSourceProperty("busy_timeout", "5000")
            } else {
                driverClassName = "org.mariadb.jdbc.Driver"
                val suffix = if (settings.properties.isBlank()) "" else "?${settings.properties}"
                jdbcUrl = "jdbc:mariadb://${settings.host}:${settings.port}/${settings.database}$suffix"
                username = settings.username
                password = settings.password
                maximumPoolSize = settings.poolSize
            }
            // 쓰기는 배치로 묶여 있으므로 대기가 길어질 이유가 없다. 오래 잡고 있으면
            // Bukkit 비동기 스케줄러 스레드(다른 플러그인과 공유)를 그만큼 묶어두게 된다.
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
                    nickname_normalized ${text(64)},
                    nickname_reset_notice ${text(64)},
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
                runCatching { st.executeUpdate(sql.replace(NICK_NORM_TYPE, text(64))) }
            }
            // MySQL 은 CREATE INDEX IF NOT EXISTS 를 지원하지 않으므로 실패를 무시한다.
            for (sql in INDEX_STATEMENTS) {
                runCatching { st.executeUpdate(index(sql)) }
            }
        }
        backfillNormalizedNicknames(conn)
        ensureNicknameUnique(conn)
    }

    private fun index(sql: String): String = if (sqlite) sql else sql.replace("IF NOT EXISTS ", "")

    /** 없으면 조용히 넘어간다. DROP INDEX 구문은 방언마다 다르다. */
    private fun dropIndex(conn: Connection, name: String) {
        val sql = if (sqlite) "DROP INDEX IF EXISTS $name" else "DROP INDEX $name ON tf_player"
        runCatching { conn.createStatement().use { it.executeUpdate(sql) } }
    }

    /**
     * 기존 행의 `nickname_normalized` 를 채운다.
     *
     * 정규화는 유니코드 NFKC 를 쓰므로 SQL 로는 재현할 수 없다. 값을 읽어와
     * [NicknameNormalizer] 로 계산한 뒤 다시 써 넣는다. 이미 채워진 행은 건드리지 않으므로
     * 두 번째 기동부터는 조회 한 번으로 끝난다.
     */
    private fun backfillNormalizedNicknames(conn: Connection) {
        val pending = LinkedHashMap<String, String?>()
        conn.prepareStatement(
            "SELECT uuid, nickname FROM tf_player WHERE nickname IS NOT NULL AND nickname_normalized IS NULL",
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) pending[rs.getString(1)] = NicknameNormalizer.normalize(rs.getString(2))
            }
        }
        if (pending.isEmpty()) return

        conn.prepareStatement("UPDATE tf_player SET nickname_normalized = ? WHERE uuid = ?").use { st ->
            for ((uuid, normalized) in pending) {
                st.setString(1, normalized)
                st.setString(2, uuid)
                st.addBatch()
            }
            st.executeBatch()
        }
        logger.info("닉네임 정규화 값을 ${pending.size}건 채웠습니다.")
    }

    /**
     * 닉네임 UNIQUE 제약을 건다.
     *
     * **이미 중복이 있으면 제약 생성이 실패한다.** 이때 임의로 한쪽 닉네임을 지우면 관리자가
     * 모르는 사이에 유저 데이터가 사라지므로(맞춤 지침 7.5-19), 지우지 않고 **중복 목록을
     * 로그로 남긴 뒤 일반 인덱스로 물러난다.** 관리자가 `/it resetnick` 등으로 정리하고 서버를
     * 다시 켜면 그때 제약이 걸린다.
     */
    private fun ensureNicknameUnique(conn: Connection) {
        val duplicates = findDuplicateNicknames(conn)
        if (duplicates.isEmpty()) {
            val created = runCatching { conn.createStatement().use { it.executeUpdate(index(NICKNAME_UNIQUE_INDEX)) } }
            if (created.isFailure) {
                logger.warning("닉네임 UNIQUE 인덱스 생성 실패: ${created.exceptionOrNull()?.message}")
                return
            }
            // 예전에 중복 때문에 물러나 만들어 둔 일반 인덱스가 있으면 이제 필요 없다.
            dropIndex(conn, NICKNAME_PLAIN_NAME)
            return
        }

        logger.severe("닉네임이 중복된 계정이 있어 UNIQUE 제약을 걸지 못했습니다. 아래를 정리한 뒤 서버를 다시 켜주세요:")
        for ((normalized, owners) in duplicates) {
            logger.severe("  '$normalized' → ${owners.joinToString(", ")}")
        }
        logger.severe("  정리 방법: /it resetnick <플레이어>  또는  /it setnick <플레이어> <새 닉네임>")
        runCatching { conn.createStatement().use { it.executeUpdate(index(NICKNAME_PLAIN_INDEX)) } }
    }

    /** @return 정규화 닉네임 → 그 닉네임을 쓰는 계정명 목록 (2개 이상인 것만). */
    private fun findDuplicateNicknames(conn: Connection): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        conn.prepareStatement(
            """
            SELECT nickname_normalized, name FROM tf_player
            WHERE nickname_normalized IS NOT NULL
              AND nickname_normalized IN (
                SELECT nickname_normalized FROM tf_player
                WHERE nickname_normalized IS NOT NULL
                GROUP BY nickname_normalized HAVING COUNT(*) > 1
              )
            ORDER BY nickname_normalized
            """.trimIndent(),
        ).use { st ->
            st.executeQuery().use { rs ->
                while (rs.next()) {
                    result.getOrPut(rs.getString(1)) { ArrayList() }.add(rs.getString(2))
                }
            }
        }
        return result
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

    override fun saveBadge(badge: Badge) = saveBadges(listOf(badge))

    override fun saveBadges(badges: Collection<Badge>) {
        if (badges.isEmpty()) return
        connection { conn ->
            withTransaction(conn) {
                conn.prepareStatement(
                    """
                    REPLACE INTO tf_badge
                    (type, id, display_name, lore, rarity, icon, permission, hidden, sort_order, stats_equip, stats_own)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ).use { st ->
                    for (badge in badges) {
                        val normalized = badge.normalized()
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
                        st.addBatch()
                    }
                    st.executeBatch()
                }
            }
        }
    }

    override fun deleteBadge(type: BadgeType, id: String) = connection { conn ->
        withTransaction(conn) {
            conn.prepareStatement("DELETE FROM tf_badge WHERE type = ? AND id = ?").use { st ->
                st.setString(1, type.id)
                st.setString(2, id)
                st.executeUpdate()
            }
            conn.prepareStatement("DELETE FROM tf_owned WHERE type = ? AND badge_id = ?").use { st ->
                st.setString(1, type.id)
                st.setString(2, id)
                st.executeUpdate()
            }
            for (column in equipColumnsFor(type)) {
                conn.prepareStatement("UPDATE tf_player SET $column = NULL WHERE $column = ?").use { st ->
                    st.setString(1, id)
                    st.executeUpdate()
                }
            }
        }
    }

    /**
     * tf_badge 는 (type,id), tf_owned 는 (uuid,type,badge_id) 가 기본 키다.
     * newId 쪽에 행이 하나라도 남아 있으면 UPDATE 가 키 충돌로 터지므로
     * **트랜잭션 안에서** 두 테이블 모두 비어 있는지 먼저 확인한다.
     * (정의 삭제가 실패해 tf_owned 에만 남은 고아 행이 실제로 존재할 수 있다.)
     */
    override fun renameBadge(type: BadgeType, oldId: String, newId: String) = connection { conn ->
        withTransaction(conn) {
            if (!badgeRowExists(conn, type, oldId)) {
                error("원본 ID 가 존재하지 않습니다: ${type.id}:$oldId")
            }
            if (badgeRowExists(conn, type, newId)) {
                error("이미 사용 중인 ID 입니다: ${type.id}:$newId")
            }
            if (ownershipRowExists(conn, type, newId)) {
                error("정리되지 않은 보유 기록이 남아 있습니다: ${type.id}:$newId")
            }

            conn.prepareStatement("UPDATE tf_badge SET id = ? WHERE type = ? AND id = ?").use { st ->
                st.setString(1, newId)
                st.setString(2, type.id)
                st.setString(3, oldId)
                st.executeUpdate()
            }
            conn.prepareStatement("UPDATE tf_owned SET badge_id = ? WHERE type = ? AND badge_id = ?").use { st ->
                st.setString(1, newId)
                st.setString(2, type.id)
                st.setString(3, oldId)
                st.executeUpdate()
            }
            for (column in equipColumnsFor(type)) {
                conn.prepareStatement("UPDATE tf_player SET $column = ? WHERE $column = ?").use { st ->
                    st.setString(1, newId)
                    st.setString(2, oldId)
                    st.executeUpdate()
                }
            }
        }
    }

    private fun badgeRowExists(conn: Connection, type: BadgeType, id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM tf_badge WHERE type = ? AND id = ? LIMIT 1").use { st ->
            st.setString(1, type.id)
            st.setString(2, id)
            st.executeQuery().use { rs -> rs.next() }
        }

    private fun ownershipRowExists(conn: Connection, type: BadgeType, id: String): Boolean =
        conn.prepareStatement("SELECT 1 FROM tf_owned WHERE type = ? AND badge_id = ? LIMIT 1").use { st ->
            st.setString(1, type.id)
            st.setString(2, id)
            st.executeQuery().use { rs -> rs.next() }
        }

    /**
     * 인장은 equip_seal, 칭호는 equip_stat/equip_display 컬럼을 쓴다.
     *
     * 아래 UPDATE 들은 타입 조건 없이 ID 만 비교한다. 인장 ID 가 칭호 컬럼에 들어가는 경로가
     * 없기에 성립하는 것이므로, 장착 슬롯을 추가할 때 이 전제를 반드시 다시 확인할 것.
     */
    private fun equipColumnsFor(type: BadgeType): List<String> =
        if (type == BadgeType.SEAL) listOf("equip_seal") else listOf("equip_stat", "equip_display")

    /** 트랜잭션으로 묶어 실행하고, 실패하면 롤백 후 다시 던진다. */
    private inline fun withTransaction(conn: Connection, block: () -> Unit) {
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        try {
            block()
            conn.commit()
        } catch (ex: Exception) {
            runCatching { conn.rollback() }
            throw ex
        } finally {
            conn.autoCommit = previousAutoCommit
        }
    }

    // ── Profile ────────────────────────────────────────────────────────

    override fun loadProfile(uuid: UUID, name: String): PlayerProfile = connection { conn ->
        val profile = PlayerProfile(uuid, name)
        conn.prepareStatement("SELECT * FROM tf_player WHERE uuid = ?").use { st ->
            st.setString(1, uuid.toString())
            st.executeQuery().use { rs ->
                if (rs.next()) {
                    profile.nickname = rs.getString("nickname")?.takeIf { it.isNotBlank() }
                    profile.nicknameResetNotice = rs.getString("nickname_reset_notice")?.takeIf { it.isNotBlank() }
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

    /**
     * `tf_player` 행을 UPDATE 시도 후, 없던 행만 INSERT 한다.
     *
     * **`REPLACE INTO` 를 쓰면 안 된다.** SQLite 의 `REPLACE`(=`INSERT OR REPLACE`)와 MySQL 의
     * `REPLACE` 는 PK 뿐 아니라 **모든 UNIQUE 제약 충돌에서 기존 행을 지우고** 새로 넣는다.
     * `nickname_normalized` 에 UNIQUE 가 걸린 뒤로는, 어쩌다 같은 닉네임이 저장되려 할 때
     * 상대방의 `tf_player` 행이 통째로 삭제되어 장착 칭호·쿨타임까지 함께 사라진다.
     *
     * UPDATE→INSERT 로 나누면 충돌 시 조용한 삭제 대신 **예외**가 나고, 호출부가 트랜잭션을
     * 롤백한 뒤 dirty 를 되돌려 다음 주기에 다시 시도한다.
     */
    private fun savePlayerRows(conn: Connection, profiles: Collection<PlayerProfile>) {
        val now = System.currentTimeMillis()

        // 1) 행이 없으면 만들어 둔다. 닉네임 없이 넣으므로 UNIQUE 충돌이 날 수 없고,
        //    이미 있으면 IGNORE 로 조용히 넘어간다. 배치 반환값에 의존하지 않는다
        //    (MariaDB 는 SUCCESS_NO_INFO 를 돌려줄 수 있어 건수로 판단하면 위험하다).
        conn.prepareStatement("$insertIgnore tf_player (uuid, name) VALUES (?, ?)").use { st ->
            for (profile in profiles) {
                st.setString(1, profile.uuid.toString())
                st.setString(2, profile.name)
                st.addBatch()
            }
            st.executeBatch()
        }

        // 2) 실제 값을 채운다. 닉네임이 겹치면 여기서 예외가 나고 호출부가 롤백한다.
        conn.prepareStatement(
            """
            UPDATE tf_player SET
                name = ?, nickname = ?, nickname_normalized = ?, nickname_reset_notice = ?,
                nickname_changed_at = ?,
                equip_stat = ?, equip_display = ?, equip_seal = ?, updated_at = ?
            WHERE uuid = ?
            """.trimIndent(),
        ).use { st ->
            for (profile in profiles) {
                st.setString(1, profile.name)
                st.setString(2, profile.nickname)
                st.setString(3, NicknameNormalizer.normalize(profile.nickname))
                st.setString(4, profile.nicknameResetNotice)
                st.setLong(5, profile.nicknameChangedAt)
                st.setString(6, profile.statTitle)
                st.setString(7, profile.displayTitle)
                st.setString(8, profile.seal)
                st.setLong(9, now)
                st.setString(10, profile.uuid.toString())
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    override fun saveProfiles(profiles: Collection<PlayerProfile>) {
        if (profiles.isEmpty()) return
        connection { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                savePlayerRows(conn, profiles)
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

    /**
     * 이미 알려진 **실제 아이디**와 겹치는지도 함께 본다.
     *
     * 실명 `nine` 인 계정이 있는데 다른 사람이 닉네임 `nine` 을 쓰면, 명령어에서 "nine" 이
     * 누구를 가리키는지 모호해진다. 설정 시점에 막아 두는 편이 낫다.
     *
     * 실명 비교를 SQL 로 해도 되는 이유: 마인크래프트 아이디는 `[a-zA-Z0-9_]{3,16}` 로
     * **순수 ASCII** 라서 [NicknameNormalizer] 의 NFKC·서식 제거가 아무 일도 하지 않는다.
     * 즉 정규화 결과 == 소문자 이므로 `LOWER(name)` 비교와 정확히 같다.
     * (전체 행을 읽어 와 애플리케이션에서 비교할 필요가 없다.)
     */
    override fun isNicknameTaken(nickname: String, except: UUID?): Boolean = connection { conn ->
        // 정규화 컬럼을 그대로 비교한다. LOWER(컬럼) 으로 감싸면 인덱스를 타지 못한다.
        conn.prepareStatement(
            "SELECT uuid FROM tf_player WHERE nickname_normalized = ? OR LOWER(name) = ? LIMIT 5",
        ).use { st ->
            val key = NicknameNormalizer.normalize(nickname)
            st.setString(1, key)
            st.setString(2, key)
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

    override fun findNicknameHolder(normalized: String, except: UUID): Pair<UUID, String>? = connection { conn ->
        conn.prepareStatement(
            "SELECT uuid, nickname FROM tf_player WHERE nickname_normalized = ? AND uuid <> ? LIMIT 1",
        ).use { st ->
            st.setString(1, normalized)
            st.setString(2, except.toString())
            st.executeQuery().use { rs ->
                if (!rs.next()) return@connection null
                val holder = runCatching { UUID.fromString(rs.getString(1)) }.getOrNull()
                    ?: return@connection null
                val nickname = rs.getString(2) ?: return@connection null
                holder to nickname
            }
        }
    }

    override fun resetNicknameCooldown(uuid: UUID) = connection { conn ->
        conn.prepareStatement("UPDATE tf_player SET nickname_changed_at = 0 WHERE uuid = ?").use { st ->
            st.setString(1, uuid.toString())
            st.executeUpdate()
        }
        Unit
    }

    // WHERE 없는 UPDATE 는 MySQL 의 SQL_SAFE_UPDATES 에서 거부된다. 조건을 붙이면
    // 실제로 바뀐 인원 수도 함께 얻을 수 있다.
    override fun resetNicknameCooldownAll(): Int = connection { conn ->
        conn.prepareStatement("UPDATE tf_player SET nickname_changed_at = 0 WHERE nickname_changed_at <> 0")
            .use { st -> st.executeUpdate() }
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
            "ALTER TABLE tf_player ADD COLUMN nickname_normalized $NICK_NORM_TYPE",
            "ALTER TABLE tf_player ADD COLUMN nickname_reset_notice $NICK_NORM_TYPE",
        )

        /** [MIGRATIONS] 안에서 방언별 타입으로 치환되는 자리표시자. */
        const val NICK_NORM_TYPE = "__NICK_NORM_TYPE__"

        val INDEX_STATEMENTS = listOf(
            "CREATE INDEX IF NOT EXISTS idx_tf_player_name ON tf_player (name)",
            "CREATE INDEX IF NOT EXISTS idx_tf_owned_badge ON tf_owned (type, badge_id)",
            "CREATE INDEX IF NOT EXISTS idx_tf_owned_rank ON tf_owned (type, uuid)",
        )

        /**
         * 닉네임 중복을 **DB 차원에서** 막는 제약.
         *
         * 애플리케이션 검사만으로는 "검사 → 저장" 사이에 다른 요청이 끼어드는 경합을 막을 수
         * 없어서, 최종 방어선을 DB 에 둔다. NULL 은 여러 행이 있어도 UNIQUE 에 걸리지 않으므로
         * 닉네임 미설정 플레이어는 영향을 받지 않는다.
         */
        const val NICKNAME_UNIQUE_NAME = "idx_tf_player_nick_uq"

        /** 제약을 걸 수 없을 때 최소한 조회 성능이라도 확보하는 대체 인덱스. */
        const val NICKNAME_PLAIN_NAME = "idx_tf_player_nick_norm"

        /**
         * 두 인덱스의 **이름이 서로 달라야 한다.**
         *
         * 같은 이름을 쓰면, 중복 때문에 한 번 일반 인덱스로 물러난 뒤 관리자가 중복을 정리하고
         * 재기동해도 `CREATE UNIQUE INDEX IF NOT EXISTS` 가 "이미 있음" 으로 조용히 넘어가
         * 제약이 영영 걸리지 않는다.
         */
        const val NICKNAME_UNIQUE_INDEX =
            "CREATE UNIQUE INDEX IF NOT EXISTS $NICKNAME_UNIQUE_NAME ON tf_player (nickname_normalized)"

        const val NICKNAME_PLAIN_INDEX =
            "CREATE INDEX IF NOT EXISTS $NICKNAME_PLAIN_NAME ON tf_player (nickname_normalized)"
    }
}

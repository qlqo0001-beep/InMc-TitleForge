package kr.inmc.titleforge.storage

import kr.inmc.titleforge.config.Settings
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 이미 운영 중인 DB 를 새 스키마로 올리는 경로 검증.
 *
 * `nickname_normalized` 컬럼과 UNIQUE 제약은 기존 설치에서 마이그레이션으로 추가된다.
 * 이 경로가 조용히 실패하면(예전에 자리표시자 치환 버그가 그랬다) 컬럼이 없는 채로 굴러가다
 * 나중에 터지므로, 옛 스키마를 직접 만들어 두고 기동시켜 확인한다.
 */
class SqlStorageMigrationTest {

    private lateinit var folder: File
    private var storage: SqlStorage? = null

    private val dbFile: File get() = File(folder, "test.db")

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("tf-migration-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        runCatching { storage?.close() }
        folder.deleteRecursively()
    }

    /** 이 기능이 들어오기 전 버전의 tf_player 스키마를 만든다. */
    private fun createLegacySchema(vararg nicknames: Pair<String, String?>) {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE tf_player (
                        uuid TEXT NOT NULL,
                        name TEXT NOT NULL,
                        nickname TEXT,
                        nickname_changed_at BIGINT NOT NULL DEFAULT 0,
                        equip_stat TEXT,
                        equip_display TEXT,
                        equip_seal TEXT,
                        updated_at BIGINT NOT NULL DEFAULT 0,
                        PRIMARY KEY (uuid)
                    )
                    """.trimIndent(),
                )
            }
            conn.prepareStatement("INSERT INTO tf_player (uuid, name, nickname) VALUES (?, ?, ?)").use { st ->
                for ((name, nickname) in nicknames) {
                    st.setString(1, UUID.nameUUIDFromBytes(name.toByteArray()).toString())
                    st.setString(2, name)
                    st.setString(3, nickname)
                    st.addBatch()
                }
                st.executeBatch()
            }
        }
    }

    private fun connect(): Connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")

    private fun boot(): SqlStorage = SqlStorage(
        Settings.StorageSettings(
            type = Settings.StorageType.SQLITE,
            sqliteFile = "test.db",
            host = "", port = 0, database = "", username = "", password = "",
            properties = "", poolSize = 2,
            autosaveSeconds = 0L, cacheKeepSeconds = 0L,
        ),
        folder,
        Logger.getLogger("tf-migration-test").apply { level = Level.OFF },
    ).also { it.init(); storage = it }

    private fun normalizedOf(name: String): String? = connect().use { conn ->
        conn.prepareStatement("SELECT nickname_normalized FROM tf_player WHERE name = ?").use { st ->
            st.setString(1, name)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    @Test
    fun `옛 스키마에 컬럼이 추가되고 기존 닉네임이 정규화되어 채워진다`() {
        createLegacySchema("Steve" to "Yun", "Alex" to "달빛", "Nobody" to null)

        val storage = boot()

        assertEquals("yun", normalizedOf("Steve"))
        assertEquals("달빛", normalizedOf("Alex"))
        assertEquals(null, normalizedOf("Nobody"))

        // 정규화 컬럼 기반 조회가 실제로 동작해야 한다.
        assertTrue(storage.isNicknameTaken("YUN", null))
        assertFalse(storage.isNicknameTaken("처음보는닉", null))
    }

    @Test
    fun `이미 중복이 있으면 데이터를 지우지 않고 제약 없이 기동한다`() {
        // 대소문자만 다른 중복. UNIQUE 를 그냥 걸면 생성이 실패하는 상황이다.
        createLegacySchema("Steve" to "Yun", "Alex" to "yun")

        val storage = boot()

        // 어느 쪽도 삭제되지 않아야 한다 (맞춤 지침 7.5-19: 파괴적 동작 금지).
        connect().use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM tf_player").use { st ->
                st.executeQuery().use { rs ->
                    rs.next()
                    assertEquals(2, rs.getInt(1))
                }
            }
        }
        // 기능 자체는 계속 동작한다.
        assertTrue(storage.isNicknameTaken("yun", null))
    }

    @Test
    fun `중복을 정리한 뒤 다시 켜면 UNIQUE 제약이 걸린다`() {
        createLegacySchema("Steve" to "Yun", "Alex" to "yun")
        boot()
        runCatching { storage?.close() }

        // 관리자가 /it resetnick 으로 한쪽을 비운 상황
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.executeUpdate("UPDATE tf_player SET nickname = NULL, nickname_normalized = NULL WHERE name = 'Alex'")
            }
        }

        boot()

        // 이제 제약이 살아 있으므로 같은 닉네임을 밀어 넣으면 거부돼야 한다.
        connect().use { conn ->
            val duplicated = runCatching {
                conn.prepareStatement("UPDATE tf_player SET nickname_normalized = 'yun' WHERE name = 'Alex'")
                    .use { it.executeUpdate() }
            }
            assertTrue(duplicated.isFailure, "UNIQUE 제약이 걸려 있어야 한다")
        }
    }
}

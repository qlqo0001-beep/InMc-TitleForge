package kr.inmc.titleforge.storage

import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.player.PlayerProfile
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 SQLite 파일에 대고 도는 통합 테스트.
 *
 * 닉네임 UNIQUE 제약을 넣으면서 `tf_player` 쓰기 경로를 `REPLACE INTO` 에서
 * INSERT-IGNORE + UPDATE 로 바꿨다. 그 경로는 이 플러그인에서 가장 자주 실행되는 코드라
 * 컴파일만으로는 안심할 수 없어 직접 검증한다.
 */
class SqlStorageNicknameTest {

    private lateinit var folder: java.io.File
    private lateinit var storage: SqlStorage

    private fun profile(name: String, nickname: String? = null): PlayerProfile =
        PlayerProfile(UUID.randomUUID(), name).also { it.nickname = nickname }

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("tf-storage-test").toFile()
        val logger = Logger.getLogger("tf-test").apply { level = Level.OFF }
        storage = SqlStorage(
            Settings.StorageSettings(
                type = Settings.StorageType.SQLITE,
                sqliteFile = "test.db",
                host = "", port = 0, database = "", username = "", password = "",
                properties = "", poolSize = 2,
                autosaveSeconds = 0L, cacheKeepSeconds = 0L,
            ),
            folder,
            logger,
        )
        storage.init()
    }

    @AfterTest
    fun tearDown() {
        runCatching { storage.close() }
        folder.deleteRecursively()
    }

    @Test
    fun `신규 프로필이 삽입되고 그대로 다시 읽힌다`() {
        val saved = profile("Steve", "윤").also {
            it.grant(BadgeType.TITLE, "개척자")
            it.setEquipped(kr.inmc.titleforge.player.EquipSlot.DISPLAY, "개척자")
        }
        storage.saveProfile(saved)

        val loaded = storage.loadProfile(saved.uuid, "Steve")
        assertEquals("윤", loaded.nickname)
        assertEquals("개척자", loaded.displayTitle)
        assertTrue(loaded.has(BadgeType.TITLE, "개척자"))
    }

    @Test
    fun `같은 프로필을 다시 저장해도 값이 갱신된다`() {
        val saved = profile("Steve", "윤")
        storage.saveProfile(saved)

        saved.nickname = "달"
        storage.saveProfile(saved)

        assertEquals("달", storage.loadProfile(saved.uuid, "Steve").nickname)
        // 예전 닉네임은 비어 있어야 다른 사람이 쓸 수 있다.
        assertFalse(storage.isNicknameTaken("윤", null))
    }

    @Test
    fun `중복 닉네임 저장은 거부되고 기존 플레이어 행은 살아남는다`() {
        val first = profile("Steve", "윤").also { it.grant(BadgeType.TITLE, "개척자") }
        storage.saveProfile(first)

        val second = profile("Alex", "윤")
        assertFailsWith<Exception> { storage.saveProfile(second) }

        // REPLACE INTO 를 쓰면 여기서 first 의 행이 통째로 지워진다. 그 회귀를 막는 검사다.
        val survivor = storage.loadProfile(first.uuid, "Steve")
        assertEquals("윤", survivor.nickname)
        assertTrue(survivor.has(BadgeType.TITLE, "개척자"))
    }

    @Test
    fun `대소문자와 전각이 달라도 같은 닉네임으로 본다`() {
        storage.saveProfile(profile("Steve", "Yun"))

        assertTrue(storage.isNicknameTaken("yun", null))
        assertTrue(storage.isNicknameTaken("YUN", null))
        assertTrue(storage.isNicknameTaken("Ｙｕｎ", null))   // 전각
        assertFalse(storage.isNicknameTaken("Yuna", null))
    }

    @Test
    fun `본인 닉네임은 중복으로 보지 않는다`() {
        val mine = profile("Steve", "윤")
        storage.saveProfile(mine)

        assertTrue(storage.isNicknameTaken("윤", null))
        assertFalse(storage.isNicknameTaken("윤", mine.uuid))
    }

    @Test
    fun `닉네임이 없는 플레이어는 여럿이어도 제약에 걸리지 않는다`() {
        // UNIQUE 인덱스에서 NULL 은 서로 충돌하지 않아야 한다.
        repeat(3) { storage.saveProfile(profile("Player$it", null)) }

        val loaded = storage.loadProfile(UUID.randomUUID(), "Nobody")
        assertNull(loaded.nickname)
    }
}

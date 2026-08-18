package kr.inmc.titleforge.storage

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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 닉네임이 **실제 아이디**와 겹치는 경우의 처리 검증.
 *
 * 실명 `nine` 인 계정과 닉네임 `nine` 인 다른 계정이 공존하면, 명령어에서 "nine" 이 누구를
 * 가리키는지 접속 여부에 따라 달라져 비결정적이 된다. 두 겹으로 막는다.
 *  - 사전 차단: 이미 알려진 실명과 겹치는 닉네임 신청을 거부한다.
 *  - 사후 해제: 그 실명 계정이 나중에 처음 접속하면 선점자를 찾아 풀어 준다.
 */
class SqlStorageRealNameTest {

    private lateinit var folder: java.io.File
    private lateinit var storage: SqlStorage

    private fun profile(name: String, nickname: String? = null): PlayerProfile =
        PlayerProfile(UUID.randomUUID(), name).also { it.nickname = nickname }

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("tf-realname-test").toFile()
        storage = SqlStorage(
            Settings.StorageSettings(
                type = Settings.StorageType.SQLITE,
                sqliteFile = "test.db",
                host = "", port = 0, database = "", username = "", password = "",
                properties = "", poolSize = 2,
                autosaveSeconds = 0L, cacheKeepSeconds = 0L,
            ),
            folder,
            Logger.getLogger("tf-realname-test").apply { level = Level.OFF },
        )
        storage.init()
    }

    @AfterTest
    fun tearDown() {
        runCatching { storage.close() }
        folder.deleteRecursively()
    }

    // ── 사전 차단 ──────────────────────────────────────────────────────

    @Test
    fun `이미 존재하는 실명과 겹치는 닉네임은 거부된다`() {
        storage.saveProfile(profile("nine"))

        assertTrue(storage.isNicknameTaken("nine", null), "실명과 겹치는데 통과했다")
    }

    @Test
    fun `실명 비교는 대소문자를 가리지 않는다`() {
        storage.saveProfile(profile("NineSik"))

        assertTrue(storage.isNicknameTaken("ninesik", null))
        assertTrue(storage.isNicknameTaken("NINESIK", null))
    }

    @Test
    fun `자기 실명을 닉네임으로 쓰는 것은 막지 않는다`() {
        val mine = profile("nine")
        storage.saveProfile(mine)

        // 본인 행은 except 로 제외되므로 걸리지 않아야 한다.
        assertFalse(storage.isNicknameTaken("nine", mine.uuid))
    }

    @Test
    fun `겹치지 않는 닉네임은 그대로 통과한다`() {
        storage.saveProfile(profile("nine"))

        assertFalse(storage.isNicknameTaken("윤", null))
        assertFalse(storage.isNicknameTaken("nine2", null))
    }

    @Test
    fun `기존 닉네임 중복 검사도 그대로 동작한다`() {
        val other = profile("Steve", "윤")
        storage.saveProfile(other)

        assertTrue(storage.isNicknameTaken("윤", null))
        assertFalse(storage.isNicknameTaken("윤", other.uuid))
    }

    // ── 사후 해제 (선점자 찾기) ────────────────────────────────────────

    @Test
    fun `실명을 선점한 닉네임 보유자를 찾아낸다`() {
        val squatter = profile("ninesik", "nine")
        storage.saveProfile(squatter)

        val newcomer = UUID.randomUUID()
        val found = storage.findNicknameHolder("nine", newcomer)

        assertNotNull(found)
        assertEquals(squatter.uuid, found.first)
        assertEquals("nine", found.second)
    }

    @Test
    fun `자기 자신은 선점자로 잡히지 않는다`() {
        val mine = profile("ninesik", "nine")
        storage.saveProfile(mine)

        assertNull(storage.findNicknameHolder("nine", mine.uuid))
    }

    @Test
    fun `선점자가 없으면 null 이다`() {
        storage.saveProfile(profile("Steve", "윤"))

        assertNull(storage.findNicknameHolder("nine", UUID.randomUUID()))
    }

    // ── 강제 해제 알림 영속성 ──────────────────────────────────────────

    @Test
    fun `강제 해제 알림이 저장되고 다시 읽힌다`() {
        // 오프라인 중에 해제됐다면 재시작 후에도 알릴 수 있어야 한다.
        val holder = profile("ninesik", "nine")
        storage.saveProfile(holder)

        holder.nickname = null
        holder.nicknameResetNotice = "nine"
        storage.saveProfile(holder)

        val reloaded = storage.loadProfile(holder.uuid, "ninesik")
        assertNull(reloaded.nickname)
        assertEquals("nine", reloaded.nicknameResetNotice)

        // 해제된 닉네임은 이제 비어 있으므로 실명 주인이 쓸 수 있다.
        assertNull(storage.findNicknameHolder("nine", UUID.randomUUID()))
    }

    @Test
    fun `알림을 전달한 뒤 비우면 다시 뜨지 않는다`() {
        val holder = profile("ninesik").also { it.nicknameResetNotice = "nine" }
        storage.saveProfile(holder)

        holder.nicknameResetNotice = null
        storage.saveProfile(holder)

        assertNull(storage.loadProfile(holder.uuid, "ninesik").nicknameResetNotice)
    }
}

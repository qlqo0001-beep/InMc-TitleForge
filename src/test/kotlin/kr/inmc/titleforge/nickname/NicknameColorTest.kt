package kr.inmc.titleforge.nickname

import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 닉네임 서식 권한 경계 검증.
 *
 * 요구사항: MiniMessage 와 `&` 코드를 모두 쓸 수 있되, **닉네임에 색을 붙이는 것은
 * 관리자의 `/it setnick` 으로만** 가능해야 한다. 일반 유저가 다이얼로그로 넣은 값은
 * 어떤 형태로도 색이 되면 안 된다.
 *
 * 저장 값은 "그대로 파싱 가능한 MiniMessage 원문" 이라는 불변식을 지키므로,
 * 여기서는 각 입력 경계를 통과한 결과를 실제로 렌더해 색이 붙었는지 확인한다.
 */
class NicknameColorTest {

    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    private fun render(stored: String) = mm.deserialize(stored)
    private fun visible(stored: String) = plain.serialize(render(stored))

    // ── 관리자: 색을 붙일 수 있어야 한다 ──────────────────────────────

    @Test
    fun `관리자는 앰퍼샌드 코드로 색을 붙일 수 있다`() {
        val stored = NicknameService.formatAdminInput("&c윤")

        assertEquals("윤", visible(stored))
        assertEquals(NamedTextColor.RED, render(stored).color())
    }

    @Test
    fun `관리자는 MiniMessage 로도 색을 붙일 수 있다`() {
        val stored = NicknameService.formatAdminInput("<red>윤</red>")

        assertEquals("윤", visible(stored))
        assertEquals(NamedTextColor.RED, render(stored).color())
    }

    @Test
    fun `관리자는 앰퍼샌드 hex 색도 쓸 수 있다`() {
        val stored = NicknameService.formatAdminInput("&#ff8800윤")

        assertEquals("윤", visible(stored))
        assertEquals(0xFF8800, render(stored).color()?.value())
    }

    @Test
    fun `색 코드로 쓰이지 않는 앰퍼샌드는 글자로 남는다`() {
        // `&z` 나 `& ` 처럼 뒤 글자가 색 코드가 아니면 그대로 둔다.
        // (`&b` 는 대소문자 관계없이 실제 색 코드라 여기서 쓰면 안 된다.)
        assertEquals("Tom & Jerry", visible(NicknameService.formatAdminInput("Tom & Jerry")))
        assertEquals("A&Z", visible(NicknameService.formatAdminInput("A&Z")))
    }

    // ── 유저: 어떤 방법으로도 색이 붙으면 안 된다 ─────────────────────

    @Test
    fun `유저의 앰퍼샌드 코드는 색이 되지 않는다`() {
        val stored = NicknameService.sanitizeUserInput("&c윤")

        assertNull(render(stored).color(), "유저 입력에 색이 붙었다")
        assertEquals("윤", visible(stored))
    }

    @Test
    fun `유저의 MiniMessage 태그는 글자 그대로 남는다`() {
        val stored = NicknameService.sanitizeUserInput("<red>윤</red>")

        assertNull(render(stored).color(), "유저 입력에 색이 붙었다")
        // 태그가 무력화되어 눈에 보이는 글자로 남아야 한다.
        assertEquals("<red>윤</red>", visible(stored))
    }

    @Test
    fun `유저의 섹션 코드도 색이 되지 않는다`() {
        val stored = NicknameService.sanitizeUserInput("§c윤")

        assertNull(render(stored).color(), "유저 입력에 색이 붙었다")
        assertEquals("윤", visible(stored))
    }

    @Test
    fun `유저의 hex 코드도 색이 되지 않는다`() {
        val stored = NicknameService.sanitizeUserInput("&#ff0000윤")

        assertNull(render(stored).color(), "유저 입력에 색이 붙었다")
        assertEquals("윤", visible(stored))
    }

    @Test
    fun `유저의 그라디언트 태그도 무력화된다`() {
        val stored = NicknameService.sanitizeUserInput("<gradient:red:blue>윤</gradient>")

        assertNull(render(stored).color(), "유저 입력에 색이 붙었다")
        assertTrue(visible(stored).contains("<gradient"), "태그가 글자로 남아야 한다")
    }

    @Test
    fun `평범한 유저 닉네임은 손상되지 않는다`() {
        assertEquals("윤", visible(NicknameService.sanitizeUserInput("윤")))
        assertEquals("Steve123", visible(NicknameService.sanitizeUserInput("Steve123")))
    }

    // ── 중복 판정은 색을 무시해야 한다 ────────────────────────────────

    @Test
    fun `색만 다른 닉네임은 같은 것으로 본다`() {
        val plainKey = NicknameNormalizer.normalize("윤")

        // 색으로 사칭하는 것을 막아야 하므로 전부 같은 키가 나와야 한다.
        assertEquals(plainKey, NicknameNormalizer.normalize("<red>윤</red>"))
        assertEquals(plainKey, NicknameNormalizer.normalize("&c윤"))
        assertEquals(plainKey, NicknameNormalizer.normalize("§c윤"))
        assertEquals(plainKey, NicknameNormalizer.normalize("&#ff0000윤"))
        assertEquals(plainKey, NicknameNormalizer.normalize("<gradient:red:blue>윤</gradient>"))
    }

    @Test
    fun `색만 있고 글자가 없으면 빈 닉네임으로 본다`() {
        assertNull(NicknameNormalizer.normalize("&c"))
        assertNull(NicknameNormalizer.normalize("<red></red>"))
    }

    @Test
    fun `대소문자와 전각 정규화는 그대로 동작한다`() {
        val key = NicknameNormalizer.normalize("Yun")
        assertEquals(key, NicknameNormalizer.normalize("YUN"))
        assertEquals(key, NicknameNormalizer.normalize("Ｙｕｎ"))
        assertEquals(key, NicknameNormalizer.normalize("<green>yUn</green>"))
    }
}

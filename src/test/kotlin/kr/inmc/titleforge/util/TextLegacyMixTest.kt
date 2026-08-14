package kr.inmc.titleforge.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MiniMessage 와 레거시 코드를 **섞어 쓴** 문자열 검증.
 *
 * 회귀 배경: `&` 지원을 넣으면서 문자열 전체를 레거시로 해석한 뒤 MiniMessage 로 재직렬화했다.
 * 그러면 원문에 이미 있던 MiniMessage 태그가 글자로 취급돼 이스케이프된다.
 * 실제로 `nameplate-format: '%cmi_user_prefix% <title>&r<nickname>'` 에서 `&r` 하나 때문에
 * `<title>` · `<nickname>` 자리표시자와 플레이스홀더가 돌려준 `<dark_green>` 까지 전부
 * 글자로 출력됐다. 그 조합을 그대로 고정해 둔다.
 */
class TextLegacyMixTest {

    private val plain = PlainTextComponentSerializer.plainText()

    @Test
    fun `레거시 코드가 섞여도 MiniMessage 태그가 살아남는다`() {
        val converted = Text.fromLegacy("<dark_green>중 <title>&r<nickname>")

        assertTrue(converted.contains("<dark_green>"), "MiniMessage 태그가 이스케이프됐다: $converted")
        assertTrue(converted.contains("<title>"), "자리표시자가 이스케이프됐다: $converted")
        assertTrue(converted.contains("<nickname>"), "자리표시자가 이스케이프됐다: $converted")
        assertFalse(converted.contains("\\<"), "이스케이프 문자가 들어갔다: $converted")
    }

    @Test
    fun `실제 nameplate 포맷이 그대로 조립된다`() {
        // %cmi_user_prefix% 가 이미 치환된 뒤의 문자열을 가정한다.
        val resolved = "<dark_green>중 <title>&r<nickname>"

        val component = Text.mini(
            resolved,
            "title" to Component.text("[개척자]"),
            "nickname" to Component.text("윤"),
        )

        // 태그는 서식으로, 자리표시자는 값으로 바뀌어 글자에는 남지 않아야 한다.
        assertEquals("중 [개척자]윤", plain.serialize(component))
    }

    @Test
    fun `레거시 코드가 실제 색으로 바뀐다`() {
        val component = Text.mini(Text.fromLegacy("&c빨강"))

        assertEquals("빨강", plain.serialize(component))
        assertEquals(NamedTextColor.RED, component.color())
    }

    @Test
    fun `섹션 코드도 동일하게 동작한다`() {
        // 외부 플레이스홀더(CMI 등)는 § 를 돌려준다.
        val converted = Text.fromLegacy("§a초록 <title>")

        assertTrue(converted.contains("<green>"), converted)
        assertTrue(converted.contains("<title>"), converted)
    }

    @Test
    fun `리셋 코드는 reset 태그가 된다`() {
        assertEquals("<reset>", Text.fromLegacy("&r"))
    }

    @Test
    fun `hex 코드는 hex 태그가 된다`() {
        assertEquals("<#ff8800>글자", Text.fromLegacy("&#ff8800글자"))
    }

    @Test
    fun `색 코드가 아닌 앰퍼샌드는 건드리지 않는다`() {
        assertEquals("Tom & Jerry", Text.fromLegacy("Tom & Jerry"))
        assertEquals("A&Z", Text.fromLegacy("A&Z"))
    }

    @Test
    fun `레거시가 없으면 원문을 그대로 돌려준다`() {
        val raw = "<red>순수 MiniMessage</red> <title>"
        assertEquals(raw, Text.fromLegacy(raw))
    }

    @Test
    fun `대문자 코드도 인식한다`() {
        assertEquals("<red>글자", Text.fromLegacy("&C글자"))
    }
}

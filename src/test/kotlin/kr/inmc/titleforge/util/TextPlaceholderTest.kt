package kr.inmc.titleforge.util

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 메시지 토큰 치환 규칙 검증.
 *
 * `Messages` 는 공용 토큰(`<allowed>` 등)을 호출부 인자 **뒤에** 붙여 넘긴다.
 * 같은 이름이 겹칠 때 어느 쪽이 이기는지는 MiniMessage 의 TagResolver 순서에 달려 있는데,
 * 이 규칙이 뒤집히면 호출부가 넘긴 값이 조용히 무시되므로 여기서 못 박아 둔다.
 */
class TextPlaceholderTest {

    private val plain = PlainTextComponentSerializer.plainText()

    private fun render(template: String, vararg values: Pair<String, Any?>) =
        plain.serialize(Text.mini(template, *values))

    @Test
    fun `토큰이 값으로 치환된다`() {
        assertEquals("허용: 한글, 영문", render("허용: <allowed>", "allowed" to "한글, 영문"))
    }

    @Test
    fun `같은 이름이 겹치면 나중에 넘긴 값이 이긴다`() {
        // Messages.render 가 (공용 토큰, 호출부 인자) 순으로 넘기는 근거.
        // 이 순서가 뒤집히면 공용 토큰이 호출부 값을 덮어써 조용히 잘못된 문장이 나간다.
        assertEquals("B", render("<x>", "x" to "A", "x" to "B"))
    }

    @Test
    fun `쓰지 않은 토큰을 넘겨도 문제가 없다`() {
        assertEquals("그냥 문장", render("그냥 문장", "allowed" to "한글", "nick_min" to 2))
    }

    @Test
    fun `정의되지 않은 토큰은 원문 그대로 남는다`() {
        // 오타를 냈을 때 메시지가 통째로 깨지지 않고 눈에 띄게만 한다.
        assertEquals("<unknown_token>", render("<unknown_token>"))
    }

    @Test
    fun `숫자 토큰도 문자열로 치환된다`() {
        assertEquals("2~8자", render("<min>~<max>자", "min" to 2, "max" to 8))
    }

    @Test
    fun `여러 토큰을 한 문장에서 쓸 수 있다`() {
        assertEquals(
            "허용: 한글 / 길이: 2~8",
            render(
                "허용: <allowed> / 길이: <nick_min>~<nick_max>",
                "allowed" to "한글", "nick_min" to 2, "nick_max" to 8,
            ),
        )
    }
}

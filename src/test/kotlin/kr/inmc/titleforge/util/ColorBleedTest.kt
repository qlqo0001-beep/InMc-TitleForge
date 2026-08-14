package kr.inmc.titleforge.util

import kr.inmc.titleforge.display.TokenRenderer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 플레이스홀더 색 번짐 차단 검증.
 *
 * 외부 플레이스홀더는 색을 열어 두고 닫지 않는 경우가 많다(`<dark_green>중`).
 * 그대로 이어 붙이면 뒤따르는 칭호·닉네임까지 그 색이 된다.
 * `display.color-bleed: false`(기본)일 때 [TokenRenderer] 가 값을 가두는지 확인한다.
 *
 * 실제 격리는 MiniMessage 의 "여는 태그를 닫으면 안쪽 태그도 닫힌다" 규칙에 기대므로,
 * 그 규칙 자체가 바뀌면 여기서 깨진다.
 */
class ColorBleedTest {

    private val mm = MiniMessage.miniMessage()
    private val plain = PlainTextComponentSerializer.plainText()

    /** 각 글자 조각이 어떤 색으로 보이는지 평평하게 모은다. */
    private fun colorsOf(source: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun walk(comp: Component, inherited: String) {
            val color = comp.color()?.toString()?.substringAfter("name=\"")?.substringBefore("\"") ?: inherited
            val text = plain.serialize(comp.children(emptyList()))
            if (text.isNotEmpty()) result[text] = color
            comp.children().forEach { walk(it, color) }
        }
        walk(mm.deserialize(source), "none")
        return result
    }

    /** TokenRenderer.isolate 와 동일한 조립. */
    private fun isolated(value: String) = TokenRenderer.ISOLATE_OPEN + value + TokenRenderer.ISOLATE_CLOSE

    @Test
    fun `가두지 않으면 색이 뒤로 번진다`() {
        // 문제 상황 그대로: 접두사가 색을 닫지 않았다.
        val colors = colorsOf("<dark_green>중 <white>|</white>칭호")

        // 번짐이 실재함을 확인 (이게 기본값 false 의 존재 이유다).
        assertEquals("dark_green", colors["중 "])
    }

    @Test
    fun `가두면 뒤따르는 내용이 영향을 받지 않는다`() {
        val colors = colorsOf(isolated("<dark_green>중") + "칭호")

        assertEquals("dark_green", colors["중"])
        assertEquals("none", colors["칭호"], "색이 뒤로 번졌다")
    }

    @Test
    fun `바깥에서 열어 둔 서식은 유지된다`() {
        // <reset> 을 쓰지 않는 이유 — 바깥 회색이 살아 있어야 한다.
        val colors = colorsOf("<gray>이름: " + isolated("<dark_green>중") + " 님")

        assertEquals("gray", colors["이름: "])
        assertEquals("dark_green", colors["중"])
        assertEquals("gray", colors[" 님"], "바깥 서식까지 지워졌다")
    }

    @Test
    fun `reset 은 바깥 서식까지 지운다`() {
        // 대안으로 검토했던 방식이 왜 부적절한지 고정해 둔다.
        val colors = colorsOf("<gray>이름: <dark_green>중<reset> 님")

        assertEquals("none", colors[" 님"])
    }

    @Test
    fun `가둬도 글자는 그대로다`() {
        assertEquals("중", plain.serialize(mm.deserialize(isolated("<dark_green>중"))))
    }

    @Test
    fun `색을 닫아 둔 값은 원래도 번지지 않는다`() {
        val colors = colorsOf(isolated("<dark_green>중</dark_green>") + "칭호")

        assertEquals("none", colors["칭호"])
    }

    @Test
    fun `레거시 코드에서 온 값도 가둬진다`() {
        // &2중 -> <dark_green>중 으로 바뀐 뒤 격리된다.
        val converted = Text.fromLegacy("&2중")
        val colors = colorsOf(isolated(converted) + "칭호")

        assertEquals(NamedTextColor.DARK_GREEN.toString().substringAfter("name=\"").substringBefore("\""), colors["중"])
        assertEquals("none", colors["칭호"])
    }
}

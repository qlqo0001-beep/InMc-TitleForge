package kr.inmc.titleforge.util

import kr.inmc.titleforge.display.TokenRenderer
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 플레이스홀더 색 번짐 차단 검증.
 *
 * 외부 플레이스홀더는 색을 열어 두고 닫지 않는 경우가 많다(`<dark_green>중`).
 * 그대로 이어 붙이면 뒤따르는 칭호·닉네임까지 그 색이 된다.
 *
 * ### 태그로 감싸는 방식이 실패했던 이유
 * 처음에는 값을 `<font:default>…</font>` 로 감쌌다. 그런데 CMI 처럼 `§r` 로 끝나는
 * 플레이스홀더는 값 안에 `<reset>` 이 들어가는데, `<reset>` 은 **열려 있는 모든 태그를 닫아**
 * 감싼 태그까지 닫아 버린다. 그러면 `</font>` 가 짝을 잃고 글자로 출력된다
 * (`중</font> NineSik`). 그래서 값을 **Component 로 넘기는** 방식으로 바꿨다.
 *
 * 여기서는 그 조립 규칙을 Component 자리표시자로 재현해 고정한다.
 */
class ColorBleedTest {

    private val plain = PlainTextComponentSerializer.plainText()

    /** 각 글자 조각이 어떤 색으로 보이는지 평평하게 모은다. */
    private fun colorsOf(component: Component): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        fun walk(comp: Component, inherited: String) {
            val color = comp.color()?.toString()?.substringAfter("name=\"")?.substringBefore("\"") ?: inherited
            val text = plain.serialize(comp.children(emptyList()))
            if (text.isNotEmpty()) result[text] = color
            comp.children().forEach { walk(it, color) }
        }
        walk(component, "none")
        return result
    }

    /** 치환값을 Component 자리표시자로 넘기는, 실제와 같은 조립. */
    private fun isolated(template: String, value: String, vararg extra: Pair<String, Any?>): Component =
        Text.mini(template, *extra, "${TokenRenderer.PLACEHOLDER_PREFIX}0" to Text.mini(value))

    private val ph = "<${TokenRenderer.PLACEHOLDER_PREFIX}0>"

    @Test
    fun `가두지 않으면 색이 뒤로 번진다`() {
        // 문제 상황 그대로: 값을 원문에 이어 붙인 경우.
        val colors = colorsOf(Text.mini("<dark_green>중칭호"))
        assertEquals("dark_green", colors["중칭호"])
    }

    @Test
    fun `가두면 뒤따르는 내용이 영향을 받지 않는다`() {
        val colors = colorsOf(isolated("${ph}칭호", "<dark_green>중"))

        assertEquals("dark_green", colors["중"])
        assertEquals("none", colors["칭호"], "색이 뒤로 번졌다")
    }

    @Test
    fun `값에 reset 이 있어도 깨지지 않는다`() {
        // 회귀 지점: CMI 가 §2중§r 을 돌려주던 실제 상황.
        val value = Text.fromLegacy("§2중§r")
        val component = isolated("$ph <nickname>", value, "nickname" to Component.text("NineSik"))

        // </font> 같은 잔해가 글자로 남으면 안 된다.
        assertEquals("중 NineSik", plain.serialize(component))
    }

    @Test
    fun `값에 reset 이 있어도 색이 뒤로 새지 않는다`() {
        val colors = colorsOf(isolated("${ph}칭호", Text.fromLegacy("§2중§r")))

        assertEquals("dark_green", colors["중"])
        assertEquals("none", colors["칭호"])
    }

    @Test
    fun `바깥에서 열어 둔 서식은 유지된다`() {
        val colors = colorsOf(isolated("<gray>이름: $ph 님", "<dark_green>중"))

        assertEquals("gray", colors["이름: "])
        assertEquals("dark_green", colors["중"])
        assertEquals("gray", colors[" 님"], "바깥 서식까지 지워졌다")
    }

    @Test
    fun `reset 을 그냥 쓰면 바깥 서식까지 지워진다`() {
        // 대안으로 검토했다가 버린 방식이 왜 부적절한지 고정해 둔다.
        val colors = colorsOf(Text.mini("<gray>이름: <dark_green>중<reset> 님"))
        assertEquals("none", colors[" 님"])
    }

    @Test
    fun `가둬도 글자는 그대로다`() {
        assertEquals("중", plain.serialize(isolated(ph, "<dark_green>중")))
    }

    @Test
    fun `레거시 코드에서 온 값도 가둬진다`() {
        val colors = colorsOf(isolated("${ph}칭호", Text.fromLegacy("&2중")))

        assertEquals("dark_green", colors["중"])
        assertEquals("none", colors["칭호"])
    }
}

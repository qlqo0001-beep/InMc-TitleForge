package kr.inmc.titleforge.stat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 채팅으로 들어온 스텟 값 해석 검증. */
class StatValueParserTest {

    @Test
    fun `절대값을 그대로 설정한다`() {
        val result = StatValueParser.parse(StatType.ATTACK_DAMAGE, "2.5")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(2.5, result.internal)
        assertEquals(2.5, result.display)
        assertFalse(result.outOfSoftRange)
    }

    @Test
    fun `음수도 허용한다`() {
        val result = StatValueParser.parse(StatType.ARMOR, "-3")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(-3.0, result.internal)
    }

    @Test
    fun `앞에 붙은 플러스 기호를 무시한다`() {
        val result = StatValueParser.parse(StatType.ARMOR, "+4")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(4.0, result.internal)
    }

    @Test
    fun `표시 단위로 입력하면 내부값으로 변환된다`() {
        // 이동 속도는 내부 0.1 = 표시 +10%
        val result = StatValueParser.parse(StatType.MOVEMENT_SPEED, "10")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(0.1, result.internal)
        assertEquals(10.0, result.display)
        assertEquals("+10%", StatType.MOVEMENT_SPEED.format(result.internal))
    }

    @Test
    fun `퍼센트 기호와 쉼표를 무시한다`() {
        val result = StatValueParser.parse(StatType.MOVEMENT_SPEED, "10%")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(0.1, result.internal)

        val comma = StatValueParser.parse(StatType.MAX_HEALTH, "1,0")
        assertIs<StatValueParser.Result.Set>(comma)
        assertEquals(10.0, comma.internal)
    }

    @Test
    fun `0 과 제거 키워드는 스텟을 지운다`() {
        for (input in listOf("0", "0.0", "제거", "삭제", "remove", "reset", "-")) {
            assertIs<StatValueParser.Result.Remove>(
                StatValueParser.parse(StatType.LUCK, input),
                "입력: $input",
            )
        }
    }

    @Test
    fun `취소 키워드는 변경하지 않는다`() {
        for (input in listOf("취소", "cancel", "CANCEL", "c")) {
            assertIs<StatValueParser.Result.Cancel>(
                StatValueParser.parse(StatType.LUCK, input),
                "입력: $input",
            )
        }
    }

    @Test
    fun `숫자가 아니면 거부한다`() {
        for (input in listOf("", "   ", "abc", "1.2.3", "NaN", "무한대")) {
            assertIs<StatValueParser.Result.Invalid>(
                StatValueParser.parse(StatType.LUCK, input),
                "입력: $input",
            )
        }
    }

    @Test
    fun `방어적 상한을 넘으면 거부한다`() {
        val result = StatValueParser.parse(StatType.MAX_HEALTH, "999999999")
        assertIs<StatValueParser.Result.TooLarge>(result)
        assertEquals(StatType.HARD_LIMIT, result.limit)
    }

    @Test
    fun `권장 범위를 벗어나면 경고 플래그가 켜지되 적용은 된다`() {
        val result = StatValueParser.parse(StatType.MAX_HEALTH, "500")
        assertIs<StatValueParser.Result.Set>(result)
        assertTrue(result.outOfSoftRange)
        assertEquals(500.0, result.internal)
    }

    @Test
    fun `반올림 후 0 이 되면 제거로 처리한다`() {
        // 내부 소수점 4자리 기준으로 잘려 0 이 되는 값
        val result = StatValueParser.parse(StatType.MOVEMENT_SPEED, "0.0000001")
        assertIs<StatValueParser.Result.Remove>(result)
    }

    @Test
    fun `앞뒤 공백을 무시한다`() {
        val result = StatValueParser.parse(StatType.ARMOR, "  7  ")
        assertIs<StatValueParser.Result.Set>(result)
        assertEquals(7.0, result.internal)
    }
}

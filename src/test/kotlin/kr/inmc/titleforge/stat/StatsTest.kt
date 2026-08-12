package kr.inmc.titleforge.stat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 스텟 맵 직렬화 · 병합 검증. 저장 형식이 깨지면 전 서버 데이터가 어긋난다. */
class StatsTest {

    @Test
    fun `직렬화 후 역직렬화하면 같은 값이 나온다`() {
        val original = mapOf(
            "max_health" to 2.0,
            "attack_damage" to 1.5,
            "movement_speed" to 0.1,
        )
        assertEquals(original, Stats.deserialize(Stats.serialize(original)))
    }

    @Test
    fun `0 값은 저장하지 않는다`() {
        val serialized = Stats.serialize(mapOf("armor" to 0.0, "luck" to 1.0))
        assertFalse(serialized.contains("armor"))
        assertTrue(serialized.contains("luck"))
    }

    @Test
    fun `빈 값과 깨진 값을 안전하게 처리한다`() {
        assertTrue(Stats.deserialize(null).isEmpty())
        assertTrue(Stats.deserialize("").isEmpty())
        assertTrue(Stats.deserialize("   ").isEmpty())
        // 형식이 깨진 토큰(=로 시작, = 없음)만 버린다.
        assertEquals(mapOf("armor" to 3.0), Stats.deserialize("깨짐;=2;armor=3;luck=abc"))
    }

    @Test
    fun `merge 는 같은 스텟을 더한다`() {
        val merged = Stats.merge(
            mapOf("max_health" to 2.0, "armor" to 1.0),
            mapOf("max_health" to 3.0),
        )
        assertEquals(5.0, merged["max_health"])
        assertEquals(1.0, merged["armor"])
    }

    @Test
    fun `합이 0 이 되면 항목을 지운다`() {
        val merged = Stats.merge(
            mapOf("armor" to 2.0),
            mapOf("armor" to -2.0),
        )
        assertFalse(merged.containsKey("armor"))
    }

    @Test
    fun `mergeInto 는 대상 맵에 누적한다`() {
        val target = LinkedHashMap<String, Double>()
        Stats.mergeInto(target, mapOf("luck" to 1.0))
        Stats.mergeInto(target, mapOf("luck" to 2.0))
        assertEquals(3.0, target["luck"])
    }

    @Test
    fun `with 는 값을 바꾸고 0 이면 제거한다`() {
        val base = mapOf("armor" to 1.0, "luck" to 2.0)

        val updated = Stats.with(base, "armor", 5.0)
        assertEquals(5.0, updated["armor"])
        assertEquals(2.0, updated["luck"])

        val removed = Stats.with(base, "armor", 0.0)
        assertFalse(removed.containsKey("armor"))
        assertEquals(2.0, removed["luck"])

        // 원본은 그대로여야 한다.
        assertEquals(1.0, base["armor"])
    }

    @Test
    fun `알 수 없는 id 도 값을 보존한다`() {
        // 설정에서 스텟 정의가 잠깐 빠져도 저장된 값이 사라지면 안 된다.
        val restored = Stats.deserialize("미등록스텟=3.5;armor=1")
        assertEquals(3.5, restored["미등록스텟"])
        assertEquals(1.0, restored["armor"])
        assertTrue(Stats.serialize(restored).contains("미등록스텟=3.5"))
    }
}

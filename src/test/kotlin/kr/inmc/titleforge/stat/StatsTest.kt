package kr.inmc.titleforge.stat

import java.util.EnumMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 스텟 맵 직렬화 · 병합 검증. 저장 형식이 깨지면 전 서버 데이터가 어긋난다. */
class StatsTest {

    @Test
    fun `직렬화 후 역직렬화하면 같은 값이 나온다`() {
        val original = mapOf(
            StatType.MAX_HEALTH to 2.0,
            StatType.ATTACK_DAMAGE to 1.5,
            StatType.MOVEMENT_SPEED to 0.1,
        )
        assertEquals(original, Stats.deserialize(Stats.serialize(original)))
    }

    @Test
    fun `0 값은 저장하지 않는다`() {
        val serialized = Stats.serialize(mapOf(StatType.ARMOR to 0.0, StatType.LUCK to 1.0))
        assertFalse(serialized.contains("armor"))
        assertTrue(serialized.contains("luck"))
    }

    @Test
    fun `빈 값과 깨진 값을 안전하게 처리한다`() {
        assertTrue(Stats.deserialize(null).isEmpty())
        assertTrue(Stats.deserialize("").isEmpty())
        assertTrue(Stats.deserialize("   ").isEmpty())
        assertTrue(Stats.deserialize("없는스텟=1;=2;armor").isEmpty())
        assertEquals(mapOf(StatType.ARMOR to 3.0), Stats.deserialize("깨짐;armor=3;luck=abc"))
    }

    @Test
    fun `merge 는 같은 스텟을 더한다`() {
        val merged = Stats.merge(
            mapOf(StatType.MAX_HEALTH to 2.0, StatType.ARMOR to 1.0),
            mapOf(StatType.MAX_HEALTH to 3.0),
        )
        assertEquals(5.0, merged[StatType.MAX_HEALTH])
        assertEquals(1.0, merged[StatType.ARMOR])
    }

    @Test
    fun `합이 0 이 되면 항목을 지운다`() {
        val merged = Stats.merge(
            mapOf(StatType.ARMOR to 2.0),
            mapOf(StatType.ARMOR to -2.0),
        )
        assertFalse(merged.containsKey(StatType.ARMOR))
    }

    @Test
    fun `mergeInto 는 대상 맵에 누적한다`() {
        val target = EnumMap<StatType, Double>(StatType::class.java)
        Stats.mergeInto(target, mapOf(StatType.LUCK to 1.0))
        Stats.mergeInto(target, mapOf(StatType.LUCK to 2.0))
        assertEquals(3.0, target[StatType.LUCK])
    }

    @Test
    fun `with 는 값을 바꾸고 0 이면 제거한다`() {
        val base = mapOf(StatType.ARMOR to 1.0, StatType.LUCK to 2.0)

        val updated = Stats.with(base, StatType.ARMOR, 5.0)
        assertEquals(5.0, updated[StatType.ARMOR])
        assertEquals(2.0, updated[StatType.LUCK])

        val removed = Stats.with(base, StatType.ARMOR, 0.0)
        assertFalse(removed.containsKey(StatType.ARMOR))
        assertEquals(2.0, removed[StatType.LUCK])

        // 원본은 그대로여야 한다.
        assertEquals(1.0, base[StatType.ARMOR])
    }

    @Test
    fun `모든 스텟 id 가 고유하다`() {
        val ids = StatType.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { StatType.of(it) != null })
    }
}

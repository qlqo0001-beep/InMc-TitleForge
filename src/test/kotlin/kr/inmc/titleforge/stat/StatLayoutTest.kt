package kr.inmc.titleforge.stat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 스텟 편집 GUI 슬롯 배치 검증.
 *
 * GUI 는 서버 없이 띄울 수 없으므로, 화면이 깨질 수 있는 계산 부분만 떼어내 여기서 검증한다.
 */
class StatLayoutTest {

    private val layout = StatLayout.compute()

    @Test
    fun `모든 스텟이 정확히 한 번씩 배치된다`() {
        assertTrue(layout.overflow.isEmpty(), "배치되지 못한 스텟: ${layout.overflow}")
        assertEquals(StatType.entries.size, layout.statSlots.size)
        assertEquals(StatType.entries.toSet(), layout.statSlots.keys.toSet())
    }

    @Test
    fun `슬롯이 겹치지 않는다`() {
        val slots = layout.statSlots.values + layout.categorySlots.values
        assertEquals(slots.size, slots.toSet().size, "중복 슬롯: $slots")
    }

    @Test
    fun `헤더 · 푸터 슬롯과 충돌하지 않는다`() {
        val chrome = setOf(
            StatLayout.SLOT_BACK,
            StatLayout.SLOT_SUMMARY,
            StatLayout.SLOT_RESET_ALL,
            StatLayout.SLOT_HELP,
            StatLayout.SLOT_LEGEND,
            StatLayout.SLOT_FOOTER_BACK,
        )
        val used = layout.statSlots.values.toSet() + layout.categorySlots.values.toSet()
        assertTrue(used.intersect(chrome).isEmpty(), "고정 버튼과 충돌: ${used.intersect(chrome)}")
    }

    @Test
    fun `모든 슬롯이 창 범위 안에 있다`() {
        val used = layout.statSlots.values + layout.categorySlots.values
        assertTrue(used.all { it in 0 until StatLayout.SIZE }, "범위를 벗어난 슬롯: $used")
    }

    @Test
    fun `분류 라벨은 각 행의 첫 칸이다`() {
        for ((category, slot) in layout.categorySlots) {
            assertEquals(0, slot % StatLayout.COLUMNS, "$category 라벨이 행 첫 칸이 아닙니다")
            assertTrue(slot / StatLayout.COLUMNS in StatLayout.CATEGORY_ROWS)
        }
    }

    @Test
    fun `같은 분류의 스텟은 라벨 오른쪽에 이어서 놓인다`() {
        for ((category, labelSlot) in layout.categorySlots) {
            val stats = category.stats().take(StatLayout.MAX_PER_ROW)
            stats.forEachIndexed { index, stat ->
                assertEquals(labelSlot + 1 + index, layout.statSlots[stat], "${stat.id} 위치")
            }
        }
    }

    @Test
    fun `역방향 조회가 정방향과 일치한다`() {
        for ((stat, slot) in layout.statSlots) {
            assertEquals(stat, layout.bySlot[slot])
        }
    }

    @Test
    fun `한 분류가 여덟 개를 넘으면 다음 행으로 이어진다`() {
        val many = StatType.entries.take(StatLayout.MAX_PER_ROW + 2)
        val result = StatLayout.compute(
            categories = listOf(StatCategory.COMBAT),
            statsOf = { many },
        )
        assertTrue(result.overflow.isEmpty())
        val rows = many.map { result.statSlots.getValue(it) / StatLayout.COLUMNS }.toSet()
        assertEquals(2, rows.size, "8개를 넘으면 두 행에 걸쳐야 합니다")
    }

    @Test
    fun `자리가 없으면 배치 대신 overflow 로 보고한다`() {
        // 분류 행(4줄) × 8칸 = 32칸을 초과하는 상황
        val overflowing = List(40) { StatType.entries[it % StatType.entries.size] }
        val result = StatLayout.compute(
            categories = listOf(StatCategory.COMBAT),
            statsOf = { overflowing },
        )
        assertTrue(result.overflow.isNotEmpty(), "자리가 없는데도 overflow 가 비어 있습니다")
    }
}

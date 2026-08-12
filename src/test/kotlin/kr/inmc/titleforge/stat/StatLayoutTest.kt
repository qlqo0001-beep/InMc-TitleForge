package kr.inmc.titleforge.stat

import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 스텟 편집 GUI 슬롯 배치 검증.
 *
 * GUI 는 서버 없이 띄울 수 없으므로, 화면이 깨질 수 있는 계산 부분만 떼어내 여기서 검증한다.
 * MMOItems 스텟을 전부 켜면 60종이 넘으므로 **페이지 계산이 특히 중요하다.**
 */
class StatLayoutTest {

    private val registry = StatRegistry(Logger.getLogger("test"))

    private fun statsOf(category: StatCategory) = registry.byCategory(category)

    private fun layout(page: Int = 0) = StatLayout.compute(page) { statsOf(it) }

    private fun allPages(): List<StatLayout.Layout> {
        val total = layout().pageCount
        return (0 until total).map { layout(it) }
    }

    @Test
    fun `모든 스텟이 어느 한 페이지에 정확히 한 번 배치된다`() {
        val placed = allPages().flatMap { it.statSlots.keys }
        assertEquals(registry.all().size, placed.size, "중복 또는 누락: $placed")
        assertEquals(registry.all().toSet(), placed.toSet())
    }

    @Test
    fun `페이지마다 슬롯이 겹치지 않는다`() {
        for (layout in allPages()) {
            val slots = layout.statSlots.values + layout.labels.keys
            assertEquals(slots.size, slots.toSet().size, "${layout.page}페이지 중복 슬롯")
        }
    }

    @Test
    fun `헤더 · 푸터 슬롯과 충돌하지 않는다`() {
        val chrome = setOf(
            StatLayout.SLOT_BACK,
            StatLayout.SLOT_SUMMARY,
            StatLayout.SLOT_RESET_ALL,
            StatLayout.SLOT_HELP,
            StatLayout.SLOT_PREV,
            StatLayout.SLOT_LEGEND,
            StatLayout.SLOT_NEXT,
            StatLayout.SLOT_FOOTER_BACK,
        )
        for (layout in allPages()) {
            val used = layout.statSlots.values.toSet() + layout.labels.keys
            assertTrue(used.intersect(chrome).isEmpty(), "${layout.page}페이지가 고정 버튼과 충돌")
        }
    }

    @Test
    fun `모든 슬롯이 창 범위 안에 있다`() {
        for (layout in allPages()) {
            val used = layout.statSlots.values + layout.labels.keys
            assertTrue(used.all { it in 0 until StatLayout.SIZE }, "${layout.page}페이지 범위 초과")
        }
    }

    @Test
    fun `분류 라벨은 각 행의 첫 칸이다`() {
        for (layout in allPages()) {
            for (slot in layout.labels.keys) {
                assertEquals(0, slot % StatLayout.COLUMNS, "라벨이 행 첫 칸이 아닙니다: $slot")
                assertTrue(slot / StatLayout.COLUMNS in StatLayout.CATEGORY_ROWS)
            }
        }
    }

    @Test
    fun `한 행에는 최대 여덟 개까지만 놓인다`() {
        for (layout in allPages()) {
            val perRow = layout.statSlots.values.groupBy { it / StatLayout.COLUMNS }
            for ((row, slots) in perRow) {
                assertTrue(slots.size <= StatLayout.MAX_PER_ROW, "$row 행에 ${slots.size}개")
            }
        }
    }

    @Test
    fun `한 행의 스텟은 같은 분류다`() {
        for (layout in allPages()) {
            val byRow = layout.statSlots.entries.groupBy { it.value / StatLayout.COLUMNS }
            for ((row, entries) in byRow) {
                val label = layout.labels[row * StatLayout.COLUMNS]
                assertTrue(label != null, "$row 행에 라벨이 없습니다")
                assertTrue(
                    entries.all { it.key.category == label.category },
                    "$row 행에 다른 분류가 섞였습니다",
                )
            }
        }
    }

    @Test
    fun `역방향 조회가 정방향과 일치한다`() {
        for (layout in allPages()) {
            for ((stat, slot) in layout.statSlots) {
                assertEquals(stat, layout.bySlot[slot])
            }
        }
    }

    @Test
    fun `여덟 개를 넘는 분류는 이어지는 행으로 나뉜다`() {
        val many = registry.all().take(StatLayout.MAX_PER_ROW + 2)
        val rows = StatLayout.rows(listOf(StatCategory.COMBAT)) { many }
        assertEquals(2, rows.size)
        assertEquals(StatLayout.MAX_PER_ROW, rows[0].stats.size)
        assertEquals(2, rows[1].stats.size)
        assertTrue(!rows[0].continuation)
        assertTrue(rows[1].continuation, "이어진 행은 continuation 이어야 합니다")
    }

    @Test
    fun `한 페이지를 넘으면 페이지가 늘어난다`() {
        val total = layout().pageCount
        assertTrue(total >= 1)

        // 페이지 수를 정하는 것은 스텟 **개수**가 아니라 **행 수**다.
        // 한 분류가 8개를 넘으면 그 분류만으로 여러 행을 차지하므로,
        // 스텟 총합이 한 페이지 정원보다 적어도 페이지가 늘어날 수 있다.
        val rows = StatLayout.rows { statsOf(it) }
        assertEquals(StatLayout.pageCount(rows.size), total)
        if (rows.size > StatLayout.ROWS_PER_PAGE) {
            assertTrue(total > 1, "행이 ${rows.size}개면 페이지가 늘어나야 합니다")
        }
    }

    @Test
    fun `페이지 범위를 벗어나면 보정된다`() {
        val total = layout().pageCount
        assertEquals(0, layout(-5).page)
        assertEquals(total - 1, layout(total + 10).page)
    }

    @Test
    fun `첫 페이지와 마지막 페이지의 이동 가능 여부가 맞다`() {
        val total = layout().pageCount
        assertTrue(!layout(0).hasPrev)
        assertTrue(!layout(total - 1).hasNext)
        if (total > 1) {
            assertTrue(layout(0).hasNext)
            assertTrue(layout(total - 1).hasPrev)
        }
    }

    @Test
    fun `빈 목록도 한 페이지로 처리한다`() {
        val empty = StatLayout.compute(0, StatCategory.entries) { emptyList() }
        assertEquals(1, empty.pageCount)
        assertTrue(empty.statSlots.isEmpty())
        assertTrue(empty.labels.isEmpty())
    }
}

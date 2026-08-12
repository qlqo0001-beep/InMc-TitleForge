package kr.inmc.titleforge.stat

/**
 * 스텟 편집 GUI 의 슬롯 배치 계산.
 *
 * Bukkit 에 전혀 의존하지 않는 순수 로직이라 단위 테스트로 검증한다
 * (`src/test/kotlin/.../StatLayoutTest.kt`).
 *
 * 배치 규칙
 * - 6줄(54칸) 고정. 0행은 헤더, 5행은 안내/이동 버튼.
 * - 1~4행이 분류 영역. 각 행의 첫 칸(열 0)은 분류 라벨, 열 1~8 에 스텟이 들어간다.
 * - 한 분류의 스텟이 8개를 넘으면 다음 행으로 이어지며, 이어진 행의 라벨은 "계속" 표시가 붙는다.
 * - 행이 4줄을 넘으면 **페이지**로 넘어간다. MMOItems 스텟을 전부 켜면 60종이 넘으므로
 *   페이지 없이는 담을 수 없다.
 */
object StatLayout {

    const val ROWS = 6
    const val COLUMNS = 9
    const val SIZE = ROWS * COLUMNS

    const val HEADER_ROW = 0
    const val FOOTER_ROW = ROWS - 1

    /** 분류에 사용할 수 있는 행 번호. */
    val CATEGORY_ROWS: List<Int> = ((HEADER_ROW + 1) until FOOTER_ROW).toList()

    /** 한 행에 들어갈 수 있는 스텟 수 (열 0 은 라벨). */
    const val MAX_PER_ROW = COLUMNS - 1

    /** 한 페이지에 들어가는 행 수. */
    val ROWS_PER_PAGE = CATEGORY_ROWS.size

    /** 한 페이지에 배치 가능한 최대 스텟 수. */
    val MAX_PER_PAGE = ROWS_PER_PAGE * MAX_PER_ROW

    // 헤더 슬롯
    const val SLOT_BACK = 0
    const val SLOT_SUMMARY = 4
    const val SLOT_RESET_ALL = 8

    // 푸터 슬롯
    val SLOT_HELP = FOOTER_ROW * COLUMNS
    val SLOT_PREV = FOOTER_ROW * COLUMNS + 2
    val SLOT_LEGEND = FOOTER_ROW * COLUMNS + 4
    val SLOT_NEXT = FOOTER_ROW * COLUMNS + 6
    val SLOT_FOOTER_BACK = FOOTER_ROW * COLUMNS + 8

    /** 한 행에 들어갈 내용. */
    class RowSpec(
        val category: StatCategory,
        val stats: List<Stat>,
        /** 같은 분류가 앞 행에서 이어진 행인지. */
        val continuation: Boolean,
    )

    /** 라벨 칸에 그릴 내용. */
    class Label(val category: StatCategory, val continuation: Boolean)

    class Layout(
        val page: Int,
        val pageCount: Int,
        /** 라벨 슬롯 → 라벨 */
        val labels: Map<Int, Label>,
        /** 스텟 → 아이콘 슬롯 */
        val statSlots: Map<Stat, Int>,
    ) {
        /** 슬롯 → 스텟 역방향 조회 (클릭 처리용). */
        val bySlot: Map<Int, Stat> = statSlots.entries.associate { (stat, slot) -> slot to stat }

        val hasPrev: Boolean get() = page > 0
        val hasNext: Boolean get() = page < pageCount - 1
    }

    /**
     * 분류별 스텟을 행 단위로 쪼갠다. 페이지 계산의 기준이 된다.
     */
    fun rows(
        categories: List<StatCategory> = StatCategory.entries,
        statsOf: (StatCategory) -> List<Stat>,
    ): List<RowSpec> {
        val result = ArrayList<RowSpec>()
        for (category in categories) {
            val stats = statsOf(category)
            if (stats.isEmpty()) continue
            stats.chunked(MAX_PER_ROW).forEachIndexed { index, chunk ->
                result += RowSpec(category, chunk, continuation = index > 0)
            }
        }
        return result
    }

    fun pageCount(rowCount: Int): Int =
        if (rowCount == 0) 1 else (rowCount + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE

    /**
     * @param page 0부터. 범위를 벗어나면 가장 가까운 페이지로 보정한다.
     */
    fun compute(
        page: Int = 0,
        categories: List<StatCategory> = StatCategory.entries,
        statsOf: (StatCategory) -> List<Stat>,
    ): Layout {
        val allRows = rows(categories, statsOf)
        val pageCount = pageCount(allRows.size)
        val current = page.coerceIn(0, pageCount - 1)

        val labels = LinkedHashMap<Int, Label>()
        val statSlots = LinkedHashMap<Stat, Int>()

        val from = current * ROWS_PER_PAGE
        val slice = allRows.drop(from).take(ROWS_PER_PAGE)

        slice.forEachIndexed { index, row ->
            val guiRow = CATEGORY_ROWS[index]
            labels[guiRow * COLUMNS] = Label(row.category, row.continuation)
            row.stats.forEachIndexed { column, stat ->
                statSlots[stat] = guiRow * COLUMNS + 1 + column
            }
        }

        return Layout(current, pageCount, labels, statSlots)
    }
}

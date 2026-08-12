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
 * - 한 분류의 스텟이 8개를 넘으면 라벨 없이 다음 행으로 이어진다.
 * - 행이 모자라면 배치하지 못한 스텟을 [Layout.overflow] 로 돌려준다(경고 + 페이지 안내용).
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

    /** 한 페이지에 배치 가능한 최대 스텟 수. */
    const val MAX_PER_PAGE = 4 * MAX_PER_ROW

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

    class Layout(
        /** 분류 → 라벨 슬롯 */
        val categorySlots: Map<StatCategory, Int>,
        /** 스텟 → 아이콘 슬롯 */
        val statSlots: Map<Stat, Int>,
        /** 자리가 없어 이 페이지에 배치하지 못한 스텟 */
        val overflow: List<Stat>,
    ) {
        /** 슬롯 → 스텟 역방향 조회 (클릭 처리용). */
        val bySlot: Map<Int, Stat> = statSlots.entries.associate { (stat, slot) -> slot to stat }
    }

    /**
     * @param statsOf 분류별 스텟 목록 (레지스트리에서 주입)
     */
    fun compute(
        categories: List<StatCategory> = StatCategory.entries,
        statsOf: (StatCategory) -> List<Stat>,
    ): Layout {
        val categorySlots = LinkedHashMap<StatCategory, Int>()
        val statSlots = LinkedHashMap<Stat, Int>()
        val overflow = ArrayList<Stat>()

        val availableRows = ArrayDeque(CATEGORY_ROWS)

        for (category in categories) {
            val stats = statsOf(category)
            if (stats.isEmpty()) continue

            val row = availableRows.removeFirstOrNull()
            if (row == null) {
                overflow += stats
                continue
            }
            categorySlots[category] = row * COLUMNS

            var index = 0
            var currentRow = row
            for (stat in stats) {
                if (index == MAX_PER_ROW) {
                    // 8개를 넘으면 라벨 없이 다음 행으로 이어붙인다.
                    val nextRow = availableRows.removeFirstOrNull()
                    if (nextRow == null) {
                        overflow += stat
                        continue
                    }
                    currentRow = nextRow
                    index = 0
                }
                statSlots[stat] = currentRow * COLUMNS + 1 + index
                index++
            }
        }

        return Layout(categorySlots, statSlots, overflow)
    }
}

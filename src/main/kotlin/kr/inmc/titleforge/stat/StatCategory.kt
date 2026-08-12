package kr.inmc.titleforge.stat

import org.bukkit.Material

/**
 * 스텟 분류. 관리자 GUI 에서 한 줄씩 배치되며, 색과 아이콘이 그대로 라벨에 쓰인다.
 *
 * 새 분류를 추가하면 [StatLayout] 이 자동으로 행을 배정한다.
 */
enum class StatCategory(
    val id: String,
    val display: String,
    val icon: Material,
    val color: String,
) {
    COMBAT("combat", "전투", Material.IRON_SWORD, "<red>"),
    DEFENSE("defense", "방어", Material.SHIELD, "<blue>"),
    MOBILITY("mobility", "이동", Material.FEATHER, "<aqua>"),
    UTILITY("utility", "유틸리티", Material.EXPERIENCE_BOTTLE, "<green>"),
    ;

    /** 이 분류에 속한 스텟 (enum 선언 순서 유지). */
    fun stats(): List<StatType> = StatType.entries.filter { it.category == this }
}

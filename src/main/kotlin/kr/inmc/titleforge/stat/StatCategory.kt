package kr.inmc.titleforge.stat

import org.bukkit.Material

/**
 * 스텟 분류. 관리자 GUI 에서 한 줄씩 배치되며, 색과 아이콘이 그대로 라벨에 쓰인다.
 *
 * 소속 스텟 목록은 [StatRegistry.byCategory] 가 돌려준다.
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
    RESOURCE("resource", "자원", Material.LAPIS_LAZULI, "<dark_aqua>"),
    WORLD("world", "상호작용", Material.IRON_PICKAXE, "<gold>"),
    UTILITY("utility", "유틸리티", Material.EXPERIENCE_BOTTLE, "<green>"),
    ;

    companion object {
        fun of(raw: String?): StatCategory? = entries.firstOrNull { it.id.equals(raw, ignoreCase = true) }
    }
}

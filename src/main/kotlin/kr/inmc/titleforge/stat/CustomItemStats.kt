package kr.inmc.titleforge.stat

/**
 * MMO 스텟을 **커스텀아이템(inmc-customitems) 능력치**로도 보내기(2026-10-09, 사용자 결정 "MI 를 쓰는 기능은 CI 로도").
 *
 * MMO 종류 스텟은 MythicLib 가 있으면 MythicLib 스텟 수정자로, 커스텀아이템이 있으면 core `CustomItemHook` 의 바깥 능력치 출처로
 * **둘 다** 간다(한 서버에 둘 다 있을 일은 거의 없다). 커스텀아이템 쪽 id 는 `stats.yml` 의 `ci-stat` 가 있으면 그것, 없으면 아래 표.
 * 표에 없는 MMO 스텟(주문 치명타·마나·스태미나·막기/회피 쿨타임 …)은 커스텀아이템에 같은 뜻이 없어 넘기지 않는다.
 */
object CustomItemStats {

    /** core `CustomItemHook.registerStatSource` 에 쓰는 출처 이름. */
    const val SOURCE = "titleforge"

    /** MMO 스텟 id → 커스텀아이템 능력치 id(`Stat.id`). */
    val DEFAULTS: Map<String, String> = mapOf(
        "CRITICAL_STRIKE_CHANCE" to "crit-chance",
        "CRITICAL_STRIKE_POWER" to "crit-power",
        "WEAPON_DAMAGE" to "damage-bonus",
        "PROJECTILE_DAMAGE" to "projectile-damage",
        "SKILL_DAMAGE" to "skill-damage",
        "PVE_DAMAGE" to "pve-damage",
        "PVP_DAMAGE" to "pvp-damage",
        "UNDEAD_DAMAGE" to "undead-damage",
        "LIFESTEAL" to "lifesteal",
        "DAMAGE_REDUCTION" to "damage-reduction",
        "PVE_DEFENSE" to "pve-defense",
        "PVP_DEFENSE" to "pvp-defense",
        "HEALTH_REGENERATION" to "health-regen",
        "BLOCK_POWER" to "block-power",
        "BLOCK_RATING" to "block-chance",
        "DODGE_RATING" to "dodge-chance",
        "COOLDOWN_REDUCTION" to "cooldown-reduction",
        "ADDITIONAL_EXPERIENCE" to "exp-bonus",
        "FIRE_DAMAGE" to "fire-damage",
        "ICE_DAMAGE" to "ice-damage",
        "LIGHTNING_DAMAGE" to "lightning-damage",
        "POISON_DAMAGE" to "poison-damage",
        "DEFENSE_FIRE" to "fire-resist",
        "DEFENSE_ICE" to "ice-resist",
        "DEFENSE_LIGHTNING" to "lightning-resist",
        "DEFENSE_POISON" to "poison-resist",
    )

    /** 이 스텟이 갈 커스텀아이템 능력치 id. 없으면 null — 그 스텟은 커스텀아이템에 넘기지 않는다. */
    fun ciStatOf(stat: Stat): String? =
        stat.ciStat?.trim()?.takeIf { it.isNotEmpty() } ?: stat.mmoStat?.let { DEFAULTS[it.trim().uppercase()] }
}

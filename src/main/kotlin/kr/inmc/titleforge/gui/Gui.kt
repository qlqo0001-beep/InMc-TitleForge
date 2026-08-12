package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.stat.StatCategory
import kr.inmc.titleforge.stat.StatType
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.text.SimpleDateFormat
import java.util.Date

/** GUI 아이템 생성 헬퍼. 라벨은 전부 messages.yml 에서 온다 (맞춤 지침 7.3-11). */
object Gui {

    private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd")

    private val DIVIDER: Component = Text.mini("<dark_gray><strikethrough>                    ")

    private const val NONE = "-"

    fun item(
        plugin: TitleForgePlugin,
        path: String,
        material: Material,
        glow: Boolean = false,
        extraLore: List<Component> = emptyList(),
        vararg placeholders: Pair<String, Any?>,
    ): ItemStack {
        val name = plugin.messages.component("$path.name", *placeholders)
        val lore = plugin.messages.list("$path.lore").map { Text.mini(it, *placeholders) } + extraLore
        return Items.of(material, name, lore, glow)
    }

    /** 칭호/인장 아이콘. 보유 여부와 장착 상태를 로어로 표현한다. */
    fun badgeIcon(
        plugin: TitleForgePlugin,
        profile: PlayerProfile?,
        badge: Badge,
        owned: Boolean,
        locked: Boolean,
        equippedSlots: List<EquipSlot>,
        adminMode: Boolean = false,
    ): ItemStack {
        val settings = plugin.settings.gui
        val messages = plugin.messages
        val lore = ArrayList<Component>()

        lore += Text.mini("${badge.rarity.color}[${badge.rarity.display}]<gray> <id>", "id" to badge.id)
        badge.lore.forEach { lore += Text.mini(it) }

        if (badge.equipStats.isNotEmpty()) {
            lore += Component.empty()
            lore += messages.component("gui.lore.equip-stats")
            lore += statLines(badge.equipStats)
        }
        if (badge.ownStats.isNotEmpty()) {
            lore += Component.empty()
            lore += messages.component("gui.lore.own-stats")
            lore += statLines(badge.ownStats)
        }

        lore += Component.empty()
        when {
            adminMode -> {
                lore += messages.component("gui.lore.admin-hint")
                if (badge.permission.isNotBlank()) {
                    lore += messages.component("gui.lore.permission", "permission" to badge.permission)
                }
                if (badge.hidden) lore += messages.component("gui.lore.hidden")
            }

            !owned -> {
                lore += messages.component("gui.lore.not-owned")
                if (badge.permission.isNotBlank()) {
                    lore += messages.component("gui.lore.permission", "permission" to badge.permission)
                }
            }

            locked -> lore += messages.component("gui.lore.locked")

            else -> {
                val date = profile?.obtainedAt(badge.type, badge.id) ?: 0L
                if (date > 0L) {
                    lore += messages.component("gui.lore.obtained", "date" to DATE_FORMAT.format(Date(date)))
                }
                equippedSlots.forEach { slot ->
                    lore += messages.component("gui.lore.equipped", "slot" to slot.display)
                }
                lore += Component.empty()
                if (badge.type == BadgeType.TITLE) {
                    lore += messages.component("gui.lore.click-display")
                    lore += messages.component("gui.lore.click-stat")
                } else {
                    lore += messages.component("gui.lore.click-seal")
                }
                if (equippedSlots.isNotEmpty()) lore += messages.component("gui.lore.click-unequip")
            }
        }

        val material = when {
            adminMode || owned -> badge.icon
            else -> settings.iconLocked
        }
        return Items.of(material, badge.nameComponent, lore, glow = equippedSlots.isNotEmpty())
    }

    /**
     * 스텟 편집 아이콘. 관리자가 문서를 찾지 않아도 되도록
     * 분류 · 바닐라 Attribute 키 · 적용 방식 · 기본값 · 권장 범위를 모두 로어에 담고,
     * **장착/보유 값을 함께** 보여준다.
     */
    fun statIcon(
        plugin: TitleForgePlugin,
        stat: StatType,
        equipValue: Double,
        ownValue: Double,
    ): ItemStack {
        val messages = plugin.messages
        val lore = ArrayList<Component>()

        lore += Text.mini("${stat.category.color}[${stat.category.display}]")
        if (stat.vanilla) {
            lore += messages.component("gui.lore.stat-attribute", "key" to "minecraft:${stat.attributeKey}")
        } else {
            lore += messages.component("gui.lore.stat-custom")
        }

        lore += DIVIDER
        lore += messages.component(
            "gui.lore.stat-equip-value",
            "value" to if (equipValue == 0.0) NONE else stat.format(equipValue),
        )
        lore += messages.component(
            "gui.lore.stat-own-value",
            "value" to if (ownValue == 0.0) NONE else stat.format(ownValue),
        )

        lore += DIVIDER
        lore += messages.component("gui.lore.stat-operation", "value" to stat.operationDisplay)
        if (stat.vanillaBase != null) {
            lore += messages.component(
                "gui.lore.stat-base",
                "value" to stat.format(stat.vanillaBase).removePrefix("+"),
            )
        }
        lore += messages.component("gui.lore.stat-range", "value" to stat.softRangeDisplay())
        if (stat.suffix == "%" || stat.displayScale != 1.0) {
            lore += messages.component("gui.lore.stat-unit-hint")
        }

        lore += DIVIDER
        lore += messages.component("gui.lore.stat-click-equip")
        lore += messages.component("gui.lore.stat-click-own")
        if (equipValue != 0.0) lore += messages.component("gui.lore.stat-click-remove-equip")
        if (ownValue != 0.0) lore += messages.component("gui.lore.stat-click-remove-own")

        val configured = equipValue != 0.0 || ownValue != 0.0
        return Items.of(stat.icon, statTitle(plugin, stat, configured), lore, glow = configured)
    }

    private fun statTitle(plugin: TitleForgePlugin, stat: StatType, configured: Boolean): Component =
        plugin.messages.component(
            if (configured) "gui.lore.stat-name-set" else "gui.lore.stat-name-unset",
            "stat" to stat.display,
            "id" to stat.id,
        )

    /** 분류 라벨. 해당 분류의 장착/보유 합계를 함께 보여준다. */
    fun categoryLabel(
        plugin: TitleForgePlugin,
        category: StatCategory,
        equipStats: Map<StatType, Double>,
        ownStats: Map<StatType, Double>,
    ): ItemStack {
        val stats = category.stats()
        val configured = stats.count { (equipStats[it] ?: 0.0) != 0.0 || (ownStats[it] ?: 0.0) != 0.0 }
        val lore = ArrayList<Component>()
        lore += plugin.messages.component(
            "gui.lore.category-summary",
            "configured" to configured,
            "total" to stats.size,
        )
        stats.forEach { stat ->
            val equip = equipStats[stat] ?: 0.0
            val own = ownStats[stat] ?: 0.0
            if (equip == 0.0 && own == 0.0) return@forEach
            lore += Text.mini(
                "<dark_gray>  ▪ <gray><stat> <dark_gray>| <aqua><equip> <dark_gray>/ <green><own>",
                "stat" to stat.display,
                "equip" to stat.format(equip),
                "own" to stat.format(own),
            )
        }
        return Items.of(
            category.icon,
            Text.mini("${category.color}<bold><name></bold>", "name" to category.display),
            lore,
        )
    }

    fun statLines(stats: Map<StatType, Double>): List<Component> =
        StatType.entries.mapNotNull { stat ->
            val value = stats[stat] ?: return@mapNotNull null
            if (value == 0.0) return@mapNotNull null
            Text.mini(
                "<dark_gray>  ▪ <gray><stat> <green><value>",
                "stat" to stat.display,
                "value" to stat.format(value),
            )
        }
}

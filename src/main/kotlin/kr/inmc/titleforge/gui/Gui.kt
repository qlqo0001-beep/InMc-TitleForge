package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
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

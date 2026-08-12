package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player

/** 내 정보. 닉네임 확인·변경과 3개 장착 슬롯, 스텟 총합을 보여준다. */
class ProfileMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val target: Player,
) : Menu(plugin, viewer, rows = 5) {

    private val self: Boolean get() = viewer.uniqueId == target.uniqueId

    override fun title(): Component = plugin.messages.component("gui.profile-title", "player" to target.name)

    override fun render() {
        val profile = plugin.profiles.of(target)
        val display = plugin.nameDisplay

        // ── 닉네임 카드 ──
        val nicknameLore = ArrayList<Component>()
        nicknameLore += plugin.messages.component(
            "gui.lore.nickname-current",
            "nickname" to display.nicknameText(profile, target.name),
        )
        nicknameLore += plugin.messages.component("gui.lore.nickname-real", "name" to target.name)
        if (self && plugin.settings.nickname.enabled) {
            val remaining = profile?.let { plugin.nicknames.cooldownRemaining(it) } ?: 0L
            nicknameLore += Component.empty()
            nicknameLore += if (remaining > 0) {
                plugin.messages.component("gui.lore.nickname-cooldown", "time" to Text.duration(remaining))
            } else {
                plugin.messages.component("gui.lore.nickname-click")
            }
        }
        button(
            13,
            Items.head(target, display.nameplate(profile, target.name), nicknameLore),
        ) {
            if (self && plugin.settings.nickname.enabled) {
                plugin.nicknames.requestChange(viewer)
            }
        }

        // ── 수집 현황 ──
        button(
            20,
            Gui.item(
                plugin, "gui.button.collection-title", plugin.settings.gui.iconTitle,
                extraLore = milestoneLore(profile?.count(BadgeType.TITLE) ?: 0),
                placeholders = arrayOf(
                    "owned" to (profile?.count(BadgeType.TITLE) ?: 0),
                    "total" to plugin.badges.count(BadgeType.TITLE),
                ),
            ),
        )
        button(
            24,
            Gui.item(
                plugin, "gui.button.collection-seal", plugin.settings.gui.iconSeal,
                placeholders = arrayOf(
                    "owned" to (profile?.count(BadgeType.SEAL) ?: 0),
                    "total" to plugin.badges.count(BadgeType.SEAL),
                ),
            ),
        )

        // ── 스텟 총합 ──
        val statLore = ArrayList<Component>()
        val total = profile?.totalStats ?: emptyMap()
        if (total.isEmpty()) {
            statLore += plugin.messages.component("gui.lore.no-stats")
        } else {
            statLore += Gui.statLines(plugin, total)
            statLore += Component.empty()
            statLore += plugin.messages.component("gui.lore.stat-breakdown")
            for (stat in plugin.stats.all()) {
                val equip = profile?.equipStats?.get(stat.id) ?: 0.0
                val own = profile?.ownStats?.get(stat.id) ?: 0.0
                if (equip == 0.0 && own == 0.0) continue
                statLore += Text.mini(
                    "<dark_gray>  ▪ <gray><stat><dark_gray>: <white>장착 <aqua><equip> <dark_gray>/ <white>보유 <green><own>",
                    "stat" to stat.display,
                    "equip" to stat.format(equip),
                    "own" to stat.format(own),
                )
            }
        }
        button(22, Gui.item(plugin, "gui.button.stat-summary", Material.NETHER_STAR, extraLore = statLore))

        // ── 장착 슬롯 3종 ──
        slotButton(29, EquipSlot.DISPLAY, display.titleComponent(profile))
        slotButton(31, EquipSlot.STAT, display.statTitleComponent(profile))
        slotButton(33, EquipSlot.SEAL, display.sealComponent(profile))

        button(40, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) { MainMenu(plugin, viewer).openLater() }
        fill()
    }

    private fun slotButton(slot: Int, equipSlot: EquipSlot, current: Component) {
        val icon = when (equipSlot) {
            EquipSlot.SEAL -> plugin.settings.gui.iconSeal
            else -> plugin.settings.gui.iconEquipped
        }
        button(
            slot,
            Gui.item(
                plugin, "gui.button.slot-${equipSlot.id}", icon,
                extraLore = listOf(
                    plugin.messages.component("gui.lore.slot-current", "value" to current),
                ) + if (self) listOf(plugin.messages.component("gui.lore.slot-open")) else emptyList(),
            ),
        ) {
            if (self) BadgeListMenu(plugin, viewer, equipSlot.type).openLater()
        }
    }

    private fun milestoneLore(owned: Int): List<Component> {
        val milestones = plugin.settings.title.milestones
        if (milestones.isEmpty()) return emptyList()
        val lore = ArrayList<Component>()
        lore += Component.empty()
        lore += plugin.messages.component("gui.lore.milestones")
        for ((threshold, stats) in milestones) {
            val reached = owned >= threshold
            lore += Text.mini(
                if (reached) "<green>  ✔ <gray><count>개 <dark_gray>- <white><stats>" else "<dark_gray>  ✖ <gray><count>개 <dark_gray>- <gray><stats>",
                "count" to threshold,
                "stats" to stats.entries.joinToString(", ") { (id, value) ->
                    val stat = plugin.stats.of(id)
                    if (stat == null) "$id $value" else "${stat.display} ${stat.format(value)}"
                },
            )
        }
        return lore
    }
}

package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.StatType
import kr.inmc.titleforge.stat.Stats
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** 칭호/인장 개별 편집 창. */
class BadgeEditMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private var badge: Badge,
) : Menu(plugin, viewer, rows = 5) {

    override fun title(): Component = plugin.messages.component("gui.edit-title", "id" to badge.id)

    private fun update(updated: Badge) {
        badge = updated
        plugin.badgeService.persist(updated)
        redraw()
    }

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }

        button(4, Gui.badgeIcon(plugin, null, badge, owned = true, locked = false, equippedSlots = emptyList(), adminMode = true))

        button(
            10,
            Gui.item(plugin, "gui.button.rename", Material.NAME_TAG, placeholders = arrayOf("value" to badge.displayName)),
        ) {
            val current = badge
            plugin.anvilInput.prompt(viewer, current.plainName) { input ->
                if (input != null) {
                    update(current.copy(displayName = input))
                    plugin.messages.send(
                        viewer, "badge.edited",
                        "type" to current.type.display, "id" to current.id, "field" to "이름",
                    )
                }
                BadgeEditMenu(plugin, viewer, badge).open()
            }
        }

        button(
            11,
            Gui.item(
                plugin, "gui.button.rarity", Material.NETHER_STAR,
                placeholders = arrayOf("value" to "${badge.rarity.color}${badge.rarity.display}"),
            ),
        ) {
            update(badge.copy(rarity = badge.rarity.next()))
        }

        button(
            12,
            Gui.item(plugin, "gui.button.icon", badge.icon, placeholders = arrayOf("value" to badge.icon.name)),
        ) {
            val held = viewer.inventory.itemInMainHand
            if (held.type.isItem && held.type != Material.AIR) {
                update(badge.copy(icon = held.type))
            }
        }

        button(
            13,
            Gui.item(
                plugin, "gui.button.hidden", if (badge.hidden) Material.GRAY_DYE else Material.LIME_DYE,
                placeholders = arrayOf("value" to if (badge.hidden) "숨김" else "공개"),
            ),
        ) {
            update(badge.copy(hidden = !badge.hidden))
        }

        button(
            14,
            Gui.item(plugin, "gui.button.order", Material.COMPARATOR, placeholders = arrayOf("value" to badge.order)),
        ) { event ->
            val delta = when {
                event.click == ClickType.SHIFT_LEFT -> 10
                event.click == ClickType.SHIFT_RIGHT -> -10
                event.click.isRightClick -> -1
                else -> 1
            }
            update(badge.copy(order = badge.order + delta))
        }

        button(
            15,
            Gui.item(
                plugin, "gui.button.permission", Material.SHIELD,
                placeholders = arrayOf("value" to badge.permission.ifBlank { "-" }),
            ),
        ) { event ->
            if (event.click.isRightClick) {
                update(badge.copy(permission = ""))
                return@button
            }
            val current = badge
            plugin.anvilInput.prompt(viewer, current.permission.ifBlank { "titleforge.badge.${current.id}" }) { input ->
                if (input != null) update(current.copy(permission = input.trim()))
                BadgeEditMenu(plugin, viewer, badge).open()
            }
        }

        if (badge.type == BadgeType.TITLE) {
            button(
                29,
                Gui.item(
                    plugin, "gui.button.stats-equip", Material.DIAMOND_SWORD,
                    extraLore = Gui.statLines(badge.equipStats),
                ),
            ) {
                StatEditMenu(plugin, viewer, badge, equipSide = true).open()
            }
            button(
                31,
                Gui.item(
                    plugin, "gui.button.stats-own", Material.BOOKSHELF,
                    extraLore = Gui.statLines(badge.ownStats),
                ),
            ) {
                StatEditMenu(plugin, viewer, badge, equipSide = false).open()
            }
        } else {
            button(30, Gui.item(plugin, "gui.button.seal-no-stat", Material.BARRIER))
        }

        button(33, Gui.item(plugin, "gui.button.delete", Material.LAVA_BUCKET)) {
            val current = badge
            ConfirmMenu(
                plugin, viewer,
                description = plugin.messages.component(
                    "gui.lore.delete-target",
                    "type" to current.type.display,
                    "name" to current.nameComponent,
                ),
                onConfirm = {
                    plugin.badgeService.delete(current.type, current.id)
                    plugin.messages.send(
                        viewer, "badge.deleted",
                        "type" to current.type.display, "id" to current.id,
                    )
                    AdminMenu(plugin, viewer, current.type).open()
                },
                onCancel = { BadgeEditMenu(plugin, viewer, current).open() },
            ).open()
        }

        button(40, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            AdminMenu(plugin, viewer, badge.type).open()
        }
        fill()
    }
}

/** 장착/보유 스텟 수치 조정 창. */
class StatEditMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private var badge: Badge,
    private val equipSide: Boolean,
) : Menu(plugin, viewer, rows = 6) {

    override fun title(): Component = plugin.messages.component(
        if (equipSide) "gui.stat-equip-title" else "gui.stat-own-title",
        "id" to badge.id,
    )

    private fun stats(): Map<StatType, Double> = if (equipSide) badge.equipStats else badge.ownStats

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }

        StatType.entries.forEachIndexed { index, stat ->
            val value = stats()[stat] ?: 0.0
            button(
                index,
                Gui.item(
                    plugin,
                    if (stat.vanilla) "gui.button.stat-entry" else "gui.button.stat-entry-custom",
                    if (value == 0.0) Material.GRAY_DYE else Material.LIME_DYE,
                    placeholders = arrayOf(
                        "stat" to stat.display,
                        "id" to stat.id,
                        "value" to stat.format(value),
                    ),
                ),
            ) { event ->
                val step = when {
                    event.click == ClickType.MIDDLE -> return@button reset(stat)
                    event.click == ClickType.SHIFT_LEFT -> 5.0
                    event.click == ClickType.SHIFT_RIGHT -> -5.0
                    event.click.isRightClick -> -0.5
                    else -> 0.5
                }
                apply(stat, (stats()[stat] ?: 0.0) + step)
            }
        }

        button(size - 5, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            BadgeEditMenu(plugin, viewer, badge).open()
        }
        fill()
    }

    private fun reset(stat: StatType) = apply(stat, 0.0)

    private fun apply(stat: StatType, value: Double) {
        val rounded = Math.round(value * 100.0) / 100.0
        val updated = if (equipSide) {
            badge.copy(equipStats = Stats.with(badge.equipStats, stat, rounded))
        } else {
            badge.copy(ownStats = Stats.with(badge.ownStats, stat, rounded))
        }
        badge = updated
        plugin.badgeService.persist(updated)
        redraw()
    }
}

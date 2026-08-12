package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 칭호/인장 개별 편집 창.
 *
 * 편집 대상은 ID 로만 들고 있고 렌더링 때마다 레지스트리에서 최신 정의를 다시 읽는다.
 * 다른 관리자가 동시에 수정해도 옛 스냅샷으로 덮어쓰지 않는다.
 */
class BadgeEditMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val type: BadgeType,
    private val badgeId: String,
) : Menu(plugin, viewer, rows = 5) {

    constructor(plugin: TitleForgePlugin, viewer: Player, badge: Badge) :
        this(plugin, viewer, badge.type, badge.id)

    private fun badge(): Badge? = plugin.badges.get(type, badgeId)

    override fun title(): Component = plugin.messages.component("gui.edit-title", "id" to badgeId)

    private fun update(updated: Badge) {
        plugin.badgeService.persist(updated)
        redraw()
    }

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val badge = badge() ?: run {
            plugin.messages.send(viewer, "badge.not-found", "type" to type.display, "id" to badgeId)
            AdminMenu(plugin, viewer, type).openLater()
            return
        }

        button(
            4,
            Gui.badgeIcon(plugin, null, badge, owned = true, locked = false, equippedSlots = emptyList(), adminMode = true),
        )

        button(
            10,
            Gui.item(plugin, "gui.button.rename", Material.NAME_TAG, placeholders = arrayOf("value" to badge.displayName)),
        ) {
            promptText(
                initial = badge.plainName,
                messageKey = "input.prompt-name",
            ) { input ->
                val current = badge() ?: return@promptText
                update(current.copy(displayName = input))
                plugin.messages.send(
                    viewer, "badge.edited",
                    "type" to current.type.display, "id" to current.id, "field" to "이름",
                )
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
            } else {
                plugin.messages.send(viewer, "badge.need-held-item")
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
            promptText(
                initial = badge.permission.ifBlank { "titleforge.badge.${badge.id}" },
                messageKey = "input.prompt-permission",
            ) { input ->
                val current = badge() ?: return@promptText
                update(current.copy(permission = input.trim()))
                plugin.messages.send(
                    viewer, "badge.edited",
                    "type" to current.type.display, "id" to current.id, "field" to "권한",
                )
            }
        }

        // 장착/보유 스텟은 하나의 통합 편집 창에서 다룬다.
        if (badge.type == BadgeType.TITLE) {
            val summary = ArrayList<Component>()
            summary += plugin.messages.component("gui.lore.equip-stats")
            summary += Gui.statLines(plugin, badge.equipStats).ifEmpty {
                listOf(plugin.messages.component("gui.lore.no-stats"))
            }
            summary += Component.empty()
            summary += plugin.messages.component("gui.lore.own-stats")
            summary += Gui.statLines(plugin, badge.ownStats).ifEmpty {
                listOf(plugin.messages.component("gui.lore.no-stats"))
            }
            button(
                31,
                Gui.item(plugin, "gui.button.stats-edit", Material.DIAMOND_SWORD, extraLore = summary),
            ) {
                StatEditMenu(plugin, viewer, badge.id).openLater()
            }
        } else {
            button(31, Gui.item(plugin, "gui.button.seal-no-stat", Material.BARRIER))
        }

        button(33, Gui.item(plugin, "gui.button.delete", Material.LAVA_BUCKET)) {
            ConfirmMenu(
                plugin, viewer,
                description = plugin.messages.component(
                    "gui.lore.delete-target",
                    "type" to badge.type.display,
                    "name" to badge.nameComponent,
                ),
                onConfirm = {
                    plugin.badgeService.delete(type, badgeId)
                    plugin.messages.send(viewer, "badge.deleted", "type" to type.display, "id" to badgeId)
                    AdminMenu(plugin, viewer, type).openLater()
                },
                onCancel = { openLater() },
            ).openLater()
        }

        button(40, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            AdminMenu(plugin, viewer, type).openLater()
        }
        fill()
    }

    /** 모루 입력창으로 문자열을 받고, 끝나면 이 창으로 돌아온다. */
    private fun promptText(initial: String, messageKey: String, onInput: (String) -> Unit) {
        val prompt = plugin.messages.prefix().append(plugin.messages.component(messageKey))
        plugin.anvilInput.prompt(viewer, initial, prompt) { input ->
            if (input != null) onInput(input)
            openLater()
        }
    }
}

package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/** 관리자용 칭호/인장 목록. 클릭하면 편집, Shift+우클릭이면 삭제 확인. */
class AdminMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private var type: BadgeType,
) : Menu(plugin, viewer, rows = 6) {

    private var page = 0

    private val pageSize: Int get() = plugin.settings.gui.pageSize

    private fun badges(): List<Badge> = plugin.badges.all(type)

    private fun maxPage(): Int {
        val count = badges().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    override fun title(): Component =
        plugin.messages.component("gui.admin-title", "page" to (page + 1), "max" to maxPage(), "type" to type.display)

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val list = badges()
        val max = maxPage()
        if (page >= max) page = max - 1

        list.drop(page * pageSize).take(pageSize).forEachIndexed { index, badge ->
            button(
                index,
                Gui.badgeIcon(plugin, null, badge, owned = true, locked = false, equippedSlots = emptyList(), adminMode = true),
            ) { event ->
                if (event.click == ClickType.SHIFT_RIGHT) {
                    ConfirmMenu(
                        plugin, viewer,
                        description = plugin.messages.component(
                            "gui.lore.delete-target",
                            "type" to badge.type.display,
                            "name" to badge.nameComponent,
                        ),
                        onConfirm = {
                            plugin.badgeService.delete(badge.type, badge.id)
                            plugin.messages.send(viewer, "badge.deleted", "type" to badge.type.display, "id" to badge.id)
                            AdminMenu(plugin, viewer, type).openLater()
                        },
                        onCancel = { AdminMenu(plugin, viewer, type).openLater() },
                    ).openLater()
                } else {
                    BadgeEditMenu(plugin, viewer, badge).openLater()
                }
            }
        }

        val navRow = size - 9
        if (page > 0) {
            button(navRow, Gui.item(plugin, "gui.button.prev", Material.ARROW)) {
                page--
                openLater()
            }
        }
        if (page < max - 1) {
            button(navRow + 8, Gui.item(plugin, "gui.button.next", Material.ARROW)) {
                page++
                openLater()
            }
        }

        button(navRow + 2, Gui.item(plugin, "gui.button.type-toggle", Material.COMPARATOR, placeholders = arrayOf("type" to type.display))) {
            type = if (type == BadgeType.TITLE) BadgeType.SEAL else BadgeType.TITLE
            page = 0
            openLater()
        }

        button(navRow + 4, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) { MainMenu(plugin, viewer).openLater() }

        button(navRow + 6, Gui.item(plugin, "gui.button.create", Material.WRITABLE_BOOK, placeholders = arrayOf("type" to type.display))) {
            val creating = type
            val prompt = plugin.messages.prefix().append(plugin.messages.component("input.prompt-badge-id"))
            plugin.anvilInput.prompt(viewer, "new_id", prompt) { input ->
                if (input == null) {
                    AdminMenu(plugin, viewer, creating).openLater()
                    return@prompt
                }
                val id = Badge.normalizeId(input)
                when {
                    !Badge.validId(id) -> plugin.messages.send(viewer, "badge.invalid-id")
                    plugin.badges.exists(creating, id) ->
                        plugin.messages.send(viewer, "badge.already-exists", "id" to id)

                    else -> {
                        val badge = Badge(
                            type = creating,
                            id = id,
                            displayName = "<white>[$id]",
                            icon = if (creating == BadgeType.TITLE) {
                                plugin.settings.gui.iconTitle
                            } else {
                                plugin.settings.gui.iconSeal
                            },
                        )
                        plugin.badgeService.persist(badge)
                        plugin.messages.send(
                            viewer, "badge.created",
                            "type" to creating.display, "name" to badge.nameComponent, "id" to id,
                        )
                        BadgeEditMenu(plugin, viewer, badge).openLater()
                        return@prompt
                    }
                }
                AdminMenu(plugin, viewer, creating).openLater()
            }
        }

        fill()
    }
}

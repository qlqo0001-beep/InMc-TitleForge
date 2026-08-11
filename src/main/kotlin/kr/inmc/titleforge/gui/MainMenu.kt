package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player

class MainMenu(plugin: TitleForgePlugin, viewer: Player) : Menu(plugin, viewer, rows = 3) {

    override fun title(): Component = plugin.messages.component("gui.main-title")

    override fun render() {
        val profile = plugin.profiles.of(viewer)
        val gui = plugin.settings.gui

        button(
            10,
            Gui.item(
                plugin, "gui.button.titles", gui.iconTitle,
                placeholders = arrayOf(
                    "owned" to (profile?.count(BadgeType.TITLE) ?: 0),
                    "total" to plugin.badges.count(BadgeType.TITLE),
                ),
            ),
        ) { BadgeListMenu(plugin, viewer, BadgeType.TITLE).open() }

        button(
            12,
            Gui.item(
                plugin, "gui.button.seals", gui.iconSeal,
                placeholders = arrayOf(
                    "owned" to (profile?.count(BadgeType.SEAL) ?: 0),
                    "total" to plugin.badges.count(BadgeType.SEAL),
                ),
            ),
        ) { BadgeListMenu(plugin, viewer, BadgeType.SEAL).open() }

        button(14, Gui.item(plugin, "gui.button.profile", gui.iconProfile)) {
            ProfileMenu(plugin, viewer, viewer).open()
        }

        if (viewer.hasPermission("titleforge.admin")) {
            button(16, Gui.item(plugin, "gui.button.admin", gui.iconAdmin)) {
                AdminMenu(plugin, viewer, BadgeType.TITLE).open()
            }
        }

        button(22, Gui.item(plugin, "gui.button.close", Material.BARRIER)) { viewer.closeInventory() }
        fill()
    }
}

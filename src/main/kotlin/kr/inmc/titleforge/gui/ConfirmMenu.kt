package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player

/** 파괴적 동작 확인 창 (맞춤 지침 7.5-19). */
class ConfirmMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val description: Component,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit = {},
) : Menu(plugin, viewer, rows = 3) {

    private var decided = false

    override fun title(): Component = plugin.messages.component("gui.confirm-title")

    override fun render() {
        button(
            11,
            Gui.item(plugin, "gui.button.confirm", Material.LIME_CONCRETE, extraLore = listOf(description)),
        ) {
            decided = true
            onConfirm()
        }
        button(
            15,
            Gui.item(plugin, "gui.button.cancel", Material.RED_CONCRETE, extraLore = listOf(description)),
        ) {
            decided = true
            onCancel()
        }
        fill()
    }

    override fun onClose() {
        if (!decided) decided = true
    }
}

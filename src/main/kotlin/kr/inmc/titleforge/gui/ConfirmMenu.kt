package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
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

    /** ESC 로 닫아도 '취소' 와 동일하게 처리해 호출한 메뉴로 되돌아간다. */
    override fun onClose() {
        if (decided) return
        decided = true
        Sched.entity(plugin, viewer) { onCancel() }
    }
}

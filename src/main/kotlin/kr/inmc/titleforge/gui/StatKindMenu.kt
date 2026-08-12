package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.StatKind
import kr.inmc.titleforge.util.Items
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player

/**
 * 스텟 종류 선택 창.
 *
 * 스텟이 60종을 넘어가면 한 화면에서 전부 다루기 어렵다. 바닐라 / MMOItems / 커스텀으로
 * 먼저 갈라 들어가게 해서, 관리자가 지금 만지려는 종류만 보게 한다.
 */
class StatKindMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val badgeId: String,
) : Menu(plugin, viewer, rows = 3) {

    private fun badge(): Badge? = plugin.badges.get(BadgeType.TITLE, badgeId)

    override fun title(): Component = plugin.messages.component("gui.stat-kind-title", "id" to badgeId)

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val badge = badge() ?: run {
            plugin.messages.send(viewer, "badge.not-found", "type" to BadgeType.TITLE.display, "id" to badgeId)
            AdminMenu(plugin, viewer, BadgeType.TITLE).openLater()
            return
        }

        kindButton(10, StatKind.VANILLA, badge, Material.IRON_SWORD)
        kindButton(13, StatKind.MMO, badge, Material.NETHER_STAR)
        kindButton(16, StatKind.VIRTUAL, badge, Material.PAPER)

        button(22, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            BadgeEditMenu(plugin, viewer, badge).openLater()
        }
        fill()
    }

    private fun kindButton(slot: Int, kind: StatKind, badge: Badge, icon: Material) {
        val stats = plugin.stats.ofKind(kind)
        val configured = stats.count {
            (badge.equipStats[it.id] ?: 0.0) != 0.0 || (badge.ownStats[it.id] ?: 0.0) != 0.0
        }

        val lore = ArrayList<Component>()
        lore += plugin.messages.component(
            "gui.lore.stat-kind-summary",
            "configured" to configured,
            "total" to stats.size,
        )
        if (stats.isEmpty()) {
            lore += plugin.messages.component("gui.lore.stat-kind-empty")
        } else {
            lore += plugin.messages.component("gui.lore.stat-kind-open")
        }

        val name = plugin.messages.component("gui.lore.stat-kind-name-${kind.id}")
        // 정의가 하나도 없는 종류는 눌러도 빈 화면이라 아예 열지 않는다.
        val item = Items.of(if (stats.isEmpty()) Material.GRAY_DYE else icon, name, lore, glow = configured > 0)

        if (stats.isEmpty()) {
            button(slot, item)
        } else {
            button(slot, item) { StatEditMenu(plugin, viewer, badgeId, kind).openLater() }
        }
    }
}

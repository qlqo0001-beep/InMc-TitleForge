package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 칭호/인장 목록. 페이지 단위로만 아이템을 만든다.
 *
 * 칭호: 좌클릭 = 표시 장착, 우클릭 = 능력치 장착, Shift = 해제
 * 인장: 좌클릭 = 장착, Shift = 해제
 */
class BadgeListMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val type: BadgeType,
) : Menu(plugin, viewer, rows = 6) {

    private var page = 0
    private var showUnowned = plugin.settings.gui.showUnownedByDefault

    private val pageSize: Int get() = plugin.settings.gui.pageSize

    private fun visible(): List<Badge> {
        val profile = plugin.profiles.of(viewer)
        return plugin.badges.all(type).filter { badge ->
            val owned = profile?.has(type, badge.id) == true
            when {
                owned -> true
                badge.hidden -> false
                else -> showUnowned
            }
        }
    }

    private fun maxPage(): Int {
        val count = visible().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    override fun title(): Component = plugin.messages.component(
        if (type == BadgeType.TITLE) "gui.title-list" else "gui.seal-list",
        "page" to (page + 1),
        "max" to maxPage(),
    )

    override fun render() {
        val profile = plugin.profiles.of(viewer)
        val badges = visible()
        val max = maxPage()
        if (page >= max) page = max - 1

        val from = page * pageSize
        val slice = badges.drop(from).take(pageSize)

        slice.forEachIndexed { index, badge ->
            val owned = profile?.has(type, badge.id) == true
            val locked = plugin.badgeService.locked(viewer, badge)
            val equippedSlots = EquipSlot.entries.filter {
                it.type == type && profile?.equipped(it) == badge.id
            }
            button(index, Gui.badgeIcon(plugin, profile, badge, owned, locked, equippedSlots)) { event ->
                onBadgeClick(badge, event.click, equippedSlots)
            }
        }

        renderNav(max)
        fill()
    }

    private fun onBadgeClick(badge: Badge, click: ClickType, equippedSlots: List<EquipSlot>) {
        val changed = when {
            click.isShiftClick -> {
                var any = false
                equippedSlots.forEach { slot -> if (plugin.badgeService.equip(viewer, slot, null)) any = true }
                any
            }

            type == BadgeType.SEAL -> plugin.badgeService.equip(viewer, EquipSlot.SEAL, badge.id)
            click.isRightClick -> plugin.badgeService.equip(viewer, EquipSlot.STAT, badge.id)
            else -> plugin.badgeService.equip(viewer, EquipSlot.DISPLAY, badge.id)
        }
        if (changed) redraw()
    }

    private fun renderNav(max: Int) {
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

        val filterPath = if (showUnowned) "gui.button.filter-on" else "gui.button.filter-off"
        button(navRow + 2, Gui.item(plugin, filterPath, Material.HOPPER)) {
            showUnowned = !showUnowned
            page = 0
            openLater()
        }

        button(navRow + 4, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            MainMenu(plugin, viewer).openLater()
        }

        button(navRow + 6, Gui.item(plugin, "gui.button.unequip", Material.STRUCTURE_VOID)) {
            val slots = if (type == BadgeType.SEAL) {
                listOf(EquipSlot.SEAL)
            } else {
                listOf(EquipSlot.DISPLAY, EquipSlot.STAT)
            }
            var any = false
            slots.forEach { if (plugin.badgeService.equip(viewer, it, null)) any = true }
            if (any) redraw()
        }
    }
}

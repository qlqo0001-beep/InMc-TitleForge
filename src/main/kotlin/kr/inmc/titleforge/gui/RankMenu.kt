package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.rank.RankService
import kr.inmc.titleforge.storage.RankEntry
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player

/** 칭호·인장 수집 개수 순위. */
class RankMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private var type: BadgeType,
) : Menu(plugin, viewer, rows = 6) {

    private var snapshot: RankService.Snapshot? = null
    private var mine: RankEntry? = null

    override fun title(): Component =
        plugin.messages.component("gui.rank-title", "type" to type.display)

    override fun render() {
        val current = snapshot
        if (current == null) {
            renderLoading()
            requestData()
            return
        }

        current.entries.take(TOP_SLOTS).forEachIndexed { index, entry ->
            button(index, entryIcon(entry, index + 1))
        }

        val navRow = size - 9
        button(
            navRow + 2,
            Gui.item(
                plugin, "gui.button.rank-toggle", Material.COMPARATOR,
                placeholders = arrayOf("type" to type.display),
            ),
        ) {
            type = if (type == BadgeType.TITLE) BadgeType.SEAL else BadgeType.TITLE
            snapshot = null
            mine = null
            openLater()
        }

        button(navRow + 4, myRankIcon(current))

        button(navRow + 6, Gui.item(plugin, "gui.button.rank-refresh", Material.CLOCK)) {
            plugin.rank.request(type, force = true) { fresh ->
                snapshot = fresh
                openLater()
            }
            plugin.rank.requestPersonal(viewer.uniqueId, type) { mine = it }
        }

        button(navRow + 8, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            MainMenu(plugin, viewer).openLater()
        }
        fill()
    }

    private fun renderLoading() {
        button(22, Gui.item(plugin, "gui.button.rank-loading", Material.CLOCK))
        button(size - 1, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            MainMenu(plugin, viewer).openLater()
        }
        fill()
    }

    private fun requestData() {
        plugin.rank.request(type) { fresh ->
            snapshot = fresh
            if (fresh == null) {
                plugin.messages.send(viewer, "rank.disabled")
                return@request
            }
            openLater()
        }
        plugin.rank.requestPersonal(viewer.uniqueId, type) { mine = it }
    }

    private fun entryIcon(entry: RankEntry, rank: Int): org.bukkit.inventory.ItemStack {
        val medal = when (rank) {
            1 -> "<gold>"
            2 -> "<white>"
            3 -> "<#cd7f32>"
            else -> "<gray>"
        }
        val lore = listOf(
            plugin.messages.component("gui.lore.rank-count", "count" to entry.count, "type" to type.display),
            plugin.messages.component("gui.lore.rank-position", "rank" to rank),
        )
        val name = Text.mini(
            "$medal<bold><rank>위</bold> <white><name>",
            "rank" to rank,
            "name" to entry.name,
        )
        return Items.head(Bukkit.getOfflinePlayer(entry.uuid), name, lore)
    }

    private fun myRankIcon(snapshot: RankService.Snapshot): org.bukkit.inventory.ItemStack {
        val entry = mine
        val extra = if (entry == null) {
            listOf(plugin.messages.component("gui.lore.rank-none"))
        } else {
            listOf(
                plugin.messages.component(
                    "gui.lore.rank-mine",
                    "rank" to entry.rank,
                    "total" to snapshot.total,
                    "count" to entry.count,
                ),
            )
        }
        return Gui.item(
            plugin, "gui.button.rank-mine", Material.PLAYER_HEAD,
            extraLore = extra,
            placeholders = arrayOf("type" to type.display),
        )
    }

    private companion object {
        /** 마지막 줄은 조작 버튼이라 45칸까지만 순위를 채운다. */
        const val TOP_SLOTS = 45
    }
}

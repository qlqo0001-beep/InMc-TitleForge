package kr.inmc.titleforge.gui

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.place.PlaceNames.Kind
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 월드·생물군계를 보여줄 이름(`places.yml`). 좌클릭으로 정하고 Shift+우클릭으로 지운다.
 *
 * 목록은 **이 서버에 있는 것 + 이름만 적혀 있는 것**이다. 데이터팩을 뺐어도 적어 둔 이름은 보여야 다시 넣었을 때
 * 그대로 쓰이는 것을 알 수 있다.
 */
class PlaceNameMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private var kind: Kind,
) : Menu(plugin, viewer, rows = 6) {

    private var page = 0

    private val pageSize: Int get() = plugin.settings.gui.pageSize

    /** @param id 보이는 id(월드 이름·생물군계 전체 id) @param present 이 서버에 있는지 */
    private data class Entry(val id: String, val present: Boolean, val world: World? = null)

    private fun entries(): List<Entry> = when (kind) {
        Kind.WORLD -> {
            val loaded = Bukkit.getWorlds().associateBy { it.name.lowercase() }
            (loaded.keys + plugin.places.all(Kind.WORLD).keys).distinct().sorted()
                .map { Entry(it, it in loaded, loaded[it]) }
        }

        Kind.BIOME -> {
            val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME)
                .map { it.key.asString() }
                .sortedWith(compareBy({ !it.startsWith("minecraft:") }, { it }))
            val covered = registry.flatMap { listOf(it, it.substringAfter(':')) }.toSet()
            registry.map { Entry(it, true) } +
                plugin.places.all(Kind.BIOME).keys.filter { it !in covered }.sorted().map { Entry(it, false) }
        }
    }

    private fun maxPage(count: Int): Int = if (count == 0) 1 else (count + pageSize - 1) / pageSize

    override fun title(): Component =
        plugin.messages.component(
            "gui.place-title",
            "kind" to kind.display, "page" to (page + 1), "max" to maxPage(entries().size),
        )

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val list = entries()
        val max = maxPage(list.size)
        if (page >= max) page = max - 1

        list.drop(page * pageSize).take(pageSize).forEachIndexed { index, entry ->
            button(index, icon(entry)) { event ->
                if (event.click == ClickType.SHIFT_RIGHT) clear(entry.id) else edit(entry.id)
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
        button(navRow + 2, Gui.item(plugin, "gui.button.place-kind", Material.COMPARATOR, placeholders = arrayOf("kind" to kind.display))) {
            kind = if (kind == Kind.WORLD) Kind.BIOME else Kind.WORLD
            page = 0
            openLater()
        }
        button(navRow + 4, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            AdminMenu(plugin, viewer, kr.inmc.titleforge.badge.BadgeType.TITLE).openLater()
        }
        val here = plugin.placeTracker.here(viewer).let { if (kind == Kind.WORLD) it.world else it.biome }
        button(navRow + 6, Gui.item(plugin, "gui.button.place-here", Material.COMPASS, placeholders = arrayOf("kind" to kind.display, "id" to here))) {
            edit(here)
        }
        fill()
    }

    private fun icon(entry: Entry): org.bukkit.inventory.ItemStack {
        val value = plugin.places.get(kind, entry.id)
        val messages = plugin.messages
        val lore = ArrayList<Component>()
        lore += messages.component("gui.lore.place-id", "id" to entry.id)
        if (!entry.present) lore += messages.component("gui.lore.place-missing", "kind" to kind.display)
        lore += Component.empty()
        if (value != null) {
            // 원문을 보여준다 — 문자열을 넘기면 그 서식이 해석돼 버린다.
            lore += messages.component("gui.lore.place-raw", "value" to Component.text(value))
            lore += messages.component("gui.lore.place-click-edit")
            lore += messages.component("gui.lore.place-click-clear")
        } else {
            lore += messages.component("gui.lore.place-unnamed")
            lore += messages.component("gui.lore.place-click-edit")
        }
        val name = if (value != null) Text.mini(value) else messages.component("gui.lore.place-no-name", "id" to entry.id)
        val material = when {
            kind == Kind.WORLD -> when (entry.world?.environment) {
                World.Environment.NETHER -> Material.NETHERRACK
                World.Environment.THE_END -> Material.END_STONE
                null -> Material.PAPER
                else -> Material.GRASS_BLOCK
            }
            !entry.present -> Material.PAPER
            value != null -> Material.FILLED_MAP
            else -> Material.MAP
        }
        return Items.of(material, name, lore, glow = value != null)
    }

    private fun edit(id: String) {
        val current = plugin.places.get(kind, id).orEmpty()
        val editing = kind
        val prompt = plugin.messages.prefix().append(
            plugin.messages.component("input.prompt-place-name", "kind" to editing.display, "id" to id),
        )
        plugin.dialogInput.prompt(viewer, current, prompt) { input ->
            // 취소면 null, 비워서 확인하면 이름을 지운다.
            if (input != null) apply(editing, id, input.ifBlank { null })
            reopen(editing)
        }
    }

    private fun clear(id: String) {
        if (plugin.places.get(kind, id) == null) return
        val clearing = kind
        ConfirmMenu(
            plugin, viewer,
            description = plugin.messages.component("gui.lore.place-clear-target", "kind" to clearing.display, "id" to id),
            onConfirm = {
                apply(clearing, id, null)
                reopen(clearing)
            },
            onCancel = { reopen(clearing) },
        ).openLater()
    }

    private fun apply(kind: Kind, id: String, value: String?) {
        plugin.places.set(kind, plugin.places.keyFor(kind, id), value)
        plugin.savePlaces()
        if (value == null) {
            plugin.messages.send(viewer, "place.cleared", "kind" to kind.display, "id" to id)
        } else {
            plugin.messages.send(viewer, "place.saved", "kind" to kind.display, "id" to id, "name" to value)
        }
    }

    private fun reopen(kind: Kind) {
        val menu = PlaceNameMenu(plugin, viewer, kind)
        menu.page = page
        menu.openLater()
    }
}

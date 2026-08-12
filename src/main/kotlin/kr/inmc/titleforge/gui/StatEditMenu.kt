package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.Stat
import kr.inmc.titleforge.stat.StatLayout
import kr.inmc.titleforge.stat.StatValueParser
import kr.inmc.titleforge.stat.Stats
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 장착 · 보유 스텟 통합 편집 창.
 *
 * - 분류(전투/방어/이동/유틸리티)별로 한 줄씩 배치해 관리자가 찾아다니지 않아도 된다.
 * - 각 아이콘 로어에 장착/보유 값과 바닐라 Attribute 정보를 함께 표시한다.
 * - 좌클릭 = 장착 스텟 입력, Shift+좌클릭 = 보유 스텟 입력 (채팅 입력, 로그 미발생)
 * - 우클릭 = 장착 스텟 제거, Shift+우클릭 = 보유 스텟 제거 (확인 창)
 */
class StatEditMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val badgeId: String,
) : Menu(plugin, viewer, rows = StatLayout.ROWS) {

    private var page = 0

    private fun layout() = StatLayout.compute(page) { plugin.stats.byCategory(it) }

    /** 항상 레지스트리에서 최신 정의를 읽는다. 동시에 편집해도 옛 값으로 덮어쓰지 않는다. */
    private fun badge(): Badge? = plugin.badges.get(BadgeType.TITLE, badgeId)

    override fun title(): Component {
        val layout = layout()
        return plugin.messages.component(
            "gui.stat-title",
            "id" to badgeId,
            "page" to (layout.page + 1),
            "max" to layout.pageCount,
        )
    }

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val badge = badge() ?: run {
            // 편집 중 삭제된 경우
            plugin.messages.send(viewer, "badge.not-found", "type" to BadgeType.TITLE.display, "id" to badgeId)
            AdminMenu(plugin, viewer, BadgeType.TITLE).openLater()
            return
        }

        val layout = layout()
        page = layout.page

        renderHeader(badge)
        renderCategories(badge, layout)
        renderFooter(badge, layout)
        fill()
    }

    private fun renderHeader(badge: Badge) {
        button(StatLayout.SLOT_BACK, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            BadgeEditMenu(plugin, viewer, badge).openLater()
        }

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
            StatLayout.SLOT_SUMMARY,
            Items.of(badge.icon, badge.nameComponent, summary, glow = true),
        )

        if (badge.equipStats.isNotEmpty() || badge.ownStats.isNotEmpty()) {
            button(StatLayout.SLOT_RESET_ALL, Gui.item(plugin, "gui.button.stat-reset-all", Material.LAVA_BUCKET)) {
                ConfirmMenu(
                    plugin, viewer,
                    description = plugin.messages.component("gui.lore.stat-reset-target", "name" to badge.nameComponent),
                    onConfirm = {
                        val latest = badge() ?: return@ConfirmMenu
                        persist(latest.copy(equipStats = emptyMap(), ownStats = emptyMap()))
                        plugin.messages.send(viewer, "stat.reset-all", "name" to latest.nameComponent)
                        openLater()
                    },
                    onCancel = { openLater() },
                ).openLater()
            }
        }
    }

    private fun renderCategories(badge: Badge, layout: StatLayout.Layout) {
        for ((slot, label) in layout.labels) {
            button(
                slot,
                Gui.categoryLabel(plugin, label.category, badge.equipStats, badge.ownStats, label.continuation),
            )
        }

        for ((stat, slot) in layout.statSlots) {
            val equip = badge.equipStats[stat.id] ?: 0.0
            val own = badge.ownStats[stat.id] ?: 0.0
            button(slot, Gui.statIcon(plugin, stat, equip, own)) { event ->
                onStatClick(stat, event.click)
            }
        }
    }

    private fun renderFooter(badge: Badge, layout: StatLayout.Layout) {
        button(StatLayout.SLOT_HELP, Gui.item(plugin, "gui.button.stat-help", Material.BOOK))
        button(
            StatLayout.SLOT_LEGEND,
            Gui.item(
                plugin, "gui.button.stat-legend", Material.PAPER,
                placeholders = arrayOf(
                    "page" to (layout.page + 1),
                    "max" to layout.pageCount,
                    "total" to plugin.stats.size(),
                ),
            ),
        )

        if (layout.hasPrev) {
            button(StatLayout.SLOT_PREV, Gui.item(plugin, "gui.button.prev", Material.ARROW)) {
                page--
                openLater()
            }
        }
        if (layout.hasNext) {
            button(StatLayout.SLOT_NEXT, Gui.item(plugin, "gui.button.next", Material.ARROW)) {
                page++
                openLater()
            }
        }

        button(StatLayout.SLOT_FOOTER_BACK, Gui.item(plugin, "gui.button.back", Material.OAK_DOOR)) {
            BadgeEditMenu(plugin, viewer, badge).openLater()
        }
    }

    // ── 클릭 처리 ──────────────────────────────────────────────────────

    private fun onStatClick(stat: Stat, click: ClickType) {
        when {
            click == ClickType.SHIFT_LEFT -> promptValue(stat, equipSide = false)
            click.isLeftClick -> promptValue(stat, equipSide = true)
            click == ClickType.SHIFT_RIGHT -> confirmRemove(stat, equipSide = false)
            click.isRightClick -> confirmRemove(stat, equipSide = true)
        }
    }

    private fun sideName(equipSide: Boolean): String = if (equipSide) "장착 스텟" else "보유 스텟"

    private fun currentValue(badge: Badge, stat: Stat, equipSide: Boolean): Double =
        (if (equipSide) badge.equipStats else badge.ownStats)[stat.id] ?: 0.0

    private fun promptValue(stat: Stat, equipSide: Boolean) {
        val badge = badge() ?: return
        val current = currentValue(badge, stat, equipSide)

        val prompt = plugin.messages.prefix().append(
            plugin.messages.component(
                "stat.prompt-chat",
                "side" to sideName(equipSide),
                "stat" to stat.display,
                "current" to stat.format(current),
                "unit" to (stat.suffix.ifEmpty { "값" }),
                "range" to stat.softRangeDisplay(),
            ),
        )

        plugin.chatInput.prompt(viewer, "", prompt) { input ->
            if (input == null) {
                plugin.messages.send(viewer, "input.cancelled")
                openLater()
                return@prompt
            }
            applyInput(stat, equipSide, input)
            openLater()
        }
    }

    private fun applyInput(stat: Stat, equipSide: Boolean, input: String) {
        val badge = badge() ?: return
        when (val result = StatValueParser.parse(stat, input)) {
            is StatValueParser.Result.Cancel -> plugin.messages.send(viewer, "input.cancelled")

            is StatValueParser.Result.Invalid ->
                plugin.messages.send(viewer, "stat.invalid", "value" to Text.escape(input))

            is StatValueParser.Result.TooLarge ->
                plugin.messages.send(viewer, "stat.too-large", "limit" to Text.number(result.limit, 0))

            is StatValueParser.Result.Remove -> {
                write(badge, stat, equipSide, 0.0)
                plugin.messages.send(
                    viewer, "stat.removed",
                    "side" to sideName(equipSide), "stat" to stat.display,
                )
            }

            is StatValueParser.Result.Set -> {
                write(badge, stat, equipSide, result.internal)
                plugin.messages.send(
                    viewer, "stat.applied",
                    "side" to sideName(equipSide),
                    "stat" to stat.display,
                    "value" to stat.format(result.internal),
                )
                if (result.outOfSoftRange) {
                    plugin.messages.send(
                        viewer, "stat.out-of-range",
                        "stat" to stat.display, "range" to stat.softRangeDisplay(),
                    )
                }
            }
        }
    }

    private fun confirmRemove(stat: Stat, equipSide: Boolean) {
        val badge = badge() ?: return
        if (currentValue(badge, stat, equipSide) == 0.0) {
            plugin.messages.send(viewer, "stat.nothing-to-remove", "side" to sideName(equipSide))
            return
        }
        ConfirmMenu(
            plugin, viewer,
            description = plugin.messages.component(
                "gui.lore.stat-remove-target",
                "side" to sideName(equipSide),
                "stat" to stat.display,
            ),
            onConfirm = {
                val latest = badge() ?: return@ConfirmMenu
                write(latest, stat, equipSide, 0.0)
                plugin.messages.send(
                    viewer, "stat.removed",
                    "side" to sideName(equipSide), "stat" to stat.display,
                )
                openLater()
            },
            onCancel = { openLater() },
        ).openLater()
    }

    private fun write(badge: Badge, stat: Stat, equipSide: Boolean, internal: Double) {
        val updated = if (equipSide) {
            badge.copy(equipStats = Stats.with(badge.equipStats, stat, internal))
        } else {
            badge.copy(ownStats = Stats.with(badge.ownStats, stat, internal))
        }
        persist(updated)
    }

    private fun persist(updated: Badge) {
        plugin.badgeService.persist(updated)
    }
}

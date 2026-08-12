package kr.inmc.titleforge.gui

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.DurationParser
import kr.inmc.titleforge.util.Items
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import java.util.UUID

/**
 * 관리자용 플레이어 보유 현황 창.
 *
 * 오프라인 대상도 다룬다. 오프라인 프로필은 캐시에 없는 임시 인스턴스일 수 있는데,
 * 그 상태로 대상이 접속하면 **같은 UUID 에 서로 다른 프로필 객체가 두 개** 생긴다.
 * 임시 인스턴스에 계속 쓰면 접속 세션이 만든 변경을 통째로 덮어쓰므로,
 * [profile] 이 매번 캐시를 먼저 확인해 살아 있는 쪽을 쓴다.
 */
class PlayerBadgeMenu(
    plugin: TitleForgePlugin,
    viewer: Player,
    private val targetUuid: UUID,
    private val targetName: String,
    /** 캐시에 없을 때만 쓰는 임시 프로필. 캐시에 들어오면 그쪽이 우선한다. */
    private val fallback: PlayerProfile,
    private var type: BadgeType,
) : Menu(plugin, viewer, rows = 6) {

    private var page = 0

    private val pageSize: Int get() = plugin.settings.gui.pageSize

    /** 항상 살아 있는 프로필을 고른다. 대상이 도중에 접속해도 안전하다. */
    private fun profile(): PlayerProfile = plugin.profiles.cached(targetUuid) ?: fallback

    private fun badges(): List<Badge> = plugin.badges.all(type)

    private fun maxPage(): Int {
        val count = badges().size
        return if (count == 0) 1 else (count + pageSize - 1) / pageSize
    }

    override fun title(): Component = plugin.messages.component(
        "gui.player-badge-title",
        "player" to targetName,
        "type" to type.display,
        "page" to (page + 1),
        "max" to maxPage(),
    )

    override fun render() {
        if (!viewer.hasPermission("titleforge.admin")) {
            viewer.closeInventory()
            return
        }
        val target = profile()
        val list = badges()
        val max = maxPage()
        if (page >= max) page = max - 1

        list.drop(page * pageSize).take(pageSize).forEachIndexed { index, listed ->
            val badgeType = listed.type
            val badgeId = listed.id
            val owned = target.has(badgeType, badgeId)
            val equippedSlots = EquipSlot.entries.filter {
                it.type == badgeType && target.equipped(it) == badgeId
            }
            val hint = if (owned) "gui.lore.admin-owned-hint" else "gui.lore.admin-unowned-hint"

            button(
                index,
                Gui.badgeIcon(
                    plugin, target, listed, owned = owned, locked = false,
                    equippedSlots = equippedSlots, clickable = false,
                    extraLore = listOf(Component.empty(), plugin.messages.component(hint)),
                ),
            ) { event ->
                // 클릭 시점에 다시 찾는다. 그 사이 정의가 지워졌거나 ID 가 바뀌었을 수 있다.
                val badge = plugin.badges.get(badgeType, badgeId) ?: run {
                    plugin.messages.send(
                        viewer, "badge.not-found",
                        "type" to badgeType.display, "id" to badgeId,
                    )
                    openLater()
                    return@button
                }
                onBadgeClick(badge, event.click)
            }
        }

        renderNav(max, target)
        fill()
    }

    private fun onBadgeClick(badge: Badge, click: ClickType) {
        val target = profile()
        when {
            click == ClickType.SHIFT_LEFT -> promptDuration(badge)
            click.isLeftClick -> grant(badge, PlayerProfile.PERMANENT)
            click.isRightClick -> confirmRevoke(badge, target)
        }
    }

    private fun grant(badge: Badge, expiresAt: Long) {
        val target = profile()
        if (plugin.badgeService.grant(target, badge, expiresAt)) {
            plugin.messages.send(
                viewer, "badge.granted",
                "target" to targetName,
                "name" to badge.nameComponent,
                "duration" to durationLabel(expiresAt),
            )
            save(target)
        } else {
            plugin.messages.send(viewer, "badge.already-owned", "target" to targetName)
        }
        redraw()
    }

    private fun promptDuration(badge: Badge) {
        val prompt = plugin.messages.prefix().append(plugin.messages.component("input.prompt-duration"))
        plugin.dialogInput.prompt(viewer, "30d", prompt) { input ->
            if (input == null) {
                openLater()
                return@prompt
            }
            when (val parsed = DurationParser.parse(input)) {
                is DurationParser.Result.Permanent -> grant(badge, PlayerProfile.PERMANENT)
                is DurationParser.Result.Limited ->
                    grant(badge, System.currentTimeMillis() + parsed.millis)

                is DurationParser.Result.Invalid ->
                    plugin.messages.send(viewer, "badge.invalid-duration", "value" to Text.escape(input))
            }
            openLater()
        }
    }

    private fun confirmRevoke(badge: Badge, target: PlayerProfile) {
        if (!target.has(badge.type, badge.id)) {
            plugin.messages.send(viewer, "badge.target-not-owned", "target" to targetName)
            return
        }
        ConfirmMenu(
            plugin, viewer,
            description = plugin.messages.component(
                "gui.lore.revoke-target",
                "target" to targetName,
                "name" to badge.nameComponent,
            ),
            onConfirm = {
                val latest = profile()
                if (plugin.badgeService.revoke(latest, badge)) {
                    plugin.messages.send(
                        viewer, "badge.taken",
                        "target" to targetName, "name" to badge.nameComponent,
                    )
                    save(latest)
                }
                openLater()
            },
            onCancel = { openLater() },
        ).openLater()
    }

    /**
     * 캐시에 있으면 dirty 배치 저장에 맡기고, 임시 프로필이면 직접 내보낸다.
     * 임시 프로필은 자동 저장 대상이 아니라 여기서 저장하지 않으면 변경이 사라진다.
     */
    private fun save(target: PlayerProfile) {
        if (plugin.profiles.isCached(target)) {
            plugin.profiles.save(target)
        } else {
            Sched.async(plugin) { plugin.profiles.persist(target) }
        }
    }

    private fun durationLabel(expiresAt: Long): String =
        if (expiresAt <= PlayerProfile.PERMANENT) {
            plugin.messages.raw("placeholder.permanent")
        } else {
            Text.duration((expiresAt - System.currentTimeMillis()) / 1000L)
        }

    private fun renderNav(max: Int, target: PlayerProfile) {
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

        button(
            navRow + 2,
            Gui.item(
                plugin, "gui.button.type-toggle", Material.COMPARATOR,
                placeholders = arrayOf("type" to type.display),
            ),
        ) {
            type = if (type == BadgeType.TITLE) BadgeType.SEAL else BadgeType.TITLE
            page = 0
            openLater()
        }

        val summary = listOf(
            plugin.messages.component(
                "gui.lore.player-owned-summary",
                "titles" to target.count(BadgeType.TITLE),
                // MiniMessage 태그 이름은 소문자/숫자/밑줄/하이픈만 허용한다 (대문자 불가).
                "title_total" to plugin.badges.count(BadgeType.TITLE),
                "seals" to target.count(BadgeType.SEAL),
                "seal_total" to plugin.badges.count(BadgeType.SEAL),
            ),
            plugin.messages.component(
                "gui.lore.player-nickname",
                "nickname" to Text.escape(target.nickname ?: targetName),
            ),
        )
        button(
            navRow + 4,
            Items.head(
                targetUuid, targetName,
                Text.mini("<white><bold><name>", "name" to Text.escape(targetName)),
                summary,
            ),
        )

        button(navRow + 6, Gui.item(plugin, "gui.button.close", Material.BARRIER)) { viewer.closeInventory() }
    }
}

package kr.inmc.titleforge.command

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.badge.Rarity
import kr.inmc.titleforge.gui.AdminMenu
import kr.inmc.titleforge.gui.BadgeListMenu
import kr.inmc.titleforge.gui.MainMenu
import kr.inmc.titleforge.gui.ProfileMenu
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.gui.RankMenu
import kr.inmc.titleforge.stat.Stats
import kr.inmc.titleforge.util.DurationParser
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

/**
 * `/it` 명령어 전체 트리.
 *
 * 인자 없이 실행하면 권한에 맞는 도움말을 출력한다.
 * 오프라인 대상 작업은 전부 비동기 조회 후 메인 스레드에서 반영한다 (맞춤 지침 7.1-1).
 */
class TitleForgeCommand(private val plugin: TitleForgePlugin) : CommandExecutor, TabCompleter {

    private val messages get() = plugin.messages

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sendHelp(sender)
            return true
        }

        when (args[0].lowercase()) {
            "menu", "gui", "메뉴" -> player(sender)?.let { MainMenu(plugin, it).open() }

            "title", "칭호" -> player(sender)?.let { BadgeListMenu(plugin, it, BadgeType.TITLE).open() }

            "seal", "인장" -> {
                val target = player(sender) ?: return true
                if (args.size == 1) {
                    BadgeListMenu(plugin, target, BadgeType.SEAL).open()
                } else {
                    plugin.badgeService.equip(target, EquipSlot.SEAL, args[1])
                }
            }

            "info", "내정보" -> handleInfo(sender, args)

            "equip", "장착" -> equip(sender, args, EquipSlot.STAT)
            "show", "표시" -> equip(sender, args, EquipSlot.DISPLAY)

            "unequip", "해제" -> {
                val target = player(sender) ?: return true
                val slot = args.getOrNull(1)?.let { EquipSlot.of(it) }
                if (slot == null) {
                    messages.send(sender, "general.unknown-command")
                } else {
                    plugin.badgeService.equip(target, slot, null)
                }
            }

            "nick", "닉네임" -> player(sender)?.let { plugin.nicknames.requestChange(it) }

            "rank", "순위" -> handleRank(sender, args)

            // ── 관리자 ──
            "create", "생성" -> ifAdmin(sender) { handleCreate(sender, args) }
            "delete", "삭제" -> ifAdmin(sender) { handleDelete(sender, args) }
            "edit", "수정" -> ifAdmin(sender) { handleEdit(sender, args) }
            "give", "지급" -> ifAdmin(sender) { handleGive(sender, args, grant = true) }
            "take", "회수" -> ifAdmin(sender) { handleGive(sender, args, grant = false) }
            "giveall" -> ifAdmin(sender) { handleGiveAll(sender, args) }
            "extend", "연장" -> ifAdmin(sender) { handleExtend(sender, args) }
            "setnick" -> ifAdmin(sender) { handleSetNick(sender, args) }
            "resetnick" -> ifAdmin(sender) { handleResetNick(sender, args) }
            "admin", "관리" -> ifAdmin(sender) {
                player(sender)?.let { AdminMenu(plugin, it, BadgeType.TITLE).open() }
            }

            "reload" -> ifAdmin(sender) { handleReload(sender) }

            else -> messages.send(sender, "general.unknown-command")
        }
        return true
    }

    // ── 공통 ───────────────────────────────────────────────────────────

    private fun player(sender: CommandSender): Player? {
        val player = sender as? Player
        if (player == null) messages.send(sender, "general.player-only")
        return player
    }

    private inline fun ifAdmin(sender: CommandSender, block: () -> Unit) {
        if (!sender.hasPermission(ADMIN)) {
            messages.send(sender, "general.no-permission")
            return
        }
        block()
    }

    private fun sendHelp(sender: CommandSender) {
        messages.sendRaw(sender, messages.component("help.header"))
        messages.list("help.user").forEach { messages.sendRaw(sender, Text.mini(it)) }
        if (sender.hasPermission(ADMIN)) {
            messages.list("help.admin").forEach { messages.sendRaw(sender, Text.mini(it)) }
        }
    }

    private fun typeOf(sender: CommandSender, raw: String?): BadgeType? {
        val type = raw?.let { BadgeType.of(it) }
        if (type == null) messages.send(sender, "general.invalid-type")
        return type
    }

    private fun badgeOf(sender: CommandSender, type: BadgeType, id: String?): Badge? {
        val badge = id?.let { plugin.badges.get(type, it) }
        if (badge == null) messages.send(sender, "badge.not-found", "type" to type.display, "id" to (id ?: "-"))
        return badge
    }

    /**
     * 이름으로 프로필을 찾아 [action] 을 메인 스레드에서 실행하고 저장까지 처리한다.
     * 오프라인 플레이어도 대상이 된다.
     */
    private fun withProfile(sender: CommandSender, name: String, action: (PlayerProfile) -> Unit) {
        Sched.async(plugin) {
            val profile = plugin.profiles.resolveBlocking(name)
            if (profile == null) {
                messages.send(sender, "player.not-found", "name" to name)
                return@async
            }
            Sched.global(plugin) {
                action(profile)
                if (plugin.profiles.isCached(profile)) {
                    plugin.profiles.save(profile)
                } else {
                    Sched.async(plugin) { plugin.profiles.persist(profile) }
                }
            }
        }
    }

    // ── 유저 ───────────────────────────────────────────────────────────

    private fun equip(sender: CommandSender, args: Array<out String>, slot: EquipSlot) {
        val target = player(sender) ?: return
        val id = args.getOrNull(1)
        if (id == null) {
            BadgeListMenu(plugin, target, slot.type).open()
            return
        }
        plugin.badgeService.equip(target, slot, id)
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val viewer = player(sender) ?: return
        val targetName = args.getOrNull(1)
        if (targetName == null) {
            ProfileMenu(plugin, viewer, viewer).open()
            return
        }
        if (!viewer.hasPermission("titleforge.info.other")) {
            messages.send(sender, "general.no-permission")
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            messages.send(sender, "player.not-found", "name" to targetName)
            return
        }
        ProfileMenu(plugin, viewer, target).open()
    }

    // ── 관리자 ─────────────────────────────────────────────────────────

    private fun handleCreate(sender: CommandSender, args: Array<out String>) {
        val type = typeOf(sender, args.getOrNull(1)) ?: return
        val id = args.getOrNull(2)?.let { Badge.normalizeId(it) }
        if (id == null || !Badge.validId(id)) {
            messages.send(sender, "badge.invalid-id")
            return
        }
        if (plugin.badges.exists(type, id)) {
            messages.send(sender, "badge.already-exists", "id" to id)
            return
        }
        val displayName = if (args.size > 3) args.drop(3).joinToString(" ") else "<white>[$id]"
        val badge = Badge(
            type = type,
            id = id,
            displayName = displayName,
            icon = if (type == BadgeType.TITLE) plugin.settings.gui.iconTitle else plugin.settings.gui.iconSeal,
        )
        plugin.badgeService.persist(badge)
        messages.send(sender, "badge.created", "type" to type.display, "name" to badge.nameComponent, "id" to id)
    }

    private fun handleDelete(sender: CommandSender, args: Array<out String>) {
        val type = typeOf(sender, args.getOrNull(1)) ?: return
        val badge = badgeOf(sender, type, args.getOrNull(2)) ?: return
        plugin.badgeService.delete(type, badge.id)
        messages.send(sender, "badge.deleted", "type" to type.display, "id" to badge.id)
    }

    private fun handleEdit(sender: CommandSender, args: Array<out String>) {
        val type = typeOf(sender, args.getOrNull(1)) ?: return
        val badge = badgeOf(sender, type, args.getOrNull(2)) ?: return
        val field = args.getOrNull(3)?.lowercase()
        if (field == null) {
            messages.send(sender, "general.unknown-command")
            return
        }
        val rest = args.drop(4)

        val updated: Badge = when (field) {
            "name", "이름" -> badge.copy(displayName = rest.joinToString(" ").ifBlank { badge.displayName })

            "lore", "설명" -> badge.copy(
                lore = rest.joinToString(" ").split('|').map { it.trim() }.filter { it.isNotEmpty() },
            )

            "rarity", "등급" -> {
                val rarity = rest.firstOrNull()?.let { Rarity.of(it) }
                if (rarity == null) {
                    messages.send(sender, "general.unknown-command")
                    return
                }
                badge.copy(rarity = rarity)
            }

            "icon", "아이콘" -> {
                val material = rest.firstOrNull()?.let { Material.matchMaterial(it.uppercase()) }
                if (material == null) {
                    messages.send(sender, "general.invalid-material", "value" to (rest.firstOrNull() ?: "-"))
                    return
                }
                badge.copy(icon = material)
            }

            "permission", "권한" -> badge.copy(permission = rest.firstOrNull().orEmpty())

            "hidden", "숨김" -> badge.copy(hidden = rest.firstOrNull()?.toBoolean() ?: !badge.hidden)

            "order", "순서" -> {
                val order = rest.firstOrNull()?.toIntOrNull()
                if (order == null) {
                    messages.send(sender, "general.invalid-number", "value" to (rest.firstOrNull() ?: "-"))
                    return
                }
                badge.copy(order = order)
            }

            "stat", "스텟" -> {
                if (type == BadgeType.SEAL) {
                    messages.send(sender, "badge.seal-no-stat")
                    return
                }
                val side = rest.getOrNull(0)?.lowercase()
                val stat = rest.getOrNull(1)?.let { plugin.stats.of(it) }
                val rawValue = rest.getOrNull(2)?.toDoubleOrNull()
                // 명령어도 GUI 와 같은 표시 단위를 쓴다.
                val value = rawValue?.let { stat?.toInternal(it) }
                if (stat == null) {
                    messages.send(sender, "general.invalid-stat", "value" to (rest.getOrNull(1) ?: "-"))
                    return
                }
                if (rawValue == null || value == null) {
                    messages.send(sender, "general.invalid-number", "value" to (rest.getOrNull(2) ?: "-"))
                    return
                }
                when (side) {
                    "equip", "장착" -> badge.copy(equipStats = Stats.with(badge.equipStats, stat, value))
                    "own", "보유" -> badge.copy(ownStats = Stats.with(badge.ownStats, stat, value))
                    else -> {
                        messages.send(sender, "general.unknown-command")
                        return
                    }
                }
            }

            else -> {
                messages.send(sender, "general.unknown-command")
                return
            }
        }

        plugin.badgeService.persist(updated)
        messages.send(sender, "badge.edited", "type" to type.display, "id" to badge.id, "field" to field)
    }

    /** `perm`(기본) / `30d` / `12h` / `2w` 등을 만료 시각으로 바꾼다. 잘못된 표기면 null. */
    private fun expiryOf(sender: CommandSender, raw: String?): Long? =
        when (val parsed = DurationParser.parse(raw)) {
            is DurationParser.Result.Permanent -> PlayerProfile.PERMANENT
            is DurationParser.Result.Limited -> System.currentTimeMillis() + parsed.millis
            is DurationParser.Result.Invalid -> {
                messages.send(sender, "badge.invalid-duration", "value" to (raw ?: "-"))
                null
            }
        }

    private fun handleGive(sender: CommandSender, args: Array<out String>, grant: Boolean) {
        val targetName = args.getOrNull(1)
        val type = typeOf(sender, args.getOrNull(2)) ?: return
        val badge = badgeOf(sender, type, args.getOrNull(3)) ?: return
        if (targetName == null) {
            messages.send(sender, "player.not-found", "name" to "-")
            return
        }
        // 4번째 인자는 보유 기간. 없으면 영구.
        val expiresAt = if (grant) expiryOf(sender, args.getOrNull(4)) ?: return else 0L

        withProfile(sender, targetName) { profile ->
            if (grant) {
                if (plugin.badgeService.grant(profile, badge, expiresAt)) {
                    messages.send(
                        sender, "badge.granted",
                        "target" to profile.name,
                        "name" to badge.nameComponent,
                        "duration" to durationLabel(expiresAt),
                    )
                } else {
                    messages.send(sender, "badge.already-owned", "target" to profile.name)
                }
            } else {
                if (plugin.badgeService.revoke(profile, badge)) {
                    messages.send(sender, "badge.taken", "target" to profile.name, "name" to badge.nameComponent)
                } else {
                    messages.send(sender, "badge.target-not-owned", "target" to profile.name)
                }
            }
        }
    }

    /** 만료 시각을 사람이 읽는 문구로. */
    private fun durationLabel(expiresAt: Long): String =
        if (expiresAt <= PlayerProfile.PERMANENT) {
            messages.raw("placeholder.permanent")
        } else {
            Text.duration((expiresAt - System.currentTimeMillis()) / 1000L)
        }

    /** 보유 기간만 바꾼다. */
    private fun handleExtend(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val type = typeOf(sender, args.getOrNull(2)) ?: return
        val badge = badgeOf(sender, type, args.getOrNull(3)) ?: return
        if (targetName == null) {
            messages.send(sender, "player.not-found", "name" to "-")
            return
        }
        val expiresAt = expiryOf(sender, args.getOrNull(4)) ?: return

        withProfile(sender, targetName) { profile ->
            if (plugin.badgeService.extend(profile, badge, expiresAt)) {
                messages.send(
                    sender, "badge.extended",
                    "target" to profile.name,
                    "name" to badge.nameComponent,
                    "duration" to durationLabel(expiresAt),
                )
            } else {
                messages.send(sender, "badge.target-not-owned", "target" to profile.name)
            }
        }
    }

    private fun handleRank(sender: CommandSender, args: Array<out String>) {
        if (!plugin.settings.rank.enabled) {
            messages.send(sender, "rank.disabled")
            return
        }
        if (args.getOrNull(1)?.equals("refresh", true) == true) {
            if (!sender.hasPermission(ADMIN)) {
                messages.send(sender, "general.no-permission")
                return
            }
            plugin.rank.invalidate()
            messages.send(sender, "rank.refreshed")
            return
        }
        val type = args.getOrNull(1)?.let { BadgeType.of(it) } ?: BadgeType.TITLE
        val viewer = player(sender) ?: return
        RankMenu(plugin, viewer, type).open()
    }

    private fun handleGiveAll(sender: CommandSender, args: Array<out String>) {
        val type = typeOf(sender, args.getOrNull(1)) ?: return
        val badge = badgeOf(sender, type, args.getOrNull(2)) ?: return
        val expiresAt = expiryOf(sender, args.getOrNull(3)) ?: return

        Sched.async(plugin) {
            val rows = runCatching { plugin.storage.grantToAll(type, badge.id, expiresAt) }.getOrElse {
                plugin.logger.severe("전체 지급 실패: ${it.message}")
                messages.send(sender, "general.storage-error")
                return@async
            }
            Sched.global(plugin) {
                var online = 0
                for (player in Bukkit.getOnlinePlayers()) {
                    val profile = plugin.profiles.of(player) ?: continue
                    if (plugin.badgeService.grant(profile, badge, expiresAt)) {
                        online++
                        plugin.profiles.save(profile)
                    }
                }
                messages.send(
                    sender, "badge.giveall-done",
                    "online" to online, "total" to rows, "name" to badge.nameComponent,
                )
            }
        }
    }

    private fun handleSetNick(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1)
        val nickname = args.drop(2).joinToString(" ").trim().ifBlank { null }
        if (targetName == null || nickname == null) {
            messages.send(sender, "general.unknown-command")
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            messages.send(sender, "player.not-found", "name" to targetName)
            return
        }
        val profile = plugin.profiles.of(target) ?: run {
            messages.send(sender, "general.profile-loading")
            return
        }
        plugin.nicknames.applyNickname(target, profile, nickname, touchCooldown = false)
        messages.send(sender, "nickname.changed-other", "target" to target.name, "nickname" to Text.escape(nickname))
    }

    private fun handleResetNick(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1) ?: run {
            messages.send(sender, "general.unknown-command")
            return
        }
        val target = Bukkit.getPlayerExact(targetName)
        if (target == null) {
            messages.send(sender, "player.not-found", "name" to targetName)
            return
        }
        val profile = plugin.profiles.of(target) ?: run {
            messages.send(sender, "general.profile-loading")
            return
        }
        plugin.nicknames.applyNickname(target, profile, null, touchCooldown = false)
        messages.send(sender, "nickname.reset-other", "target" to target.name)
    }

    private fun handleReload(sender: CommandSender) {
        plugin.reloadSettings()
        Sched.async(plugin) {
            val badges = runCatching { plugin.storage.loadBadges() }.getOrElse {
                plugin.logger.severe("칭호 로드 실패: ${it.message}")
                messages.send(sender, "general.storage-error")
                return@async
            }
            Sched.global(plugin) {
                plugin.badges.replaceAll(badges)
                plugin.profiles.refreshAllOnline()
                messages.send(sender, "general.reload-success", "count" to plugin.badges.total())
            }
        }
    }

    // ── 탭 완성 ────────────────────────────────────────────────────────

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        val admin = sender.hasPermission(ADMIN)
        val result: List<String> = when (args.size) {
            0, 1 -> USER_SUBCOMMANDS + if (admin) ADMIN_SUBCOMMANDS else emptyList()

            2 -> when (args[0].lowercase()) {
                "equip", "show" -> ownedIds(sender, BadgeType.TITLE)
                "seal" -> ownedIds(sender, BadgeType.SEAL)
                "unequip" -> listOf("stat", "show", "seal")
                "info" -> onlineNames()
                "rank" -> TYPES + if (admin) listOf("refresh") else emptyList()
                "create", "delete", "edit", "giveall" -> if (admin) TYPES else emptyList()
                "give", "take", "extend", "setnick", "resetnick" -> if (admin) onlineNames() else emptyList()
                else -> emptyList()
            }

            3 -> when (args[0].lowercase()) {
                "delete", "edit" -> if (admin) idsOf(args[1]) else emptyList()
                "giveall" -> if (admin) idsOf(args[1]) else emptyList()
                "give", "take", "extend" -> if (admin) TYPES else emptyList()
                else -> emptyList()
            }

            4 -> when (args[0].lowercase()) {
                "edit" -> if (admin) EDIT_FIELDS else emptyList()
                "give", "take", "extend" -> if (admin) idsOf(args[2]) else emptyList()
                "giveall" -> if (admin) DURATIONS else emptyList()
                else -> emptyList()
            }

            5 -> if (admin && (args[0].equals("give", true) || args[0].equals("extend", true))) {
                DURATIONS
            } else if (admin && args[0].equals("edit", true)) {
                when (args[3].lowercase()) {
                    "stat" -> listOf("equip", "own")
                    "rarity" -> Rarity.entries.map { it.id }
                    "hidden" -> listOf("true", "false")
                    else -> emptyList()
                }
            } else {
                emptyList()
            }

            6 -> if (admin && args[0].equals("edit", true) && args[3].equals("stat", true)) {
                plugin.stats.ids().toList()
            } else {
                emptyList()
            }

            else -> emptyList()
        }

        val prefix = args.lastOrNull().orEmpty().lowercase()
        return result.filter { it.lowercase().startsWith(prefix) }.sorted()
    }

    private fun onlineNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    private fun idsOf(rawType: String): List<String> =
        BadgeType.of(rawType)?.let { plugin.badges.ids(it).toList() } ?: emptyList()

    private fun ownedIds(sender: CommandSender, type: BadgeType): List<String> {
        val player = sender as? Player ?: return emptyList()
        val profile = plugin.profiles.of(player) ?: return emptyList()
        return profile.owned(type).toList()
    }

    private companion object {
        const val ADMIN = "titleforge.admin"

        val TYPES = listOf("title", "seal")

        val USER_SUBCOMMANDS = listOf(
            "menu", "title", "seal", "info", "equip", "show", "unequip", "nick", "rank",
        )

        val ADMIN_SUBCOMMANDS = listOf(
            "create", "delete", "edit", "give", "take", "giveall", "extend",
            "setnick", "resetnick", "admin", "reload",
        )

        /** 기간 인자 추천값. */
        val DURATIONS = listOf("perm", "1d", "7d", "14d", "30d", "12h", "1w")

        val EDIT_FIELDS = listOf("name", "lore", "rarity", "icon", "permission", "hidden", "order", "stat")
    }
}

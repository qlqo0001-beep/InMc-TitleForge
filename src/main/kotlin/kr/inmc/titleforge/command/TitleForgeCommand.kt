package kr.inmc.titleforge.command

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.badge.Rarity
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.nickname.NicknameService
import kr.inmc.titleforge.gui.AdminMenu
import kr.inmc.titleforge.gui.BadgeListMenu
import kr.inmc.titleforge.gui.MainMenu
import kr.inmc.titleforge.gui.PlayerBadgeMenu
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
            // 플레이어는 바로 메뉴, 콘솔은 창을 열 수 없으니 도움말.
            if (sender is Player) openMainMenu(sender) else sendHelp(sender)
            return true
        }

        // "help" 는 권한 없이도 봐야 하는 안내라 예외. 그 외 서브커맨드는 전부 최소한
        // titleforge.use 가 있어야 한다 — 예전엔 "menu" 진입 시에만 검사해서, 관리자가
        // 이 권한만 박탈해도 /it title 등 다른 서브커맨드는 그대로 새어나갔다.
        // titleforge.admin 은 permissions.yml 에서 titleforge.use 를 자식으로 포함하므로
        // 관리자는 이 검사에 영향받지 않는다.
        val sub = args[0].lowercase()
        if (sub != "help" && sub != "도움말" && sub != "?" && sender is Player && !sender.hasPermission(USE)) {
            messages.send(sender, "general.no-permission")
            return true
        }

        when (sub) {
            "help", "도움말", "?" -> sendHelp(sender)

            "menu", "gui", "메뉴" -> player(sender)?.let { openMainMenu(it) }

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

            "nick", "닉네임" -> player(sender)?.let { target ->
                // `/it nick reset` 은 원래 아이디로 되돌린다 (비용·쿨타임 없음).
                if (args.getOrNull(1)?.lowercase() in NICK_RESET_ARGS) {
                    plugin.nicknames.resetOwn(target)
                } else {
                    plugin.nicknames.requestChange(target)
                }
            }

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
            "resetcooldown", "쿨타임초기화" -> ifAdmin(sender) { handleResetCooldown(sender, args) }
            "checkitem" -> ifAdmin(sender) { handleCheckItem(sender) }
            "player", "플레이어" -> ifAdmin(sender) { handlePlayer(sender, args) }
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

    /** 기본 권한이 없으면 메뉴를 열지 않는다. */
    private fun openMainMenu(player: Player) {
        if (!player.hasPermission(USE)) {
            messages.send(player, "general.no-permission")
            return
        }
        MainMenu(plugin, player).open()
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
            val resolved = plugin.profiles.resolveBlocking(name)
            if (resolved == null) {
                messages.send(sender, "player.not-found", "name" to name)
                return@async
            }
            Sched.global(plugin) {
                // resolveBlocking 은 완전 오프라인 대상이면 캐시에 없는 임시 인스턴스를 돌려준다.
                // 그 비동기 조회 도중 대상이 실제로 접속하면 별도의 캐시 인스턴스가 생기는데,
                // 임시 인스턴스에 그대로 적용하면 나중에 어느 쪽이 저장되느냐에 따라 서로의
                // 변경을 덮어써 지급/설정이 사라진다. 적용 직전에 한 번 더 캐시를 확인해
                // 살아있는 인스턴스가 있으면 그쪽을 우선한다.
                val profile = plugin.profiles.cached(resolved.uuid) ?: resolved
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

        // ID 변경은 여러 테이블을 함께 옮겨야 해서 copy+persist 경로를 쓸 수 없다.
        if (field == "id" || field == "아이디") {
            val newId = rest.firstOrNull()?.let { Badge.normalizeId(it) }
            if (newId == null || !Badge.validId(newId)) {
                messages.send(sender, "badge.invalid-id")
                return
            }
            if (plugin.badges.exists(type, newId)) {
                messages.send(sender, "badge.already-exists", "id" to newId)
                return
            }
            plugin.badgeService.rename(type, badge.id, newId) { success, reason ->
                if (success) {
                    messages.send(
                        sender, "badge.id-changed",
                        "type" to type.display, "old" to badge.id, "new" to newId,
                    )
                } else {
                    messages.send(sender, "badge.id-change-failed", "reason" to (reason ?: "-"))
                }
            }
            return
        }

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
        // 관리자만 서식을 쓸 수 있다. `&c` 등 레거시 코드도 여기서 MiniMessage 로 통일한다.
        val formatted = NicknameService.formatAdminInput(nickname)
        plugin.nicknames.applyNickname(target, profile, formatted, touchCooldown = false)
        // 결과를 실제 색이 적용된 모습으로 보여 준다.
        messages.send(sender, "nickname.changed-other", "target" to target.name, "nickname" to Text.mini(formatted))
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

    /**
     * 닉네임 변경 쿨타임 초기화.
     *
     * 캐시된 프로필을 **먼저** 고친다. DB 만 0 으로 바꾸면 다음 자동 저장이 메모리에 남아 있던
     * 옛 값을 그대로 다시 써넣어 되돌아간다.
     */
    private fun handleResetCooldown(sender: CommandSender, args: Array<out String>) {
        val targetName = args.getOrNull(1) ?: run {
            messages.send(sender, "general.unknown-command")
            return
        }

        if (targetName.equals("all", true) || targetName == "전체") {
            for (profile in plugin.profiles.cachedProfiles()) {
                profile.nicknameChangedAt = 0L
                profile.markDirty()
            }
            Sched.async(plugin) {
                val rows = runCatching { plugin.storage.resetNicknameCooldownAll() }.getOrElse {
                    plugin.logger.severe("쿨타임 일괄 초기화 실패: ${it.message}")
                    messages.send(sender, "general.storage-error")
                    return@async
                }
                messages.send(sender, "nickname.cooldown-reset-all", "count" to rows)
            }
            return
        }

        withProfile(sender, targetName) { profile ->
            profile.nicknameChangedAt = 0L
            profile.markDirty()
            messages.send(sender, "nickname.cooldown-reset", "target" to profile.name)
        }
    }

    /**
     * 손에 든 아이템이 비용 아이템으로 인식되는지 확인한다.
     *
     * 비용 아이템이 왜 안 먹히는지는 설정과 실제 아이템을 나란히 보지 않으면 알기 어려워서
     * 진단용 명령을 따로 둔다.
     */
    private fun handleCheckItem(sender: CommandSender) {
        val player = player(sender) ?: return
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) {
            messages.send(sender, "badge.need-held-item")
            return
        }

        messages.sendRaw(sender, Text.mini("<gray>─ <white>손에 든 아이템<gray> ─"))
        messages.sendRaw(sender, Text.mini("<gray>재료: <white><v>", "v" to item.type.name))
        val mmo = plugin.mmoItems?.describe(item)
        messages.sendRaw(
            sender,
            Text.mini("<gray>MMOItems: <white><v>", "v" to (mmo ?: "아님 (MMOItems 태그 없음)")),
        )

        val costItems = plugin.settings.nickname.costItems
        if (costItems.isEmpty()) {
            messages.sendRaw(sender, Text.mini("<yellow>설정된 비용 아이템이 없습니다."))
            return
        }
        for (cost in costItems) {
            val ok = plugin.nicknames.debugMatches(item, cost)
            val expected = when (cost.type) {
                Settings.ItemSourceType.VANILLA -> "${cost.material.name} / 이름 '${cost.displayName.ifBlank { "(검사 안 함)" }}'"
                Settings.ItemSourceType.MMOITEMS -> "${cost.mmoType} / ${cost.mmoId}"
            }
            messages.sendRaw(
                sender,
                Text.mini(
                    "<gray>· <white><label></white> <gray>(<type>) → <result><newline>  <dark_gray>기대값: <gray><expected>",
                    "label" to cost.label,
                    "type" to cost.type.name.lowercase(),
                    "expected" to expected,
                    "result" to if (ok) "<green>일치" else "<red>불일치",
                ),
            )
        }
    }

    /** 관리자용 보유 현황 창. 오프라인 대상도 연다. */
    private fun handlePlayer(sender: CommandSender, args: Array<out String>) {
        val viewer = player(sender) ?: return
        val targetName = args.getOrNull(1) ?: run {
            messages.send(sender, "general.unknown-command")
            return
        }
        Sched.async(plugin) {
            val profile = plugin.profiles.resolveBlocking(targetName)
            if (profile == null) {
                messages.send(sender, "player.not-found", "name" to targetName)
                return@async
            }
            Sched.entity(plugin, viewer) {
                PlayerBadgeMenu(
                    plugin, viewer,
                    targetUuid = profile.uuid,
                    targetName = profile.name,
                    fallback = profile,
                    type = BadgeType.TITLE,
                ).open()
            }
        }
    }

    private fun handleReload(sender: CommandSender) {
        plugin.reloadSettings()
        Sched.async(plugin) {
            // 저장 대기 중인 편집을 먼저 내보내지 않으면 곧바로 덮어써서 잃어버린다.
            plugin.badgeService.flushPending()
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
                "nick" -> NICK_RESET_ARGS.toList()
                "info" -> onlineNames()
                "rank" -> TYPES + if (admin) listOf("refresh") else emptyList()
                "create", "delete", "edit", "giveall" -> if (admin) TYPES else emptyList()
                "resetcooldown" -> if (admin) onlineNames() + "all" else emptyList()
                "give", "take", "extend", "setnick", "resetnick", "player" ->
                    if (admin) onlineNames() else emptyList()
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
        const val USE = "titleforge.use"

        /** `/it nick <이것>` 이면 원래 아이디로 되돌린다. */
        val NICK_RESET_ARGS = setOf("reset", "초기화")

        val TYPES = listOf("title", "seal")

        val USER_SUBCOMMANDS = listOf(
            "help", "menu", "title", "seal", "info", "equip", "show", "unequip", "nick", "rank",
        )

        val ADMIN_SUBCOMMANDS = listOf(
            "create", "delete", "edit", "give", "take", "giveall", "extend",
            "setnick", "resetnick", "resetcooldown", "player", "checkitem", "admin", "reload",
        )

        /** 기간 인자 추천값. */
        val DURATIONS = listOf("perm", "1d", "7d", "14d", "30d", "12h", "1w")

        val EDIT_FIELDS =
            listOf("name", "lore", "rarity", "icon", "permission", "hidden", "order", "stat", "id")
    }
}

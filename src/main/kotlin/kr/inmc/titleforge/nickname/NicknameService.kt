package kr.inmc.titleforge.nickname

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.api.event.NicknameChangeEvent
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 닉네임 변경의 검증 · 비용 · 적용을 담당한다.
 *
 * 흐름: 입력 → (동기)형식 검증 → (비동기)중복 검사 → (동기)비용 차감 → 적용 → (비동기)저장
 */
class NicknameService(private val plugin: TitleForgePlugin) {

    private val config: Settings.NicknameSettings get() = plugin.settings.nickname

    /**
     * 지금 확정 절차를 밟고 있는 정규화 닉네임 → 신청자.
     *
     * "중복 검사 → 비용 차감 → 저장" 이 비동기를 한 번 거치므로, 두 사람이 같은 닉네임을
     * 동시에 신청하면 둘 다 검사를 통과할 수 있다. 이 예약이 그 구간을 직렬화한다.
     * DB 의 UNIQUE 제약은 여러 서버가 한 DB 를 공유할 때를 위한 최종 방어선이다.
     *
     * 신청자를 함께 들고 있는 이유: 확정 콜백은 [Sched.entity] 로 돌아오는데, 그 사이 접속이
     * 끊기면 **콜백이 조용히 버려진다.** 그때 예약을 놓아줄 곳이 없으면 해당 닉네임이 재시작
     * 전까지 아무도 못 쓰게 잠긴다. 그래서 퇴장 시 [handleQuit] 이 정리한다.
     */
    private val reserving = java.util.concurrent.ConcurrentHashMap<String, java.util.UUID>()

    /** 퇴장한 플레이어가 잡고 있던 예약을 놓아준다. */
    fun handleQuit(uuid: java.util.UUID) {
        reserving.values.removeIf { it == uuid }
    }

    /** 입력창을 띄운다. */
    fun requestChange(player: Player) {
        if (!config.enabled) {
            plugin.messages.send(player, "nickname.disabled")
            return
        }
        if (!player.hasPermission(PERMISSION)) {
            plugin.messages.send(player, "general.no-permission")
            return
        }
        val profile = plugin.profiles.of(player) ?: run {
            plugin.messages.send(player, "general.profile-loading")
            return
        }
        val remaining = cooldownRemaining(profile)
        if (remaining > 0) {
            plugin.messages.send(player, "nickname.cooldown", "time" to Text.duration(remaining))
            return
        }

        val input = if (config.inputMode == Settings.InputMode.CHAT) plugin.chatInput else plugin.dialogInput
        val prompt = plugin.messages.prefix().append(plugin.messages.component("nickname.prompt-chat"))
        input.prompt(player, profile.nickname ?: player.name, prompt) { result ->
            if (result == null) {
                plugin.messages.send(player, "nickname.cancelled")
            } else {
                submit(player, result)
            }
        }
    }

    fun cooldownRemaining(profile: PlayerProfile): Long {
        if (config.cooldownSeconds <= 0L || profile.nicknameChangedAt <= 0L) return 0L
        val elapsed = (System.currentTimeMillis() - profile.nicknameChangedAt) / 1000L
        return (config.cooldownSeconds - elapsed).coerceAtLeast(0L)
    }

    /** 유저가 입력한 닉네임을 처리한다. 메인/엔티티 스레드에서 호출. */
    fun submit(player: Player, rawInput: String) {
        val profile = plugin.profiles.of(player) ?: run {
            plugin.messages.send(player, "general.profile-loading")
            return
        }
        val nickname = rawInput.trim()

        if (nickname == profile.nickname || (profile.nickname == null && nickname == player.name)) {
            plugin.messages.send(player, "nickname.same")
            return
        }
        if (!validateFormat(player, nickname)) return

        val remaining = cooldownRemaining(profile)
        if (remaining > 0) {
            plugin.messages.send(player, "nickname.cooldown", "time" to Text.duration(remaining))
            return
        }

        if (!config.unique) {
            Sched.entity(plugin, player) { charge(player, profile, nickname) }
            return
        }

        // DB 검사와 실제 저장 사이에 다른 요청이 같은 닉네임을 확정해 버리는 경합을 막는다.
        // 이 예약을 잡은 동안에는 같은 정규화 키로 다른 요청이 들어올 수 없다.
        // 같은 사람이 자기 예약을 다시 잡는 건 허용한다(앞선 시도가 중간에 끊긴 경우).
        val reservation = NicknameNormalizer.normalize(nickname)
        if (reservation != null) {
            val holder = reserving.putIfAbsent(reservation, profile.uuid)
            if (holder != null && holder != profile.uuid) {
                plugin.messages.send(player, "nickname.duplicate")
                return
            }
        }

        Sched.async(plugin) {
            val taken = runCatching { plugin.storage.isNicknameTaken(nickname, profile.uuid) }
                .getOrElse {
                    plugin.logger.severe("닉네임 중복 검사 실패: ${it.message}")
                    true
                }
            Sched.entity(plugin, player) {
                try {
                    if (taken) {
                        plugin.messages.send(player, "nickname.duplicate")
                    } else {
                        // 일반 유저 경로: 서식을 무력화한 값만 저장한다.
                        charge(player, profile, sanitizeUserInput(nickname))
                    }
                } finally {
                    // 성공하면 DB 에 값이 남아 다음 요청은 isNicknameTaken 에서 걸린다.
                    // 실패·취소했다면 예약을 풀어 다른 사람이 쓸 수 있게 한다.
                    if (reservation != null) reserving.remove(reservation, profile.uuid)
                }
            }
        }
    }

    /** 형식 검증. 실패 시 사유를 알리고 false. */
    fun validateFormat(player: Player, nickname: String): Boolean {
        val length = config.lengthOf(nickname)
        if (length < config.minLength) {
            plugin.messages.send(player, "nickname.too-short", "min" to config.minLength)
            return false
        }
        if (length > config.maxLength) {
            plugin.messages.send(player, "nickname.too-long", "max" to config.maxLength)
            return false
        }
        if (!config.pattern.matches(nickname)) {
            plugin.messages.send(player, "nickname.invalid-chars", "allowed" to config.allowedDescription)
            return false
        }
        val lower = nickname.lowercase()
        if (config.blacklist.any { it.isNotBlank() && lower.contains(it) }) {
            plugin.messages.send(player, "nickname.blacklisted")
            return false
        }
        return true
    }

    /** 비용 확인 후 차감하고 적용까지 진행한다. 메인/엔티티 스레드 전용. */
    private fun charge(player: Player, profile: PlayerProfile, nickname: String) {
        // Vault 가 없으면 경제 비용은 자동으로 면제된다.
        val economy = plugin.vault?.takeIf { config.economyEnabled && config.economyAmount > 0 }
        val amount = config.economyAmount

        if (economy != null && !economy.has(player, amount)) {
            plugin.messages.send(player, "nickname.need-money", "amount" to economy.format(amount))
            return
        }
        // 비용 아이템이 여러 개 켜져 있으면 **하나만** 만족해도 된다.
        // 확인과 차감이 서로 다른 항목을 고르면 이중 차감이 되므로, 여기서 고른 것을 끝까지 쓴다.
        val required = config.costItems
        val chosen = required.firstOrNull { countCostItems(player, it) >= it.amount }
        if (required.isNotEmpty() && chosen == null) {
            plugin.messages.send(
                player,
                "nickname.need-item",
                "item" to required.joinToString(" 또는 ") { itemLabel(it) },
                "amount" to required.first().amount,
            )
            return
        }

        // 비용을 걷기 전에 취소 가능한 이벤트부터 불러 확정한다. 다른 플러그인이 취소하면
        // (욕설 필터 등) 여기서 그냥 끝나고 돈·아이템은 전혀 걷지 않는다.
        // 순서를 반대로 하면(적용 안에서 이벤트를 부르면) 이미 걷은 비용이 환불 없이 사라진다.
        val event = NicknameChangeEvent(player, profile.nickname, nickname)
        if (!event.callEvent()) return

        if (economy != null && !economy.withdraw(player, amount)) {
            plugin.messages.send(player, "nickname.need-money", "amount" to economy.format(amount))
            return
        }
        if (chosen != null) consumeCostItems(player, chosen)

        commitNickname(player, profile, event.newNickname, touchCooldown = true)

        if (economy != null) {
            plugin.messages.send(player, "nickname.paid-money", "amount" to economy.format(amount))
        }
        if (chosen != null) {
            plugin.messages.send(
                player, "nickname.paid-item",
                "item" to itemLabel(chosen), "amount" to chosen.amount,
            )
        }
    }

    /**
     * 유저가 스스로 닉네임을 지우고 원래 아이디로 돌아간다.
     *
     * 되돌리는 것뿐이라 비용도 쿨타임도 걷지 않는다. 관리자를 부르지 않고도
     * 되돌릴 수 있어야 [enforceRealNameOwnership] 의 강제 해제 이후 UX 가 완성된다.
     */
    fun resetOwn(player: Player) {
        if (!config.enabled) {
            plugin.messages.send(player, "nickname.disabled")
            return
        }
        if (!player.hasPermission(PERMISSION)) {
            plugin.messages.send(player, "general.no-permission")
            return
        }
        val profile = plugin.profiles.of(player) ?: run {
            plugin.messages.send(player, "general.profile-loading")
            return
        }
        if (profile.nickname == null) {
            plugin.messages.send(player, "nickname.no-nickname")
            return
        }
        if (applyNickname(player, profile, null, touchCooldown = false)) {
            plugin.messages.send(player, "nickname.reset")
        }
    }

    /**
     * 접속한 플레이어의 **실제 아이디**를 닉네임으로 선점하고 있는 사람이 있으면 풀어 준다.
     *
     * 실명 `nine` 인 계정이 처음 접속했는데 다른 사람이 닉네임 `nine` 을 쓰고 있으면,
     * 명령어에서 "nine" 이 누구를 가리키는지 접속 여부에 따라 달라져 비결정적이 된다.
     * 실명을 우선해 닉네임 쪽을 해제한다.
     *
     * 남의 닉네임을 지우는 동작이므로 반드시 알린다. 그 사람이 접속 중이 아니면
     * [PlayerProfile.nicknameResetNotice] 에 남겨 다음 접속 때 전달한다(서버 재시작에도 살아남는다).
     *
     * **비동기 컨텍스트에서 호출할 것.**
     */
    fun enforceRealNameOwnership(player: Player) {
        if (!config.enabled || !config.unique) return
        val key = NicknameNormalizer.normalize(player.name) ?: return
        val (holderUuid, oldNickname) = runCatching {
            plugin.storage.findNicknameHolder(key, player.uniqueId)
        }.getOrElse {
            plugin.logger.warning("실명 충돌 검사 실패 (${player.name}): ${it.message}")
            null
        } ?: return

        plugin.logger.info("실명 '${player.name}' 과 겹쳐 닉네임 '$oldNickname' 을 해제합니다.")

        val online = Bukkit.getPlayer(holderUuid)
        if (online != null) {
            Sched.entity(plugin, online) { releaseNickname(online, oldNickname) }
            return
        }

        // 오프라인이면 캐시에 없을 수 있다. 임시 로드해 값을 비우고 바로 저장한다.
        val profile = plugin.profiles.cached(holderUuid)
            ?: runCatching { plugin.storage.loadProfile(holderUuid, "") }.getOrNull()
            ?: return
        profile.nickname = null
        profile.nicknameResetNotice = oldNickname
        profile.markDirty()
        if (plugin.profiles.isCached(profile)) plugin.profiles.save(profile) else plugin.profiles.persist(profile)
    }

    /** 접속 중인 대상의 닉네임을 즉시 해제하고 알린다. 대상 소유 스레드에서 호출할 것. */
    private fun releaseNickname(holder: Player, oldNickname: String) {
        val profile = plugin.profiles.of(holder) ?: return
        applyNickname(holder, profile, null, touchCooldown = false)
        plugin.messages.send(holder, "nickname.forced-reset", "nickname" to Text.mini(oldNickname))
    }

    /**
     * 오프라인 중에 닉네임이 강제 해제됐다면 지금 알린다.
     *
     * 접속 처리에서 호출한다. 알린 뒤에는 표시를 비워 다시 뜨지 않게 한다.
     */
    fun deliverPendingNotice(player: Player) {
        val profile = plugin.profiles.of(player) ?: return
        val notice = profile.nicknameResetNotice ?: return
        profile.nicknameResetNotice = null
        profile.markDirty()
        plugin.profiles.save(profile)
        plugin.messages.send(player, "nickname.forced-reset", "nickname" to Text.mini(notice))
    }

    /** 실제 적용. 관리자 명령도 이 경로를 쓴다(비용 없이 바로 이벤트→적용). */
    fun applyNickname(player: Player, profile: PlayerProfile, nickname: String?, touchCooldown: Boolean = true): Boolean {
        val event = NicknameChangeEvent(player, profile.nickname, nickname)
        if (!event.callEvent()) return false
        commitNickname(player, profile, event.newNickname, touchCooldown)
        return true
    }

    /** 이벤트 통과 후 실제로 저장·표시를 반영하는 공통 마무리. */
    private fun commitNickname(player: Player, profile: PlayerProfile, newNickname: String?, touchCooldown: Boolean) {
        profile.nickname = newNickname
        if (touchCooldown) profile.nicknameChangedAt = System.currentTimeMillis()
        profile.markDirty()
        plugin.profiles.save(profile)
        plugin.nameDisplay.refresh(player)

        if (newNickname == null) {
            plugin.messages.send(player, "nickname.reset")
        } else {
            plugin.messages.send(player, "nickname.changed", "nickname" to Text.escape(newNickname))
        }
    }

    // ── 아이템 비용 ────────────────────────────────────────────────────

    /** 진단 명령(`/it checkitem`) 전용. 판별 로직을 그대로 노출한다. */
    fun debugMatches(item: ItemStack, cost: Settings.CostItem): Boolean = matches(item, cost)

    private fun matches(item: ItemStack?, cost: Settings.CostItem): Boolean {
        if (item == null || item.type.isAir) return false
        return when (cost.type) {
            Settings.ItemSourceType.VANILLA -> {
                if (item.type != cost.material) return false
                if (cost.displayName.isBlank()) return true
                val expected = Text.plain(Text.mini(cost.displayName))
                val actual = item.itemMeta?.displayName()?.let(Text::plain) ?: return false
                actual == expected
            }

            // 훅이 없으면 이 항목은 절대 만족되지 않는다. 기동 시 경고로 알린다.
            Settings.ItemSourceType.MMOITEMS ->
                plugin.mmoItems?.matches(item, cost.mmoType, cost.mmoId) == true
        }
    }

    /**
     * 소지품에서 비용 아이템 개수를 센다.
     *
     * `inventory.contents` 는 갑옷·오프핸드까지 포함한다. 입고 있는 장비가 비용으로
     * 사라지면 안 되므로 **보관함과 단축바만** 본다.
     */
    private fun countCostItems(player: Player, cost: Settings.CostItem): Int =
        player.inventory.storageContents.sumOf { if (matches(it, cost)) it!!.amount else 0 }

    private fun consumeCostItems(player: Player, cost: Settings.CostItem) {
        var remaining = cost.amount
        val contents = player.inventory.storageContents
        for (index in contents.indices) {
            if (remaining <= 0) break
            val item = contents[index] ?: continue
            if (!matches(item, cost)) continue
            val take = minOf(remaining, item.amount)
            item.amount -= take
            remaining -= take
            contents[index] = if (item.amount <= 0) null else item
        }
        player.inventory.storageContents = contents
    }

    private fun itemLabel(cost: Settings.CostItem): String = when (cost.type) {
        Settings.ItemSourceType.VANILLA ->
            if (cost.displayName.isBlank()) cost.material.name else Text.plain(Text.mini(cost.displayName))

        Settings.ItemSourceType.MMOITEMS -> cost.mmoId.ifBlank { cost.label }
    }

    companion object {
        const val PERMISSION = "titleforge.nickname"

        /**
         * 관리자가 입력한 닉네임을 저장 가능한 형태로 바꾼다.
         *
         * **관리자에게만 서식을 허용한다**(맞춤 지침 7.5-24). `&c` 같은 레거시 코드와
         * MiniMessage 를 모두 받아 **MiniMessage 원문 하나로 통일**해 둔다. 저장된 값이 항상
         * 그대로 파싱 가능해야 표시 경로에서 이스케이프 없이 색을 낼 수 있다.
         */
        fun formatAdminInput(raw: String): String = Text.fromLegacy(raw)

        /**
         * 유저가 입력한 닉네임을 저장 가능한 형태로 바꾼다.
         *
         * 서식을 **무력화**해서 일반 유저가 색을 붙이지 못하게 한다. 허용 문자 화이트리스트가
         * 이미 `&` 와 `<` 를 막고 있지만, 설정에서 `extra-allowed-chars` 를 넓히면 뚫릴 수
         * 있으므로 입력 경계에서 한 번 더 막는다.
         */
        fun sanitizeUserInput(raw: String): String = Text.escape(Text.stripLegacyCodes(raw))
    }
}

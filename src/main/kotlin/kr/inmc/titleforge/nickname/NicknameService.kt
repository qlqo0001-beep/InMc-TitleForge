package kr.inmc.titleforge.nickname

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.api.event.NicknameChangeEvent
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 닉네임 변경의 검증 · 비용 · 적용을 담당한다.
 *
 * 흐름: 입력 → (동기)형식 검증 → (비동기)중복 검사 → (동기)비용 차감 → 적용 → (비동기)저장
 */
class NicknameService(private val plugin: TitleForgePlugin) {

    private val config: Settings.NicknameSettings get() = plugin.settings.nickname

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

        Sched.async(plugin) {
            val taken = runCatching { plugin.storage.isNicknameTaken(nickname, profile.uuid) }
                .getOrElse {
                    plugin.logger.severe("닉네임 중복 검사 실패: ${it.message}")
                    true
                }
            Sched.entity(plugin, player) {
                if (taken) {
                    plugin.messages.send(player, "nickname.duplicate")
                } else {
                    charge(player, profile, nickname)
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
    }
}

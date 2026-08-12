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

        val input = if (config.inputMode == Settings.InputMode.CHAT) plugin.chatInput else plugin.anvilInput
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
        if (config.itemEnabled && countCostItems(player) < config.itemAmount) {
            plugin.messages.send(
                player,
                "nickname.need-item",
                "item" to itemLabel(),
                "amount" to config.itemAmount,
            )
            return
        }

        if (economy != null && !economy.withdraw(player, amount)) {
            plugin.messages.send(player, "nickname.need-money", "amount" to economy.format(amount))
            return
        }
        if (config.itemEnabled) {
            consumeCostItems(player)
        }

        if (!applyNickname(player, profile, nickname)) return

        if (economy != null) {
            plugin.messages.send(player, "nickname.paid-money", "amount" to economy.format(amount))
        }
        if (config.itemEnabled) {
            plugin.messages.send(player, "nickname.paid-item", "item" to itemLabel(), "amount" to config.itemAmount)
        }
    }

    /** 실제 적용. 관리자 명령도 이 경로를 쓴다. */
    fun applyNickname(player: Player, profile: PlayerProfile, nickname: String?, touchCooldown: Boolean = true): Boolean {
        val event = NicknameChangeEvent(player, profile.nickname, nickname)
        if (!event.callEvent()) return false

        profile.nickname = event.newNickname
        if (touchCooldown) profile.nicknameChangedAt = System.currentTimeMillis()
        profile.markDirty()
        plugin.profiles.save(profile)
        plugin.nameDisplay.refresh(player)

        if (event.newNickname == null) {
            plugin.messages.send(player, "nickname.reset")
        } else {
            plugin.messages.send(player, "nickname.changed", "nickname" to Text.escape(event.newNickname!!))
        }
        return true
    }

    // ── 아이템 비용 ────────────────────────────────────────────────────

    private fun matches(item: ItemStack?): Boolean {
        if (item == null || item.type != config.itemMaterial) return false
        if (config.itemName.isBlank()) return true
        val expected = Text.plain(Text.mini(config.itemName))
        val actual = item.itemMeta?.displayName()?.let(Text::plain) ?: return false
        return actual == expected
    }

    private fun countCostItems(player: Player): Int =
        player.inventory.contents.sumOf { if (matches(it)) it!!.amount else 0 }

    private fun consumeCostItems(player: Player) {
        var remaining = config.itemAmount
        val contents = player.inventory.contents
        for (index in contents.indices) {
            if (remaining <= 0) break
            val item = contents[index] ?: continue
            if (!matches(item)) continue
            val take = minOf(remaining, item.amount)
            item.amount -= take
            remaining -= take
            player.inventory.setItem(index, if (item.amount <= 0) null else item)
        }
    }

    private fun itemLabel(): String =
        if (config.itemName.isBlank()) config.itemMaterial.name else Text.plain(Text.mini(config.itemName))

    companion object {
        const val PERMISSION = "titleforge.nickname"
    }
}

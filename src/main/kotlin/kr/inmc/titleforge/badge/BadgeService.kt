package kr.inmc.titleforge.badge

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.api.event.BadgeEquipEvent
import kr.inmc.titleforge.api.event.BadgeGrantEvent
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Sched
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * 장착 · 지급 · 회수의 단일 진입점. 명령어와 GUI 가 모두 이 클래스를 통한다
 * (칭호/인장 분기는 [BadgeType] 하나로 처리 — 맞춤 지침 7.4-13).
 */
class BadgeService(private val plugin: TitleForgePlugin) {

    /** 잠금 여부. 권한이 설정된 칭호는 권한이 있어야 장착할 수 있다. */
    fun locked(player: Player, badge: Badge): Boolean =
        badge.permission.isNotBlank() && !player.hasPermission(badge.permission)

    /**
     * 슬롯에 장착하거나(id != null) 해제한다(id == null).
     * 메인/엔티티 스레드 전용.
     */
    fun equip(player: Player, slot: EquipSlot, badgeId: String?): Boolean {
        val profile = plugin.profiles.of(player) ?: run {
            plugin.messages.send(player, "general.profile-loading")
            return false
        }
        val type = slot.type

        if (badgeId == null) {
            if (profile.equipped(slot) == null) return false
            if (!BadgeEquipEvent(player, slot, null).callEvent()) return false
            profile.setEquipped(slot, null)
            afterEquip(player, profile, slot)
            plugin.messages.send(player, unequipMessage(slot))
            return true
        }

        val badge = plugin.badges.get(type, badgeId) ?: run {
            plugin.messages.send(player, "badge.not-found", "type" to type.display, "id" to badgeId)
            return false
        }
        if (!profile.has(type, badge.id)) {
            plugin.messages.send(player, "badge.not-owned", "type" to type.display)
            return false
        }
        if (locked(player, badge)) {
            plugin.messages.send(player, "badge.locked", "type" to type.display)
            return false
        }
        if (profile.equipped(slot) == badge.id) {
            plugin.messages.send(player, "badge.already-equipped")
            return false
        }
        if (!BadgeEquipEvent(player, slot, badge).callEvent()) return false

        profile.setEquipped(slot, badge.id)
        afterEquip(player, profile, slot)
        plugin.messages.send(player, equipMessage(slot), "name" to badge.nameComponent)
        return true
    }

    private fun afterEquip(player: Player, profile: PlayerProfile, slot: EquipSlot) {
        when (slot) {
            EquipSlot.STAT -> plugin.profiles.refreshStats(profile)
            EquipSlot.DISPLAY, EquipSlot.SEAL -> plugin.nameDisplay.refresh(player)
        }
        plugin.profiles.save(profile)
    }

    private fun equipMessage(slot: EquipSlot): String = when (slot) {
        EquipSlot.STAT -> "badge.equipped-stat"
        EquipSlot.DISPLAY -> "badge.equipped-display"
        EquipSlot.SEAL -> "badge.equipped-seal"
    }

    private fun unequipMessage(slot: EquipSlot): String = when (slot) {
        EquipSlot.STAT -> "badge.unequipped-stat"
        EquipSlot.DISPLAY -> "badge.unequipped-display"
        EquipSlot.SEAL -> "badge.unequipped-seal"
    }

    /**
     * 지급. 이미 보유 중이면 false.
     * 온라인이면 스텟까지 즉시 반영하고, 오프라인 프로필이면 호출자가 저장을 책임진다.
     */
    fun grant(profile: PlayerProfile, badge: Badge): Boolean {
        val offlinePlayer = Bukkit.getOfflinePlayer(profile.uuid)
        if (profile.has(badge.type, badge.id)) return false
        if (!BadgeGrantEvent(offlinePlayer, badge).callEvent()) return false

        profile.grant(badge.type, badge.id)
        val online = Bukkit.getPlayer(profile.uuid)
        if (online != null) {
            plugin.profiles.refreshStats(profile)
            plugin.messages.send(online, "badge.granted-target", "name" to badge.nameComponent)
        }
        return true
    }

    /** 정의 생성/수정 후 반영. 레지스트리는 즉시, DB 는 비동기로 저장한다. */
    fun persist(badge: Badge) {
        val normalized = badge.normalized()
        plugin.badges.put(normalized)
        Sched.async(plugin) {
            runCatching { plugin.storage.saveBadge(normalized) }
                .onFailure { plugin.logger.severe("칭호 저장 실패 (${normalized.key}): ${it.message}") }
        }
        plugin.profiles.refreshAllOnline()
    }

    /** 정의 삭제. 보유 기록과 장착 상태까지 정리한다. */
    fun delete(type: BadgeType, id: String): Badge? {
        val removed = plugin.badges.remove(type, id) ?: return null
        for (profile in plugin.profiles.cachedProfiles()) {
            profile.revoke(type, id)
        }
        Sched.async(plugin) {
            runCatching {
                plugin.storage.deleteBadge(type, id)
                plugin.storage.purgeOwnership(type, id)
            }.onFailure { plugin.logger.severe("칭호 삭제 실패 ($id): ${it.message}") }
        }
        plugin.profiles.refreshAllOnline()
        return removed
    }

    fun revoke(profile: PlayerProfile, badge: Badge): Boolean {
        if (!profile.revoke(badge.type, badge.id)) return false
        val online = Bukkit.getPlayer(profile.uuid)
        if (online != null) {
            plugin.profiles.refreshStats(profile)
            plugin.nameDisplay.refresh(online)
            plugin.messages.send(online, "badge.taken-target", "name" to badge.nameComponent)
        }
        return true
    }
}

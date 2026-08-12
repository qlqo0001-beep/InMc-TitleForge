package kr.inmc.titleforge.badge

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.api.event.BadgeEquipEvent
import kr.inmc.titleforge.api.event.BadgeGrantEvent
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.player.PlayerProfile
import kr.inmc.titleforge.util.Sched
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.ConcurrentHashMap

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
     * 지급. 이미 보유 중이면 false (기간만 갱신하려면 [extend] 를 쓴다).
     * 온라인이면 스텟까지 즉시 반영하고, 오프라인 프로필이면 호출자가 저장을 책임진다.
     *
     * @param expiresAt 만료 시각(epoch ms). 0 이면 영구.
     */
    @JvmOverloads
    fun grant(profile: PlayerProfile, badge: Badge, expiresAt: Long = PlayerProfile.PERMANENT): Boolean {
        val offlinePlayer = Bukkit.getOfflinePlayer(profile.uuid)
        if (profile.has(badge.type, badge.id)) return false
        if (!BadgeGrantEvent(offlinePlayer, badge).callEvent()) return false

        profile.grant(badge.type, badge.id, expiresAt = expiresAt)
        val online = Bukkit.getPlayer(profile.uuid)
        if (online != null) {
            plugin.profiles.refreshStats(profile)
            plugin.messages.send(online, "badge.granted-target", "name" to badge.nameComponent)
        }
        return true
    }

    // ── 정의 저장 ──────────────────────────────────────────────────────

    /**
     * 저장 대기 중인 정의. 같은 칭호를 연달아 편집하면 마지막 값 하나만 남는다.
     *
     * GUI 에서 등급·아이콘을 연타하면 클릭마다 쓰기가 발생해 커넥션 1개짜리 SQLite 풀이
     * 그대로 막혔다. 여기 모았다가 [flushPending] 이 한 트랜잭션으로 내보낸다.
     */
    private val pending = ConcurrentHashMap<String, Badge>()

    private var flushTask: ScheduledTask? = null

    fun startFlushTask() {
        flushTask?.cancel()
        flushTask = Sched.asyncTimer(plugin, FLUSH_SECONDS, FLUSH_SECONDS) { flushPending() }
    }

    fun stopFlushTask() {
        flushTask?.cancel()
        flushTask = null
    }

    /** 대기 중인 정의를 DB 에 내보낸다. **블로킹**이므로 비동기 컨텍스트에서만 호출할 것. */
    fun flushPending() {
        if (pending.isEmpty()) return
        // 꺼내는 즉시 비운다. 쓰기 도중 들어온 편집은 다음 주기로 넘어간다.
        val batch = pending.keys.toList().mapNotNull { key -> pending.remove(key) }
        if (batch.isEmpty()) return
        runCatching { plugin.storage.saveBadges(batch) }
            .onFailure { error ->
                // 실패분은 되돌려 다음 주기에 다시 시도한다. 그 사이 새 편집이 들어왔다면 그쪽이 최신이다.
                batch.forEach { pending.putIfAbsent(it.key, it) }
                plugin.logger.severe("칭호 저장 실패 (${batch.size}건): ${error.message}")
            }
    }

    /** 특정 칭호의 대기분만 먼저 내보낸다. 이름 변경·삭제 직전에 호출해 유령 행을 막는다. */
    private fun flushPending(type: BadgeType, id: String) {
        val badge = pending.remove("${type.id}:$id") ?: return
        runCatching { plugin.storage.saveBadge(badge) }
            .onFailure { plugin.logger.severe("칭호 저장 실패 (${badge.key}): ${it.message}") }
    }

    /**
     * 정의 생성/수정 후 반영. 레지스트리는 즉시, DB 는 [flushPending] 이 모아서 저장한다.
     *
     * 갱신 범위는 **실제로 바뀐 필드**로 좁힌다. 등급·아이콘처럼 표시에도 스텟에도 영향이
     * 없는 값을 바꿨는데 접속자 전원의 Attribute 를 다시 계산할 이유가 없다.
     */
    fun persist(badge: Badge) {
        val normalized = badge.normalized()
        val previous = plugin.badges.get(normalized.type, normalized.id)

        val statsChanged = previous == null ||
            previous.equipStats != normalized.equipStats ||
            previous.ownStats != normalized.ownStats
        // 이름표·탭리스트에 실제로 나가는 값은 displayName 뿐이다.
        val displayChanged = previous == null || previous.displayName != normalized.displayName
        // 권한이 바뀌면 이미 장착한 사람의 잠금 여부가 달라질 수 있다.
        val permissionChanged = previous != null && previous.permission != normalized.permission

        plugin.badges.put(normalized)
        pending[normalized.key] = normalized

        when {
            statsChanged || permissionChanged -> refreshHolders(normalized)
            displayChanged -> refreshWearers(normalized)
            // 아이콘·등급·숨김·정렬 순서는 GUI 에만 보이므로 아무도 갱신할 필요가 없다.
        }
    }

    /** 정의 삭제. 보유 기록과 장착 상태까지 정리한다. */
    fun delete(type: BadgeType, id: String): Badge? {
        val removed = plugin.badges.remove(type, id) ?: return null
        val affected = plugin.profiles.cachedProfiles().filter { it.has(type, id) || equipsAny(it, type, id) }
        for (profile in affected) {
            profile.revoke(type, id)
        }
        Sched.async(plugin) {
            // 대기 중인 쓰기가 삭제 뒤에 나가면 지운 정의가 되살아난다.
            flushPending(type, id)
            runCatching {
                plugin.storage.deleteBadge(type, id)
                plugin.storage.purgeOwnership(type, id)
            }.onFailure { plugin.logger.severe("칭호 삭제 실패 ($id): ${it.message}") }
        }
        refreshProfiles(affected)
        return removed
    }

    /**
     * ID 변경. DB 를 먼저 바꾸고 성공했을 때만 메모리에 반영한다.
     * 실패한 채로 메모리만 바뀌면 해당 칭호에 아무도 접근할 수 없게 된다.
     *
     * @param onResult 성공 여부를 메인 스레드에서 돌려준다. 실패 사유는 두 번째 인자.
     */
    fun rename(type: BadgeType, oldId: String, newId: String, onResult: (Boolean, String?) -> Unit) {
        val badge = plugin.badges.get(type, oldId)
        if (badge == null) {
            onResult(false, "원본을 찾을 수 없습니다")
            return
        }
        if (plugin.badges.exists(type, newId)) {
            onResult(false, "이미 사용 중인 ID 입니다")
            return
        }

        Sched.async(plugin) {
            // 옛 ID 로 대기 중이던 쓰기가 이름 변경 뒤에 나가면 옛 행이 되살아난다.
            flushPending(type, oldId)
            val error = runCatching { plugin.storage.renameBadge(type, oldId, newId) }.exceptionOrNull()
            Sched.global(plugin) {
                if (error != null) {
                    plugin.logger.severe("ID 변경 실패 ($oldId → $newId): ${error.message}")
                    onResult(false, error.message)
                    return@global
                }
                // 프로필을 먼저 고쳐야 자동 저장이 옛 ID 를 다시 써넣지 않는다.
                val affected = plugin.profiles.cachedProfiles()
                    .filter { it.renameBadge(type, oldId, newId) }
                plugin.badges.remove(type, oldId)
                plugin.badges.put(badge.copy(id = newId))
                refreshProfiles(affected)
                onResult(true, null)
            }
        }
    }

    // ── 갱신 범위 ──────────────────────────────────────────────────────

    private fun equipsAny(profile: PlayerProfile, type: BadgeType, id: String): Boolean =
        EquipSlot.entries.any { it.type == type && profile.equipped(it) == id }

    /** 보유하거나 장착한 사람만. 스텟·권한 변경에 쓴다. */
    private fun refreshHolders(badge: Badge) {
        refreshProfiles(
            plugin.profiles.cachedProfiles()
                .filter { it.has(badge.type, badge.id) || equipsAny(it, badge.type, badge.id) },
        )
    }

    /** 이름표에 이 칭호가 실제로 나오는 사람만. 표시 이름 변경에 쓴다. */
    private fun refreshWearers(badge: Badge) {
        val slot = if (badge.type == BadgeType.SEAL) EquipSlot.SEAL else EquipSlot.DISPLAY
        refreshProfiles(
            plugin.profiles.cachedProfiles().filter { it.equipped(slot) == badge.id },
        )
    }

    private fun refreshProfiles(profiles: Collection<PlayerProfile>) {
        for (profile in profiles) {
            plugin.profiles.refreshStats(profile)
            val player = Bukkit.getPlayer(profile.uuid) ?: continue
            plugin.nameDisplay.refresh(player)
        }
    }

    private companion object {
        /** 정의 저장 배치 주기(초). 하드 크래시 시 이 시간만큼의 편집이 유실될 수 있다. */
        const val FLUSH_SECONDS = 2L
    }

    /**
     * 보유 기간을 바꾼다. 미보유면 false.
     *
     * @param expiresAt 0 이면 영구로 전환
     */
    fun extend(profile: PlayerProfile, badge: Badge, expiresAt: Long): Boolean {
        if (!profile.has(badge.type, badge.id)) return false
        profile.setExpiry(badge.type, badge.id, expiresAt)
        return true
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

package kr.inmc.titleforge.api

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.EquipSlot
import kr.inmc.titleforge.stat.Stat
import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * 다른 플러그인에서 사용하는 공개 API.
 *
 * 모든 조회는 **캐시 전용**이라 메인 스레드에서 자유롭게 호출해도 안전하다.
 * 캐시에 없는(오프라인) 플레이어는 null / 빈 값을 돌려준다.
 */
object TitleForgeApi {

    private val plugin: TitleForgePlugin get() = JavaPlugin.getPlugin(TitleForgePlugin::class.java)

    fun all(type: BadgeType): List<Badge> = plugin.badges.all(type)

    fun badge(type: BadgeType, id: String): Badge? = plugin.badges.get(type, id)

    fun owned(uuid: UUID, type: BadgeType): Set<String> =
        plugin.profiles.cached(uuid)?.owned(type)?.toSet() ?: emptySet()

    fun has(uuid: UUID, type: BadgeType, id: String): Boolean =
        plugin.profiles.cached(uuid)?.has(type, id) == true

    fun equipped(uuid: UUID, slot: EquipSlot): Badge? {
        val profile = plugin.profiles.cached(uuid) ?: return null
        val id = profile.equipped(slot) ?: return null
        return plugin.badges.get(slot.type, id)
    }

    /** 보유 + 장착 + 마일스톤을 합친 값. 스텟 id 로 조회한다. */
    fun stat(uuid: UUID, statId: String): Double = plugin.profiles.cached(uuid)?.stat(statId) ?: 0.0

    fun stat(uuid: UUID, stat: Stat): Double = stat(uuid, stat.id)

    fun stats(uuid: UUID): Map<String, Double> = plugin.profiles.cached(uuid)?.totalStats ?: emptyMap()

    fun equipStats(uuid: UUID): Map<String, Double> = plugin.profiles.cached(uuid)?.equipStats ?: emptyMap()

    fun ownStats(uuid: UUID): Map<String, Double> = plugin.profiles.cached(uuid)?.ownStats ?: emptyMap()

    /** 등록된 스텟 정의 목록. */
    fun statDefinitions(): List<Stat> = plugin.stats.all()

    fun statDefinition(id: String): Stat? = plugin.stats.of(id)

    /** 보유 만료까지 남은 초. 영구면 null, 미보유면 null. */
    fun remainingSeconds(uuid: UUID, type: BadgeType, id: String): Long? {
        val profile = plugin.profiles.cached(uuid) ?: return null
        if (!profile.has(type, id)) return null
        return profile.remainingSeconds(type, id)
    }

    /** 설정된 닉네임. 없으면 null. */
    fun nickname(uuid: UUID): String? = plugin.profiles.cached(uuid)?.nickname

    /** 인장 + 표시 칭호 + 닉네임 조합 컴포넌트. */
    fun nameplate(player: Player): Component =
        plugin.nameDisplay.nameplate(plugin.profiles.of(player), player.name)

    /**
     * 온라인 플레이어에게 지급. 메인 스레드에서 호출할 것.
     *
     * @param durationMillis 보유 기간(밀리초). 0 이면 영구.
     */
    @JvmOverloads
    fun grant(player: Player, type: BadgeType, id: String, durationMillis: Long = 0L): Boolean {
        val profile = plugin.profiles.of(player) ?: return false
        val badge = plugin.badges.get(type, id) ?: return false
        val expiresAt = if (durationMillis > 0L) System.currentTimeMillis() + durationMillis else 0L
        val granted = plugin.badgeService.grant(profile, badge, expiresAt)
        if (granted) plugin.profiles.save(profile)
        return granted
    }

    /** 온라인 플레이어에게서 회수. 메인 스레드에서 호출할 것. */
    fun revoke(player: Player, type: BadgeType, id: String): Boolean {
        val profile = plugin.profiles.of(player) ?: return false
        val badge = plugin.badges.get(type, id) ?: return false
        val revoked = plugin.badgeService.revoke(profile, badge)
        if (revoked) plugin.profiles.save(profile)
        return revoked
    }

    /** 슬롯 장착/해제. 메인 스레드에서 호출할 것. */
    fun equip(player: Player, slot: EquipSlot, id: String?): Boolean =
        plugin.badgeService.equip(player, slot, id)
}

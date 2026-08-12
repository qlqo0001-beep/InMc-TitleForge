package kr.inmc.titleforge.storage

import kr.inmc.titleforge.badge.Badge
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.player.PlayerProfile
import java.util.UUID

/**
 * 저장소 계약. **모든 구현은 블로킹**이며 호출자는 반드시 비동기 컨텍스트여야 한다
 * (맞춤 지침 7.1-1). 유일한 예외는 서버 기동 시 [init] 이다.
 */
interface Storage {

    fun init()

    fun close()

    fun loadBadges(): List<Badge>

    fun saveBadge(badge: Badge)

    fun deleteBadge(type: BadgeType, id: String)

    /** 해당 칭호/인장의 모든 보유 기록 제거. */
    fun purgeOwnership(type: BadgeType, id: String)

    fun loadProfile(uuid: UUID, name: String): PlayerProfile

    fun saveProfile(profile: PlayerProfile)

    fun saveProfiles(profiles: Collection<PlayerProfile>)

    fun findUuidByName(name: String): UUID?

    fun isNicknameTaken(nickname: String, except: UUID?): Boolean

    /** 저장된 모든 플레이어에게 지급. 지급된 행 수를 반환. */
    fun grantToAll(type: BadgeType, id: String, expiresAt: Long): Int

    /** 만료되지 않은 보유 개수 기준 상위 목록. */
    fun topCollectors(type: BadgeType, limit: Int): List<RankEntry>

    /** 특정 플레이어의 순위(1부터). 보유가 없으면 null. */
    fun rankOf(type: BadgeType, uuid: UUID): RankEntry?

    /** 해당 분류에서 1개 이상 보유한 인원 수. */
    fun collectorCount(type: BadgeType): Int
}

/** 순위 1줄. */
data class RankEntry(
    val uuid: UUID,
    val name: String,
    val count: Int,
    val rank: Int,
)

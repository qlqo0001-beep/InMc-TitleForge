package kr.inmc.titleforge.place

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 플레이어가 지금 있는 월드·생물군계. 플레이스홀더가 읽는다.
 *
 * TAB 같은 호출자는 비동기 스레드에서 묻는데, 생물군계는 청크를 읽는 일이라 그 스레드에서 하면 안 된다(규칙 2).
 * 그래서 **소유 스레드에서 물으면 바로 읽고, 아니면 마지막으로 읽은 값**을 준다. 그 값은 이동·순간이동·리스폰 때
 * 소유 스레드에서 새로 읽는다.
 *
 * **아무도 묻지 않으면 이동 사건에서 곧바로 돌아간다** — 이 플레이스홀더를 안 쓰는 서버는 비용이 없다.
 * 이동 중에도 생물군계가 저장되는 4×4×4 칸을 넘을 때만 읽는다.
 */
class PlaceTracker(private val plugin: TitleForgePlugin) : Listener {

    data class Place(val world: String, val biome: String)

    private val cache = ConcurrentHashMap<UUID, Place>()

    @Volatile
    private var wanted = false

    /** 지금 있는 곳. 처음 묻는 비동기 호출이면 아직 모르므로 null — 다음 틱에 채워 둔다. */
    fun of(player: Player): Place? {
        wanted = true
        if (Bukkit.isOwnedByCurrentRegion(player)) return here(player).also { cache[player.uniqueId] = it }
        return cache[player.uniqueId] ?: run {
            refreshLater(player)
            null
        }
    }

    /** 소유 스레드에서만 부른다. */
    fun here(player: Player): Place = at(player.location)

    private fun at(location: Location): Place {
        val world = location.world
        return Place(world.name, world.getBiome(location.blockX, location.blockY, location.blockZ).key.asString())
    }

    private fun refreshLater(player: Player) {
        Sched.entity(plugin, player) {
            if (player.isOnline) cache[player.uniqueId] = here(player)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!wanted) return
        val from = event.from
        val to = event.to
        if (from.blockX shr 2 == to.blockX shr 2 && from.blockY shr 2 == to.blockY shr 2 && from.blockZ shr 2 == to.blockZ shr 2) return
        cache[event.player.uniqueId] = at(to)
    }

    // 순간이동은 도착 청크가 아직 안 읽혔을 수 있어 그 자리에서 읽지 않는다 — 옮겨진 다음 틱에 읽는다.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        if (wanted) refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) {
        if (wanted) refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (wanted) refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (wanted) refreshLater(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        cache.remove(event.player.uniqueId)
    }
}

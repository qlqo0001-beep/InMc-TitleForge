package kr.inmc.titleforge.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.TimeUnit

/**
 * Paper 스케줄러 래퍼.
 *
 * 규칙(맞춤 지침 7.1):
 *  - DB / 파일 / 네트워크 → [async]
 *  - 월드·엔티티 조작 → [entity] 또는 [global]
 *  - Folia 에서도 그대로 동작하도록 리전 스케줄러만 사용한다.
 */
object Sched {

    fun async(plugin: Plugin, block: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getAsyncScheduler().runNow(plugin) { block() }
    }

    fun asyncDelayed(plugin: Plugin, delaySeconds: Long, block: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getAsyncScheduler().runDelayed(plugin, { block() }, delaySeconds, TimeUnit.SECONDS)
    }

    fun asyncTimer(plugin: Plugin, initialSeconds: Long, periodSeconds: Long, block: () -> Unit): ScheduledTask =
        Bukkit.getAsyncScheduler()
            .runAtFixedRate(plugin, { block() }, initialSeconds, periodSeconds, TimeUnit.SECONDS)

    fun global(plugin: Plugin, block: () -> Unit) {
        if (!plugin.isEnabled) return
        Bukkit.getGlobalRegionScheduler().run(plugin) { block() }
    }

    /** 해당 엔티티를 소유한 스레드에서 실행. 엔티티가 사라졌으면 조용히 무시된다. */
    fun entity(plugin: Plugin, entity: Entity, block: () -> Unit) {
        if (!plugin.isEnabled) return
        entity.scheduler.run(plugin, { block() }, null)
    }
}

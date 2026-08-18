package kr.inmc.titleforge.hook

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.badge.BadgeType
import kr.inmc.titleforge.stat.StatKind
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart

/**
 * bStats 통계.
 *
 * ### 무엇을 보내는가
 * bStats 가 기본으로 수집하는 서버 정보(버전·플레이어 수·자바 버전 등) 외에, 여기서는
 * **설정값 집계만** 덧붙인다. 플레이어 이름·UUID·닉네임·칭호 이름 등 식별 가능한 값은
 * 어떤 차트에도 넣지 않는다.
 *
 * ### 끄는 방법
 * - 이 플러그인만: `config.yml` 의 `metrics: false`
 * - 서버 전체: `plugins/bStats/config.yml` 의 `enabled: false`
 *
 * ### 스레드
 * 차트 콜백은 bStats 자체 스케줄러(비메인 스레드)에서 호출된다. 그래서 Bukkit API 를
 * 건드리지 않고, 불변 스냅샷([kr.inmc.titleforge.config.Settings])과 동시성 컬렉션만 읽는다.
 */
class MetricsHook(private val plugin: TitleForgePlugin) {

    private var metrics: Metrics? = null

    fun start() {
        if (!plugin.settings.metricsEnabled) {
            plugin.logger.info("bStats 통계: 사용 안 함 (config.yml 의 metrics: false)")
            return
        }
        if (SERVICE_ID <= 0) {
            // 등록 전에 켜 두면 존재하지 않는 서비스로 계속 전송하게 된다.
            plugin.logger.warning("bStats 서비스 ID 가 설정되지 않아 통계를 보내지 않습니다.")
            return
        }

        metrics = runCatching { Metrics(plugin, SERVICE_ID) }
            .onFailure { plugin.logger.warning("bStats 초기화 실패: ${it.message}") }
            .getOrNull() ?: return

        registerCharts(metrics!!)
    }

    fun stop() {
        runCatching { metrics?.shutdown() }
        metrics = null
    }

    private fun registerCharts(metrics: Metrics) {
        val settings = plugin.settings

        // ── 저장소 ──
        metrics.addCustomChart(SimplePie("storage_type") { settings.storage.type.name })

        // ── 어떤 표시 기능을 켜서 쓰는지 ──
        metrics.addCustomChart(SimplePie("nametag_enabled") { onOff(settings.display.nametag.enabled) })
        metrics.addCustomChart(SimplePie("tablist_enabled") { onOff(settings.display.tablist.enabled) })
        metrics.addCustomChart(SimplePie("chat_enabled") { onOff(settings.display.chatEnabled) })
        metrics.addCustomChart(SimplePie("color_bleed") { onOff(settings.display.colorBleed) })

        // ── 닉네임 ──
        metrics.addCustomChart(SimplePie("nickname_enabled") { onOff(settings.nickname.enabled) })
        metrics.addCustomChart(SimplePie("nickname_input_mode") { settings.nickname.inputMode.name })

        // ── 선택 연동을 실제로 쓰는 비율 ──
        metrics.addCustomChart(SimplePie("hook_placeholderapi") { present("PlaceholderAPI") })
        metrics.addCustomChart(SimplePie("hook_vault") { onOff(plugin.vault != null) })
        metrics.addCustomChart(SimplePie("hook_mythiclib") { onOff(plugin.statApplier.mythicLib != null) })
        metrics.addCustomChart(SimplePie("hook_mmoitems") { present("MMOItems") })

        // ── 규모 (기능 설계 판단용) ──
        metrics.addCustomChart(SingleLineChart("title_count") { plugin.badges.count(BadgeType.TITLE) })
        metrics.addCustomChart(SingleLineChart("seal_count") { plugin.badges.count(BadgeType.SEAL) })
        metrics.addCustomChart(SimplePie("custom_stat_count") { bucket(plugin.stats.ofKind(StatKind.MMO).size) })
    }

    private fun onOff(value: Boolean): String = if (value) "사용" else "사용 안 함"

    private fun present(pluginName: String): String =
        onOff(plugin.server.pluginManager.getPlugin(pluginName) != null)

    /** 개수를 그대로 보내면 파이 조각이 잘게 쪼개져 읽기 어렵다. 구간으로 묶는다. */
    private fun bucket(count: Int): String = when {
        count == 0 -> "0"
        count <= 10 -> "1-10"
        count <= 30 -> "11-30"
        count <= 60 -> "31-60"
        else -> "60+"
    }

    private companion object {
        /**
         * bStats 서비스 ID.
         *
         * https://bstats.org/getting-started 에서 이 플러그인을 등록하면 숫자 ID 를 받는다.
         * 받은 값으로 아래 `0` 을 바꾸면 그때부터 전송이 시작된다.
         * 등록 전에는 [start] 가 경고 한 줄만 남기고 아무것도 보내지 않는다.
         */
        const val SERVICE_ID = 33373
    }
}

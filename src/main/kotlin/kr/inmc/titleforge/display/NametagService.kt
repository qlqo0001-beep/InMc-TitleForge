package kr.inmc.titleforge.display

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.scoreboard.Team
import org.bukkit.util.Transformation
import org.joml.Vector3f
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 여러 줄 머리 위 이름표.
 *
 * ### 왜 줄마다 엔티티를 만들지 않는가
 * [TextDisplay] 는 텍스트 안의 줄바꿈을 **자체적으로 렌더링**한다. 따라서 한 묶음의 줄이
 * 몇 줄이든 엔티티는 1개면 충분하다.
 *
 * ### 왜 그럼에도 엔티티가 2개인가
 * 인장은 본인도 봐야 하지만 닉네임·칭호는 본인 화면에서 가리고 싶다는 요구가 있다.
 * 엔티티 단위로만 숨길 수 있으므로 [Layer.SHARED](모두에게)와
 * [Layer.OTHERS](본인에게는 숨김) 두 개로 나눈다. 둘은 같은 부착점에 타므로 높이를 달리 준다.
 *
 * ### 텔레포트
 * 승객이 붙어 있는 플레이어는 **차원 간 텔레포트가 실패한다**(Paper 문서 명시).
 * 이름표 때문에 다른 플러그인의 `/warp` 가 조용히 실패하면 안 되므로,
 * [detachFor] 로 이동 직전에 떼어내고 다음 티커 주기에 자동으로 다시 붙인다.
 *
 * ### 실제로 보이는 사람에게만 표시
 * 이름표는 플레이어에 탑승한 위치에 뜨는데, 그 위치가 몸 전체를 가리는 지형보다 높이
 * 떠 있으면 몸은 안 보여도 이름표만 보이는 경우가 생긴다. 그래서 위치 기반 가림과는
 * 별개로, [refreshVisibility] 가 **뷰어별로 실제 블록 시야**(`LivingEntity#hasLineOfSight`,
 * 적대 몹이 플레이어를 찾을 때 쓰는 것과 같은 알고리즘)를 확인해 몸이 안 보이는 사람에게는
 * 이름표도 [Player.hideEntity] 로 숨긴다. 인원수 제곱에 비례하는 레이캐스트라
 * 텍스트 갱신보다 훨씬 느린 주기(`visibility-check-ticks`)로 돈다.
 */
class NametagService(private val plugin: TitleForgePlugin) {

    /** 이름표 묶음. 엔티티 1개씩 대응한다. */
    enum class Layer(val tag: String) {
        /** 본인 포함 모두에게 보인다. */
        SHARED("titleforge_nametag"),

        /** 본인에게는 숨긴다. */
        OTHERS("titleforge_nametag_others"),
    }

    private class Handle {
        val displays = EnumMap<Layer, TextDisplay>(Layer::class.java)

        /** 마지막으로 그린 원문. 같은 내용이면 다시 보내지 않는다. */
        val rendered = EnumMap<Layer, String>(Layer::class.java)
    }

    private val handles = ConcurrentHashMap<UUID, Handle>()

    /** ownerUuid → 지금 그 사람의 이름표를 숨기고 있는 viewer uuid 집합. 중복 hide/show 호출을 막는다. */
    private val hiddenFrom = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    private val settings: Settings.NametagSettings get() = plugin.settings.display.nametag

    // ── 수명 주기 ──────────────────────────────────────────────────────

    /** 서버 재시작 등으로 남은 유령 엔티티를 정리한다. */
    fun cleanupOrphans() {
        val tags = Layer.entries.map { it.tag }.toSet()
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClass(TextDisplay::class.java)) {
                if (entity.scoreboardTags.none { it in tags }) continue
                entity.remove()
                removed++
            }
        }
        if (removed > 0) plugin.logger.info("남아 있던 이름표 엔티티 ${removed}개를 정리했습니다.")
    }

    fun handleQuit(player: Player) {
        remove(player.uniqueId, immediate = true)
        restoreVanillaNametag(player)
        // 이 사람이 소유자였던 기록과, 다른 사람 이름표를 숨기고 있던 뷰어 기록을 모두 정리한다.
        hiddenFrom.remove(player.uniqueId)
        for (viewers in hiddenFrom.values) viewers.remove(player.uniqueId)
    }

    fun removeAll() {
        handles.keys.toList().forEach { remove(it, immediate = true) }
        hiddenFrom.clear()
    }

    /**
     * 텔레포트 직전에 승객 관계를 끊는다.
     *
     * 엔티티를 지우기만 하면 되므로 다음 주기의 [refresh] 가 새 위치에 다시 만들어 붙인다.
     */
    fun detachFor(player: Player) {
        remove(player.uniqueId, immediate = true)
    }

    /**
     * @param immediate 지금 이 스레드에서 바로 제거한다. 종료·퇴장처럼 다음 틱을 기대할 수
     *   없는 경로에서만 쓴다. 평상시에는 엔티티를 소유한 리전 스레드로 넘긴다(Folia).
     */
    private fun remove(uuid: UUID, immediate: Boolean = false) {
        val handle = handles.remove(uuid) ?: return
        for (display in handle.displays.values) {
            if (immediate) {
                runCatching { display.remove() }
            } else {
                Sched.entity(plugin, display) { runCatching { display.remove() } }
            }
        }
    }

    // ── 갱신 ───────────────────────────────────────────────────────────

    /**
     * 플레이어 1명의 이름표를 갱신한다. 해당 플레이어를 소유한 스레드에서 호출할 것.
     */
    fun refresh(player: Player) {
        if (!settings.enabled || !settings.hasAnyLine) {
            if (handles.containsKey(player.uniqueId)) {
                remove(player.uniqueId, immediate = true)
                restoreVanillaNametag(player)
            }
            return
        }

        var anyVisible = false
        for (layer in Layer.entries) {
            if (refreshLayer(player, layer)) anyVisible = true
        }
        if (anyVisible) hideVanillaNametag(player)
    }

    /** @return 이 묶음이 화면에 떠 있으면 true. */
    private fun refreshLayer(player: Player, layer: Layer): Boolean {
        val content = renderLines(player, linesOf(layer))
        val handle = handles.computeIfAbsent(player.uniqueId) { Handle() }

        if (content.isEmpty()) {
            handle.displays.remove(layer)?.let { display ->
                Sched.entity(plugin, display) { runCatching { display.remove() } }
            }
            handle.rendered.remove(layer)
            return false
        }

        // 살아 있고 **아직 이 플레이어에 타고 있는** 엔티티만 재사용한다.
        // 텔레포트로 승객 관계가 끊기면 여기서 걸러져 새로 만들어진다.
        val existing = handle.displays[layer]?.takeIf { it.isValid && it.vehicle?.uniqueId == player.uniqueId }
        val display = existing ?: spawn(player, layer)?.also {
            handle.displays[layer] = it
            // 새로 만든 엔티티는 누구에게도 숨겨진 적이 없다(hideEntity 상태는 엔티티별로 따로 관리됨).
            // 예전 엔티티 기준으로 기록해 둔 hiddenFrom 을 그대로 두면 "이미 숨겨져 있다"고 착각해
            // 다음 시야 판정에서 hideEntity 를 다시 부르지 않게 되므로, 여기서 비워 재판정을 강제한다.
            hiddenFrom.remove(player.uniqueId)
        } ?: run {
            // 생성 실패 시 캐시를 남기면 내용이 바뀌기 전까지 영영 재시도하지 않는다.
            handle.displays.remove(layer)
            handle.rendered.remove(layer)
            return false
        }

        // 캐시 갱신은 엔티티를 확보한 **뒤에** 한다. 새로 만든 엔티티는 내용이 같아도 한 번 써야 한다.
        if (existing != null && handle.rendered[layer] == content) return true
        display.text(Text.mini(content))
        handle.rendered[layer] = content
        return true
    }

    private fun linesOf(layer: Layer): List<String> = when (layer) {
        Layer.SHARED -> settings.sharedLines
        Layer.OTHERS -> settings.othersLines
    }

    private fun heightOf(layer: Layer): Double = when (layer) {
        Layer.SHARED -> settings.sharedHeightOffset
        Layer.OTHERS -> settings.othersHeightOffset
    }

    /**
     * 뷰어별 시야 판정을 다시 계산한다. [DisplayTicker] 가 `visibility-check-ticks` 주기로 호출한다.
     *
     * 대상(소유자)마다 온라인 전원을 훑으므로 인원수 제곱에 비례한다. 다른 세계에 있거나
     * `view-range` 밖이면 레이캐스트 없이 즉시 숨김 처리해 비용을 줄인다.
     */
    fun refreshVisibility() {
        if (!settings.enabled || !settings.hideWhenNotVisible) return
        val rangeSq = settings.viewRange * settings.viewRange
        val online = Bukkit.getOnlinePlayers()

        for ((ownerUuid, handle) in handles) {
            if (handle.displays.isEmpty()) continue
            val owner = Bukkit.getPlayer(ownerUuid) ?: continue
            val ownerLocation = owner.location

            for (viewer in online) {
                if (viewer.uniqueId == ownerUuid) continue
                val reachable = viewer.world == owner.world &&
                    viewer.location.distanceSquared(ownerLocation) <= rangeSq

                // 실제 hideEntity/showEntity 호출은 뷰어를 소유한 스레드에서만 안전하다(Folia).
                Sched.entity(plugin, viewer) {
                    val visible = reachable && runCatching { viewer.hasLineOfSight(owner) }.getOrDefault(true)
                    applyVisibility(owner, handle, viewer, visible)
                }
            }
        }
    }

    /** 상태가 실제로 바뀔 때만 hide/showEntity 를 호출한다. */
    private fun applyVisibility(owner: Player, handle: Handle, viewer: Player, visible: Boolean) {
        val hidden = hiddenFrom.getOrPut(owner.uniqueId) { ConcurrentHashMap.newKeySet() }
        val currentlyHidden = hidden.contains(viewer.uniqueId)
        if (currentlyHidden == !visible) return

        if (visible) hidden.remove(viewer.uniqueId) else hidden.add(viewer.uniqueId)
        for (display in handle.displays.values) {
            runCatching {
                if (visible) viewer.showEntity(plugin, display) else viewer.hideEntity(plugin, display)
            }
        }
    }

    /** 설정을 다시 읽었을 때 전원 재생성. */
    fun refreshAll() {
        for (player in Bukkit.getOnlinePlayers()) {
            Sched.entity(plugin, player) {
                remove(player.uniqueId, immediate = true)
                refresh(player)
            }
        }
    }

    /**
     * 설정된 줄들을 MiniMessage 원문 하나로 합친다.
     * 치환 결과가 빈 줄은 자동으로 빠진다.
     */
    private fun renderLines(player: Player, lines: List<String>): String {
        if (lines.isEmpty()) return ""
        return lines.mapNotNull { line ->
            val resolved = plugin.tokens.render(player, line)
            // 치환 결과가 사실상 비어 있으면(태그만 남으면) 그 줄은 버린다.
            if (Text.plain(Text.mini(resolved)).isBlank()) null else resolved
        }.joinToString("<newline>")
    }

    private fun spawn(player: Player, layer: Layer): TextDisplay? = runCatching {
        val display = player.world.spawn(player.location, TextDisplay::class.java) { entity ->
            entity.isPersistent = false
            entity.scoreboardTags.add(layer.tag)
            entity.billboard = Display.Billboard.CENTER
            entity.isSeeThrough = settings.seeThrough
            entity.isShadowed = settings.textShadow
            entity.viewRange = settings.viewRange.toFloat()
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.backgroundColor = if (settings.background) {
                Color.fromARGB(settings.backgroundColor)
            } else {
                Color.fromARGB(0)
            }
            val current = entity.transformation
            entity.transformation = Transformation(
                Vector3f(0f, heightOf(layer).toFloat(), 0f),
                current.leftRotation,
                current.scale,
                current.rightRotation,
            )
        }
        player.addPassenger(display)
        // 본인 전용 숨김. 인장 묶음은 본인도 봐야 하므로 건드리지 않는다.
        if (layer == Layer.OTHERS) player.hideEntity(plugin, display)
        display
    }.getOrElse {
        plugin.logger.warning("이름표 엔티티 생성 실패 (${player.name}): ${it.message}")
        null
    }

    // ── 바닐라 이름표 숨김 ─────────────────────────────────────────────

    private fun hideVanillaNametag(player: Player) {
        if (!settings.hideVanillaNametag) return
        runCatching {
            val team = nametagTeam() ?: return
            if (!team.hasEntry(player.name)) team.addEntry(player.name)
        }
    }

    private fun restoreVanillaNametag(player: Player) {
        runCatching { nametagTeam()?.removeEntry(player.name) }
    }

    private fun nametagTeam(): Team? = runCatching {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        (board.getTeam(TEAM_NAME) ?: board.registerNewTeam(TEAM_NAME)).apply {
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
        }
    }.getOrNull()

    private companion object {
        const val TEAM_NAME = "tf_hidden_name"
    }
}

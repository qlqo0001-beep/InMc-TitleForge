package kr.inmc.titleforge.display

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
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

    /**
     * 마지막으로 관측한 플레이어 위치. **후보군을 미리 좁히는 용도로만** 쓴다.
     *
     * [refreshVisibility] 는 owner 마다 온라인 전원을 훑는데, 각 viewer 의 좌표를 읽으려면
     * viewer 소유 스레드로 넘어가야 해서(Folia) 판정 전에 이미 인원수 제곱만큼의 태스크가
     * 생긴다. 좌표를 평범한 값으로 복사해 두면 그 스케줄 **이전에** 걸러낼 수 있다.
     *
     * 갱신은 [refresh] 안에서 공짜로 이뤄진다 — 어차피 그 플레이어를 소유한 스레드에서
     * `display.refresh-ticks` 주기(기본 1틱)로 호출되므로 별도 태스크가 필요 없다.
     */
    private val positions = ConcurrentHashMap<UUID, Pos>()

    /** 텔레포트가 끝날 때까지 이름표 재생성을 막는 유예. */
    private val teleporting = TeleportGrace(TELEPORT_GRACE_MS)

    private class Pos(val world: UUID, val x: Double, val y: Double, val z: Double) {
        fun withinSquared(other: Pos, rangeSq: Double): Boolean {
            if (world != other.world) return false
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return dx * dx + dy * dy + dz * dz <= rangeSq
        }
    }

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
        positions.remove(player.uniqueId)
        teleporting.release(player.uniqueId)
    }

    fun removeAll() {
        handles.keys.toList().forEach { remove(it, immediate = true) }
        hiddenFrom.clear()
        positions.clear()
        teleporting.clear()
    }

    /**
     * 텔레포트 직전에 승객 관계를 끊는다.
     *
     * 엔티티를 지우기만 하면 되므로 다음 주기의 [refresh] 가 새 위치에 다시 만들어 붙인다.
     */
    fun detachFor(player: Player) {
        // 텔레포트 이벤트는 **실제 이동 전에** 온다. 여기서 떼어내도 표시 갱신 티커가
        // (기본 매 틱) 이동 직전에 다시 붙여 버리면, 그 엔티티가 이동과 함께 분리되어
        // 떠나온 자리에 그대로 남는다. 이동이 끝날 때까지 재생성을 잠깐 막는다.
        teleporting.mark(player.uniqueId)
        remove(player.uniqueId, immediate = true)
        // 이동이 끝난 다음 틱에 바로 풀어 준다. 만료 시각은 이 콜백이 유실됐을 때의 보험이다.
        Sched.entity(plugin, player) { teleporting.release(player.uniqueId) }
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
        // 이 호출은 player 소유 스레드에서만 일어나므로 좌표를 읽어도 안전하다.
        // 여기서 복사해 둔 값이 [refreshVisibility] 의 후보군 사전 필터에 쓰인다.
        val location = player.location
        positions[player.uniqueId] = Pos(player.world.uid, location.x, location.y, location.z)

        // 이동이 끝나기 전에 다시 붙이면 떠나온 자리에 이름표가 남는다.
        if (teleporting.isActive(player.uniqueId)) return

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
        val cacheKey = content.cacheKey

        // 살아 있고 **아직 이 플레이어에 타고 있는** 엔티티만 재사용한다.
        // 텔레포트로 승객 관계가 끊기면 여기서 걸러져 새로 만들어진다.
        val cached = handle.displays[layer]
        val existing = cached?.takeIf { it.isValid && it.vehicle?.uniqueId == player.uniqueId }

        // 재사용할 수 없게 된 엔티티는 **반드시 지우고** 넘어간다.
        // 참조만 덮어쓰면 승객 관계가 끊긴 그 엔티티가 월드에 그대로 남는다.
        // 텔레포트 직후 예전 자리에 이름표가 떠 있던 원인이 이것이었다.
        if (cached != null && existing == null) {
            handle.displays.remove(layer)
            handle.rendered.remove(layer)
            Sched.entity(plugin, cached) { runCatching { cached.remove() } }
        }
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
        if (existing != null && handle.rendered[layer] == cacheKey) return true
        display.text(content.component())
        handle.rendered[layer] = cacheKey
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
     * 대상(소유자)마다 온라인 전원을 훑으므로 인원수 제곱에 비례한다. `owner.location` 은
     * 이 함수를 호출하는 전역 스레드가 아니라 owner 를 소유한 스레드에서 읽어야 안전하므로
     * (Folia), owner 단위로 한 번 더 스케줄한다. viewer 쪽 좌표·시야 판정은 원래도 viewer
     * 소유 스레드([applyVisibility] 호출부)에서 이뤄진다.
     */
    fun refreshVisibility() {
        if (!settings.enabled || !settings.hideWhenNotVisible) return
        val rangeSq = settings.viewRange * settings.viewRange
        val online = Bukkit.getOnlinePlayers()

        // 사전 필터는 좌표 스냅샷이 한 주기 늦을 수 있으므로 여유를 둔다.
        // 여유 안쪽은 어차피 아래에서 실제 좌표로 다시 정확히 판정한다.
        val candidateRangeSq = (settings.viewRange + CANDIDATE_MARGIN) *
            (settings.viewRange + CANDIDATE_MARGIN)

        for ((ownerUuid, handle) in handles) {
            if (handle.displays.isEmpty()) continue
            val owner = Bukkit.getPlayer(ownerUuid) ?: continue

            Sched.entity(plugin, owner) {
                val ownerLocation = owner.location
                val ownerWorld = owner.world
                val ownerPos = Pos(ownerWorld.uid, ownerLocation.x, ownerLocation.y, ownerLocation.z)
                val hidden = hiddenFrom[ownerUuid]

                for (viewer in online) {
                    if (viewer.uniqueId == ownerUuid) continue

                    // ── 태스크를 만들기 전에 후보군을 좁힌다 ──
                    // 이미 숨긴 상대가 여전히 멀리 있으면 상태가 바뀔 일이 없으므로 건너뛴다.
                    // 스냅샷이 없는 상대(갓 접속 등)는 안전하게 후보로 남긴다.
                    if (hidden?.contains(viewer.uniqueId) == true) {
                        val viewerPos = positions[viewer.uniqueId]
                        if (viewerPos != null && !viewerPos.withinSquared(ownerPos, candidateRangeSq)) {
                            continue
                        }
                    }

                    // 실제 hideEntity/showEntity 호출과 viewer.location 읽기는 viewer 를
                    // 소유한 스레드에서만 안전하다(Folia).
                    Sched.entity(plugin, viewer) {
                        val reachable = viewer.world == ownerWorld &&
                            viewer.location.distanceSquared(ownerLocation) <= rangeSq
                        // 위 사전 필터를 통과했더라도 좌표가 갱신됐을 수 있으므로 여기서 다시 확인한다.
                        if (!reachable && hiddenFrom[ownerUuid]?.contains(viewer.uniqueId) == true) {
                            return@entity
                        }
                        val visible = reachable && runCatching { viewer.hasLineOfSight(owner) }.getOrDefault(true)
                        applyVisibility(owner, handle, viewer, visible)
                    }
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
    private fun renderLines(player: Player, lines: List<String>): RenderedLines {
        if (lines.isEmpty()) return RenderedLines.EMPTY
        val session = plugin.tokens.session()
        val kept = lines.mapNotNull { line ->
            val resolved = session.render(player, line)
            // 치환 결과가 사실상 비어 있으면(태그만 남으면) 그 줄은 버린다.
            if (Text.plain(Text.mini(resolved, *session.placeholders())).isBlank()) null else resolved
        }
        return RenderedLines(kept.joinToString("<newline>"), session.placeholders(), session.signature())
    }

    /**
     * 치환 결과 한 묶음.
     *
     * 값이 자리표시자로 빠져 있어 템플릿만으로는 내용이 바뀌었는지 알 수 없다.
     * 그래서 [cacheKey] 로 값까지 포함해 비교한다 — 안 그러면 칭호가 바뀌어도 다시 안 보낸다.
     */
    private class RenderedLines(
        val template: String,
        val placeholders: Array<Pair<String, Any?>>,
        signature: String,
    ) {
        val cacheKey: String = "$template $signature"

        fun isEmpty(): Boolean = template.isEmpty()

        fun component(): Component = Text.mini(template, *placeholders)

        companion object {
            val EMPTY = RenderedLines("", emptyArray(), "")
        }
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

    /**
     * `refresh()` 는 플레이어마다 다른 리전 스레드에서 동시에 실행될 수 있는데(Folia),
     * 여기서 건드리는 팀은 서버에 **하나뿐인 공유 객체**다. Bukkit 의 Team/Scoreboard API 는
     * 스레드 안전을 보장하지 않으므로 동시 mutate 를 막기 위해 락으로 감싼다.
     */
    private val teamLock = Any()

    private fun hideVanillaNametag(player: Player) {
        if (!settings.hideVanillaNametag) return
        synchronized(teamLock) {
            runCatching {
                val team = nametagTeam() ?: return@synchronized
                if (!team.hasEntry(player.name)) team.addEntry(player.name)
            }
        }
    }

    private fun restoreVanillaNametag(player: Player) {
        synchronized(teamLock) {
            runCatching { nametagTeam()?.removeEntry(player.name) }
        }
    }

    private fun nametagTeam(): Team? = runCatching {
        val board = Bukkit.getScoreboardManager().mainScoreboard
        (board.getTeam(TEAM_NAME) ?: board.registerNewTeam(TEAM_NAME)).apply {
            setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
        }
    }.getOrNull()

    private companion object {
        const val TEAM_NAME = "tf_hidden_name"

        /**
         * 후보군 사전 필터에 더하는 여유 거리(블록).
         *
         * 좌표 스냅샷은 최대 `display.refresh-ticks` 만큼 늦을 수 있다. 그 사이 이동한 거리를
         * 덮지 못하면 막 가까워진 상대를 한 주기 늦게 인식한다. 겉넓이가 조금 늘어나는 비용은
         * 인원수 제곱 스케줄에 비하면 무시할 수 있으므로 넉넉히 잡는다.
         */
        const val CANDIDATE_MARGIN = 32.0

        /**
         * 텔레포트 유예 시간(ms).
         *
         * 정상 경로에서는 이동 직후 다음 틱에 곧바로 해제되므로 이 값까지 기다리지 않는다.
         * 해제 콜백이 유실됐을 때 이름표가 영영 안 돌아오는 것만 막는 보험이다.
         */
        const val TELEPORT_GRACE_MS = 1000L
    }
}

package kr.inmc.titleforge.display

import kr.inmc.core.integration.Vanish
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.config.Settings
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.potion.PotionEffectType
import org.bukkit.scoreboard.Team
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
 * ### 왜 승객으로 태우지 않는가
 * 예전에는 `addPassenger` 로 플레이어에 태웠다. 그러면 치장 플러그인이 붙인 승객과 세로로
 * **쌓여서** 이름표가 뜨거나 치장 위치가 밀리고, 승객이 있는 플레이어는 차원 이동에도
 * 제약을 받는다. 지금은 독립 엔티티로 띄우고 [follow] 가 좌표를 따라가게 한다.
 * 이동 패킷은 서버의 엔티티 트래커가 추적 범위 안의 뷰어에게만 델타로 보내며,
 * `teleportDuration` 보간 덕분에 갱신 주기를 늘려도 부드럽다.
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

    /**
     * @param vanished 투명(vanish)인 채로 만든 묶음 — 엔티티가 **기본 안 보임**이다([spawn]). [refresh] 가 지금 투명 여부와 다르면 다시
     *   만든다(core `Vanish.set` 을 거치지 않는 투명 플러그인도 다음 갱신에 맞춰진다).
     */
    private class Handle(val vanished: Boolean) {
        val displays = EnumMap<Layer, TextDisplay>(Layer::class.java)

        /**
         * 기본 안 보임 묶음을 보여 준 viewer — "숨긴 적 없음"이 곧 "보임"이 아니라서 따로 센다([applyVisibility]).
         * [vanished] 일 때만 쓴다.
         */
        val revealed: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

        /**
         * 마지막으로 그린 치환 원문([RenderedLines.sourceKey]). 같으면 파싱도 전송도 하지 않는다.
         * 묶음이 비어 엔티티가 없을 때도 남긴다(그 묶음이 계속 비어 있으면 다시 볼 것이 없다).
         */
        val rendered = EnumMap<Layer, String>(Layer::class.java)
    }

    private val handles = ConcurrentHashMap<UUID, Handle>()

    /** ownerUuid → 지금 그 사람의 이름표를 숨기고 있는 viewer uuid 집합. 중복 hide/show 호출을 막는다. */
    private val hiddenFrom = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * ownerUuid → 공유 이름표(인장 줄)만 숨긴 viewer uuid 집합(타인 인장 끄기).
     * 시야 가림(`hiddenFrom`)과 따로 논다 — 시야로 숨겨진 사람에게는 건드리지 않는다.
     */
    private val sealHiddenFrom = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    /**
     * ownerUuid → 본인에게 숨긴 layer 집합(본인 시점 설정). 타인에게 보이는 것과 무관하다.
     * OTHERS 는 기본 숨김(남에게 보이는 줄), SHARED 는 기본 보임이다.
     */
    private val selfHidden = ConcurrentHashMap<UUID, MutableSet<Layer>>()

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

    /** 서버 재시작 등으로 남은 유령 엔티티를 정리한다. 말풍선([ChatBubbleService.TAG])도 같이. */
    fun cleanupOrphans() {
        val tags = Layer.entries.map { it.tag }.toSet() + ChatBubbleService.TAG
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
        sealHiddenFrom.remove(player.uniqueId)
        for (viewers in hiddenFrom.values) viewers.remove(player.uniqueId)
        for (viewers in sealHiddenFrom.values) viewers.remove(player.uniqueId)
        positions.remove(player.uniqueId)
    }

    fun removeAll() {
        handles.keys.toList().forEach { remove(it, immediate = true) }
        hiddenFrom.clear()
        positions.clear()
    }

    /**
     * 지금 있는 이름표를 지워 다음 갱신 주기에 새로 만들게 한다.
     *
     * 월드 이동·리스폰처럼 좌표가 크게 튀는 경우에 쓴다. 승객이 아니므로 이동 자체를
     * 막을 일이 없고, 평상시에는 [follow] 가 따라가므로 여기까지 올 필요가 없다.
     */
    fun rebuildFor(player: Player) {
        remove(player.uniqueId, immediate = true)
    }

    /**
     * @param immediate 지금 이 스레드에서 바로 제거한다. 종료·퇴장처럼 다음 틱을 기대할 수
     *   없는 경로에서만 쓴다. 평상시에는 엔티티를 소유한 리전 스레드로 넘긴다(Folia).
     */
    private fun remove(uuid: UUID, immediate: Boolean = false) {
        val handle = handles.remove(uuid) ?: return
        selfHidden.remove(uuid)
        // 시야 숨김 기록은 이 묶음 엔티티들에 대한 것이다 — 통째로 다시 만들면(월드 이동·리스폰·투명 전환·리로드) 새 엔티티는 아무에게도
        // 숨겨진 적이 없으니 기록도 비워 다음 시야 판정이 새로 정하게 한다. 안 비우면 시야 판정을 끈 리로드 뒤 영영 숨는다(2026-10-16 검토).
        hiddenFrom.remove(uuid)
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

        if (!settings.enabled || !settings.hasAnyLine) {
            if (handles.containsKey(player.uniqueId)) {
                remove(player.uniqueId, immediate = true)
                restoreVanillaNametag(player)
            }
            return
        }

        // 본체가 안 보이는 상태면 이름표도 없앤다.
        //
        // **바닐라 이름표는 계속 숨긴 채로 둔다.** 여기서 restoreVanillaNametag 를 부르면
        // 실제 아이디가 머리 위에 되살아나 오히려 위치가 더 노출된다.
        // 은신이 풀리면 다음 갱신 주기에 알아서 다시 만들어진다.
        if (concealed(player)) {
            if (handles.containsKey(player.uniqueId)) remove(player.uniqueId, immediate = true)
            return
        }

        // 투명 여부가 만든 때와 다르면 다시 만든다 — 기본 안 보임/보임은 엔티티를 만들 때만 정할 수 있다.
        val vanished = Vanish.isVanished(player)
        if (handles[player.uniqueId]?.vanished?.let { it != vanished } == true) remove(player.uniqueId, immediate = true)

        var anyVisible = false
        for (layer in Layer.entries) {
            if (refreshLayer(player, layer, vanished)) anyVisible = true
        }
        if (anyVisible) hideVanillaNametag(player)
        // 타인 인장 끄기를 켠 뷰어들에게 공유 이름표를 맞춘다(바뀔 때만 넘어간다).
        syncSeals(player)
        syncSelfView(player)
    }

    /**
     * 뷰어를 구분할 수 없는 은신 상태인가. 대상 소유 스레드에서 호출할 것.
     *
     * 투명(vanish)은 기본으로는 여기서 엔티티째 지우지 않는다 — 볼 권한(`inmc.vanish.see`)이 있는 관리자에게는
     * 이름표가 남아야 해서, 투명인 사람의 이름표는 **기본 안 보임**으로 띄우고 볼 수 있는 사람에게만 보여 준다([spawn]).
     * `vanish-metadata` 를 켜면 예전처럼 모두에게서 지운다.
     */
    private fun concealed(player: Player): Boolean = NametagVisibility.concealedFromEveryone(
        spectator = player.gameMode == GameMode.SPECTATOR,
        invisible = player.isInvisible,
        potion = player.hasPotionEffect(PotionEffectType.INVISIBILITY),
        vanishedMeta = Vanish.isVanished(player),
        useVanishMeta = settings.vanishMetadata,
        enabled = settings.hideWhenInvisible,
    )

    /**
     * 투명이 걸리거나 풀렸다(core `Vanish.listen`) — 이름표를 곧바로 지워 다음 갱신에 맞는 모양(기본 안 보임/보임)으로 다시 만든다.
     * 시야 판정 주기(`visibility-check-ticks`)를 기다리면 그동안 투명인 사람의 자리가 이름표로 샌다.
     */
    fun onVanish(player: Player) {
        Sched.entity(plugin, player) { rebuildFor(player) }
    }

    /** @return 이 묶음이 화면에 떠 있으면 true. */
    private fun refreshLayer(player: Player, layer: Layer, vanished: Boolean): Boolean {
        val content = renderLines(player, linesOf(layer))
        val handle = handles.computeIfAbsent(player.uniqueId) { Handle(vanished) }
        val cacheKey = content.sourceKey

        // 살아 있고 **같은 월드에 있는** 엔티티만 재사용한다.
        // 차원을 넘어가면 예전 월드에 남으므로 여기서 걸러져 새 월드에 다시 만들어진다.
        val cached = handle.displays[layer]
        val existing = cached?.takeIf { it.isValid && it.world == player.world }

        // 치환 원문이 지난번과 같으면 파싱하지 않는다 — 이 함수는 매 틱 사람마다 돈다
        // (2026-10-16 테섭 JFR: 값이 같아도 파싱해 메인 표본의 12%).
        if (handle.rendered[layer] == cacheKey) {
            if (cached == null) return false // 지난번에도 빈 묶음
            if (existing != null) {
                follow(player, existing, layer)
                return true
            }
        }

        if (content.isEmpty()) {
            handle.displays.remove(layer)?.let { display ->
                Sched.entity(plugin, display) { runCatching { display.remove() } }
            }
            // 빈 것도 기억한다 — 안 그러면 인장 없는 사람의 인장 줄을 매 틱 다시 파싱한다.
            handle.rendered[layer] = cacheKey
            return false
        }

        // 재사용할 수 없게 된 엔티티는 **반드시 지우고** 넘어간다.
        // 참조만 덮어쓰면 그 엔티티가 예전 월드에 그대로 남는다.
        if (cached != null && existing == null) {
            handle.displays.remove(layer)
            handle.rendered.remove(layer)
            Sched.entity(plugin, cached) { runCatching { cached.remove() } }
        }
        val display = existing ?: spawn(player, layer, handle.vanished)?.also {
            handle.displays[layer] = it
            // 새로 만든 엔티티에는 시야 숨김이 걸려 있지 않다(hideEntity 상태는 엔티티별로 따로 관리됨).
            // 같은 Handle 안에서 이 묶음만 다시 만든 것이면 시야로 숨겨 둔 사람(hiddenFrom)에게 새 엔티티도 숨겨 기록을 참으로 둔다.
            // 예전처럼 기록을 비우면 그대로 있는 다른 묶음이 그 사람에게 숨은 채 남았다 — 다음 판정이 "안 바뀜" 으로 넘어가서(2026-10-16 검토).
            // 통째로 다시 만든 Handle 이면 [remove] 가 기록을 이미 비웠다.
            val fresh = it
            hiddenFrom[player.uniqueId]?.forEach { id ->
                val viewer = Bukkit.getPlayer(id) ?: return@forEach
                Sched.entity(plugin, viewer) {
                    // 실행할 때 다시 본다 — 그 사이 시야 판정이 "보임" 으로 바꿨다면 그쪽이 새 엔티티까지 이미 맞췄다.
                    if (fresh.isValid && handle.displays[layer] === fresh && hiddenFrom[player.uniqueId]?.contains(id) == true) {
                        runCatching { viewer.hideEntity(plugin, fresh) }
                    }
                }
            }
            // 보여 준 기록도 옛 엔티티 기준이다 — 비워야 다음 시야 판정이 새 묶음까지 보여 준다.
            handle.revealed.clear()
            // 타인 인장 끄기 기록도 옛 엔티티 기준이다 — 비워야 아래 syncSeals 가 새 엔티티를 다시 숨긴다.
            if (layer == Layer.SHARED) sealHiddenFrom.remove(player.uniqueId)
        } ?: run {
            // 생성 실패 시 캐시를 남기면 내용이 바뀌기 전까지 영영 재시도하지 않는다.
            handle.displays.remove(layer)
            handle.rendered.remove(layer)
            return false
        }

        // 승객이 아니므로 위치를 직접 따라가게 한다. 실제로 움직였을 때만 옮긴다.
        follow(player, display, layer)

        // 캐시 갱신은 엔티티를 확보한 **뒤에** 한다. 새로 만든 엔티티는 내용이 같아도 한 번 써야 한다.
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
        if (!settings.enabled) return
        // 시야 가림과 은신은 서로 독립이다. 둘 중 하나만 켜져 있어도 이 판정은 돌아야 한다.
        if (!settings.hideWhenNotVisible && !settings.hideWhenInvisible) return
        val useLineOfSight = settings.hideWhenNotVisible
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
                        if (useLineOfSight && !reachable &&
                            hiddenFrom[ownerUuid]?.contains(viewer.uniqueId) == true
                        ) {
                            return@entity
                        }
                        // 베니시 플러그인은 hidePlayer 로 대상을 숨긴다. 그래서 canSee 가 곧
                        // "이 뷰어에게 본체가 보이는가" 이며, 볼 권한이 있는 관리자는 true 라
                        // 이름표가 그대로 남는다.
                        // core 투명 창구도 본다 — hidePlayer 를 쓰지 않는 투명이어도 볼 권한이 없으면 숨긴다.
                        val canSee = Vanish.canSee(viewer, owner) && (
                            !settings.hideWhenInvisible ||
                                runCatching { viewer.canSee(owner) }.getOrDefault(true)
                            )
                        // 시야 가림을 끈 설정(은신만 켬)에서는 결과를 안 쓰니 레이캐스트도 하지 않는다.
                        val lineOfSight = useLineOfSight && reachable &&
                            runCatching { viewer.hasLineOfSight(owner) }.getOrDefault(true)
                        val visible = NametagVisibility.visibleTo(
                            concealed = false, // 전역 은신은 refresh() 에서 엔티티째 지운다
                            canSee = canSee,
                            lineOfSight = lineOfSight,
                            useLineOfSight = useLineOfSight,
                        )
                        applyVisibility(owner, handle, viewer, visible)
                    }
                }
            }
        }
    }

    /**
     * 상태가 실제로 바뀔 때만 hide/showEntity 를 호출한다.
     *
     * 투명인 채로 만든 묶음([Handle.vanished])은 기본 안 보임이라, 숨긴 기록이 없어도 아직 안 보인다 — 보여 준 기록([Handle.revealed])이
     * 없으면 보여 준다. 그래야 나중에 들어온 사람·볼 권한이 있는 관리자에게도 보인다.
     */
    private fun applyVisibility(owner: Player, handle: Handle, viewer: Player, visible: Boolean) {
        // 판정을 예약한 뒤 묶음이 통째로 다시 만들어졌으면(월드 이동·리스폰·리로드) 이 판정은 옛 엔티티의 것이다 — 버린다.
        // 안 버리면 [remove] 가 비운 숨김 기록을 다시 써서 새 엔티티까지 숨긴다(2026-10-16 검토). 새 묶음은 다음 판정이 정한다.
        if (handles[owner.uniqueId] !== handle) return
        val hidden = hiddenFrom.getOrPut(owner.uniqueId) { ConcurrentHashMap.newKeySet() }
        val currentlyHidden = hidden.contains(viewer.uniqueId)
        val reveal = visible && handle.vanished && !handle.revealed.contains(viewer.uniqueId)
        if (currentlyHidden == !visible && !reveal) return

        if (visible) hidden.remove(viewer.uniqueId) else hidden.add(viewer.uniqueId)
        if (handle.vanished) {
            if (visible) handle.revealed.add(viewer.uniqueId) else handle.revealed.remove(viewer.uniqueId)
        }
        val sealHidden = sealHiddenFrom[owner.uniqueId]?.contains(viewer.uniqueId) == true
        for ((layer, display) in handle.displays) {
            // 공유 이름표(인장 줄)는 타인 인장 끄기를 켠 뷰어에게 계속 숨긴다.
            val show = if (layer == Layer.SHARED) visible && !sealHidden else visible
            runCatching {
                if (show) viewer.showEntity(plugin, display) else viewer.hideEntity(plugin, display)
            }
        }
    }

    /**
     * 막 들어온 [viewer] 에게 — 투명인 채로(기본 안 보임) 떠 있는 이름표를 볼 수 있으면(`Vanish.canSee`) 보여 준다.
     * 시야 판정(`refreshVisibility`)이 꺼져 있는 설정에서도 볼 권한이 있는 관리자가 놓치지 않게. viewer 스레드에서(접속 이벤트).
     */
    fun revealVanishedTo(viewer: Player) {
        for ((ownerUuid, handle) in handles) {
            if (!handle.vanished || ownerUuid == viewer.uniqueId || handle.displays.isEmpty()) continue
            val owner = Bukkit.getPlayer(ownerUuid) ?: continue
            if (Vanish.canSee(viewer, owner)) applyVisibility(owner, handle, viewer, visible = true)
        }
    }

    /**
     * 타인 인장 끄기를 한 뷰어에게, 주인들의 공유 이름표를 맞춘다.
     *
     * 설정 토글 때와 주인 갱신 때 부른다. 바뀌는 경우에만 viewer 스레드로 넘어간다 —
     * 매 틱 전원을 훑으면서 태스크를 만들지 않는다.
     */
    fun refreshSealVisibility(viewer: Player) {
        for ((ownerUuid, _) in handles) {
            if (ownerUuid == viewer.uniqueId) continue
            val owner = Bukkit.getPlayer(ownerUuid) ?: continue
            applySeal(owner, viewer)
        }
    }

    /**
     * 주인 갱신 때마다 — 접속 중인 뷰어들의 인장 설정을 공유 이름표에 반영한다.
     * 설정과 숨긴 기록이 어긋난 사람만 본다: 켰는데 아직 안 숨긴 사람, 숨겼는데 이제 끈 사람.
     * 설정은 알림 없이도 바뀔 수 있어(UUID 로 쓰는 길) 갱신마다 맞춘다.
     */
    private fun syncSeals(owner: Player) {
        if (handles[owner.uniqueId]?.displays?.get(Layer.SHARED) == null) return
        val hiders = sealHiders()
        val tracked = sealHiddenFrom[owner.uniqueId]
        for (id in hiders) {
            if (id == owner.uniqueId || tracked?.contains(id) == true) continue
            Bukkit.getPlayer(id)?.let { applySeal(owner, it) }
        }
        tracked?.forEach { id -> if (id !in hiders) Bukkit.getPlayer(id)?.let { applySeal(owner, it) } }
    }

    /** [sealHiders] 를 센 서버 틱과 그 결과. */
    @Volatile
    private var sealHidersMemo: Pair<Int, Set<UUID>> = -1 to emptySet()

    /**
     * "타인 인장 끄기" 를 켠 접속자. 개인 설정 조회가 가볍지 않아 서버 틱마다 한 번만 센다 —
     * 주인마다 접속자 전원에게 물으면 매 틱 인원 제곱이었다(2026-10-16).
     */
    private fun sealHiders(): Set<UUID> {
        val tick = Bukkit.getCurrentTick()
        sealHidersMemo.let { (at, hiders) -> if (at == tick) return hiders }
        val hiders = Bukkit.getOnlinePlayers()
            .filter { kr.inmc.core.integration.PlayerSettings.enabled(it.uniqueId, TitleForgeSettings.HIDE_OTHERS_SEAL, false) }
            .mapTo(HashSet()) { it.uniqueId }
        sealHidersMemo = tick to hiders
        return hiders
    }

    private fun applySeal(owner: Player, viewer: Player) {
        val handle = handles[owner.uniqueId] ?: return
        val display = handle.displays[Layer.SHARED] ?: return
        val wantHidden = kr.inmc.core.integration.PlayerSettings.enabled(
            viewer.uniqueId, kr.inmc.titleforge.display.TitleForgeSettings.HIDE_OTHERS_SEAL, false,
        )
        val tracked = sealHiddenFrom.getOrPut(owner.uniqueId) { ConcurrentHashMap.newKeySet() }
        if (tracked.contains(viewer.uniqueId) == wantHidden) return
        // viewer 엔티티 호출은 viewer 스레드에서만 안전하다(Folia).
        Sched.entity(plugin, viewer) {
            if (wantHidden) {
                tracked.add(viewer.uniqueId)
                runCatching { viewer.hideEntity(plugin, display) }
            } else if (tracked.remove(viewer.uniqueId)) {
                // 시야로 숨겨진 상태면 그대로 둔다 — 다음 시야 판정이 보여준다. 투명인 주인은 볼 수 있는 사람에게만.
                if (hiddenFrom[owner.uniqueId]?.contains(viewer.uniqueId) != true && Vanish.canSee(viewer, owner)) {
                    runCatching { viewer.showEntity(plugin, display) }
                }
            }
        }
    }

    /** 본인이 본인 OTHERS 줄(닉네임·칭호)을 보는가. 둘 중 하나라도 켜져 있으면 본다(줄이 하나라 함께 간다). */
    private fun selfShowsOthers(player: Player): Boolean {
        val id = player.uniqueId
        return kr.inmc.core.integration.PlayerSettings.enabled(
            id, kr.inmc.titleforge.display.TitleForgeSettings.SHOW_NAME, false,
        )
    }

    /** 본인이 본인 SHARED 줄(인장)을 보는가. 기본 보인다. */
    private fun selfShowsShared(player: Player): Boolean =
        kr.inmc.core.integration.PlayerSettings.enabled(
            player.uniqueId, kr.inmc.titleforge.display.TitleForgeSettings.SHOW_SEAL, true,
        )

    /**
     * 설정 토글 때 부른다 — 본인 화면의 본인 이름표만 맞춘다.
     * 타인에게 보이는 것은 건드리지 않는다.
     */
    fun refreshSelfView(owner: Player) {
        syncSelfView(owner)
    }

    /** 주인 갱신 때마다 — 본인에게 보이는 본인 줄을 맞춘다. 바뀌는 경우에만 넘긴다. */
    private fun syncSelfView(owner: Player) {
        val handle = handles[owner.uniqueId] ?: return
        if (handle.displays.isEmpty()) return
        applySelf(owner, handle, Layer.OTHERS, !selfShowsOthers(owner))
        applySelf(owner, handle, Layer.SHARED, !selfShowsShared(owner))
    }

    private fun applySelf(owner: Player, handle: Handle, layer: Layer, wantHidden: Boolean) {
        val display = handle.displays[layer] ?: return
        val tracked = selfHidden.getOrPut(owner.uniqueId) { ConcurrentHashMap.newKeySet() }
        if (tracked.contains(layer) == wantHidden) return
        // 보는 쪽도 가리는 쪽도 본인이라 본인 스레드다. 그래도 스케줄로 넘긴다(관례).
        Sched.entity(plugin, owner) {
            if (wantHidden) {
                tracked.add(layer)
                runCatching { owner.hideEntity(plugin, display) }
            } else if (tracked.remove(layer)) {
                runCatching { owner.showEntity(plugin, display) }
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
     * 설정된 줄들을 치환한다. 파싱(빈 줄 판정·Component)은 [RenderedLines] 가 처음 필요할 때 한다 —
     * [refreshLayer] 는 [RenderedLines.sourceKey] 가 지난번과 같으면 파싱하지 않는다.
     */
    private fun renderLines(player: Player, lines: List<String>): RenderedLines {
        if (lines.isEmpty()) return RenderedLines.EMPTY
        val session = plugin.tokens.session()
        return RenderedLines(lines.map { session.render(player, it) }, session)
    }

    /**
     * 치환 결과 한 묶음.
     *
     * 값이 자리표시자로 빠져 있어 템플릿만으로는 내용이 바뀌었는지 알 수 없다.
     * 그래서 [sourceKey] 로 값까지 포함해 비교한다 — 안 그러면 칭호가 바뀌어도 다시 안 보낸다.
     * [sourceKey] 는 파싱 없이 만들고, 같으면 [template]·[component] 도 같다.
     */
    private class RenderedLines(
        private val resolved: List<String>,
        private val session: TokenRenderer.Session?,
    ) {
        val sourceKey: String = resolved.joinToString("\n") + "\u0000" + session?.signature().orEmpty()

        private val placeholders by lazy(LazyThreadSafetyMode.NONE) { session?.placeholders() ?: emptyArray() }

        /** 치환 결과가 사실상 비어 있는(태그만 남은) 줄을 뺀 MiniMessage 원문. 빈 줄 판정에 파싱이 들어 처음 부를 때 만든다. */
        val template: String by lazy(LazyThreadSafetyMode.NONE) {
            resolved.filterNot { Text.plain(Text.mini(it, *placeholders)).isBlank() }.joinToString("<newline>")
        }

        fun isEmpty(): Boolean = template.isEmpty()

        fun component(): Component = Text.mini(template, *placeholders)

        companion object {
            val EMPTY = RenderedLines(emptyList(), null)
        }
    }

    /**
     * 이름표가 떠 있어야 할 위치.
     *
     * 승객이 아니라 **독립 엔티티**이므로 높이를 좌표로 직접 준다.
     * [Player.getHeight] 를 쓰면 웅크리기·수영으로 키가 줄 때도 머리 위에 붙어 따라온다.
     */
    private fun anchorOf(player: Player, layer: Layer): Location =
        player.location.let { base ->
            Location(base.world, base.x, base.y + player.height + heightOf(layer), base.z)
        }

    /**
     * 플레이어를 따라 이동시킨다.
     *
     * 실제로 움직였을 때만 옮긴다. 서 있으면 패킷이 아예 발생하지 않는다.
     * 이동 패킷 자체는 서버의 엔티티 트래커가 만든다 — 추적 범위 안의 뷰어에게만,
     * 전체 좌표가 아닌 **델타**로 나간다. 몹 한 마리와 같은 비용이다.
     */
    private fun follow(player: Player, display: TextDisplay, layer: Layer) {
        val target = anchorOf(player, layer)
        val current = display.location
        if (current.world == target.world && current.distanceSquared(target) < FOLLOW_EPSILON_SQ) return
        // 엔티티를 소유한 리전 스레드에서만 옮긴다(Folia).
        Sched.entity(plugin, display) { runCatching { display.teleport(target) } }
    }

    private fun spawn(player: Player, layer: Layer, vanished: Boolean): TextDisplay? = runCatching {
        // 투명인 사람의 이름표는 **기본 안 보임**으로 띄운다 — 만들어지는 순간부터 새지 않게. 볼 수 있는 사람에게는 아래에서 보여 준다.
        val display = player.world.spawn(anchorOf(player, layer), TextDisplay::class.java) { entity ->
            entity.isPersistent = false
            if (vanished) entity.isVisibleByDefault = false
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
            // 위치 갱신 사이를 클라이언트가 부드럽게 이어 준다.
            // 이게 있어야 갱신 주기를 늘려도 뚝뚝 끊겨 보이지 않는다.
            entity.teleportDuration = plugin.settings.display.refreshTicks.toInt().coerceIn(1, 59)
            // 높이는 좌표(anchorOf)로 주므로 변형은 건드리지 않는다.
        }
        // **승객으로 태우지 않는다.** 태우면 치장 플러그인이 붙인 승객과 세로로 쌓여
        // 이름표가 뜨거나 치장 위치가 밀리고, 차원 이동에도 제약이 생긴다.
        // 본인 전용 숨김 — 칭호 묶음은 "칭호,닉네임 보기"(SHOW_NAME), 인장 묶음은 "내 인장 보기"(SHOW_SEAL)를 끈 사람에게 처음부터 숨긴다.
        // 인장 묶음을 여기서 안 숨기면, 같은 Handle 안에서 다시 만든 엔티티는 본인 숨김 기록(selfHidden)이 이미 SHARED 라
        // syncSelfView 가 건너뛰어 설정은 꺼져 있는데 인장이 다시 보였다(2026-10-16 검토).
        if (layer == Layer.OTHERS && !selfShowsOthers(player)) player.hideEntity(plugin, display)
        if (layer == Layer.SHARED && !selfShowsShared(player)) player.hideEntity(plugin, display)
        if (vanished) revealToWatchers(player, layer, display)
        display
    }.getOrElse {
        plugin.logger.warning("이름표 엔티티 생성 실패 (${player.name}): ${it.message}")
        null
    }

    /**
     * 기본 안 보임으로 띄운 투명인 사람의 이름표를 볼 수 있는 사람(`Vanish.canSee`)과 본인 설정대로 본인에게 보여 준다.
     * 시야 가림은 다음 시야 판정이 맡는다 — 통째로 새로 만든 묶음이면 `hiddenFrom` 이 비어 다시 판정되고, 같은 Handle 안에서 이 묶음만
     * 다시 만든 것이면 [refreshLayer] 가 시야로 숨겨 둔 사람에게 새 엔티티도 숨긴다(같은 viewer 스케줄러에 여기 보여 주기 뒤로 예약된다).
     */
    private fun revealToWatchers(owner: Player, layer: Layer, display: TextDisplay) {
        val self = if (layer == Layer.SHARED) selfShowsShared(owner) else selfShowsOthers(owner)
        if (self) owner.showEntity(plugin, display)
        for (viewer in Bukkit.getOnlinePlayers()) {
            if (viewer.uniqueId == owner.uniqueId || !Vanish.canSee(viewer, owner)) continue
            if (layer == Layer.SHARED && kr.inmc.core.integration.PlayerSettings.enabled(
                    viewer.uniqueId, TitleForgeSettings.HIDE_OTHERS_SEAL, false,
                )
            ) {
                continue
            }
            Sched.entity(plugin, viewer) { if (display.isValid) runCatching { viewer.showEntity(plugin, display) } }
        }
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
            // 다를 때만 — 서버는 같은 값인지 보지 않고 팀 패킷을 전원에게 다시 보내고 위치 표시줄 웨이포인트까지 다시 잇는다.
            // 매 틱 사람마다 불리는 자리라 인원 N 에 팀 패킷 N²·웨이포인트 약 N³ 이 됐다(2026-10-16 테섭 JFR).
            if (getOption(Team.Option.NAME_TAG_VISIBILITY) != Team.OptionStatus.NEVER) {
                setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER)
            }
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
         * 이 거리(제곱) 안이면 안 옮긴다.
         *
         * 서 있는 플레이어의 이름표를 매 틱 teleport 하면 트래커가 의미 없는 이동 패킷을
         * 만든다. 눈에 안 보일 만큼의 흔들림은 무시한다. (0.01블록)
         */
        const val FOLLOW_EPSILON_SQ = 0.0001

    }
}

package kr.inmc.titleforge.display

import kr.inmc.titleforge.TitleForgePlugin
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 여러 줄 머리 위 이름표.
 *
 * ### 왜 줄마다 엔티티를 만들지 않는가
 * `TextDisplay` 는 텍스트 안의 줄바꿈을 **자체적으로 렌더링**한다. 따라서 줄 수와 무관하게
 * 플레이어당 엔티티는 **1개**면 충분하다. 줄 간격 계산도, 엔티티 3배 트래픽도 생기지 않는다.
 *
 * ### 구조
 * - 플레이어에 [TextDisplay] 를 태워(`addPassenger`) 위치 갱신 패킷을 없앤다.
 * - 높이는 `Transformation` 의 translation 으로 조정한다.
 * - 렌더 결과 문자열이 **바뀐 경우에만** 텍스트를 갱신한다.
 * - 인장을 본인도 확인할 수 있어야 하므로 기본적으로 **자기 자신에게도 보인다**.
 * - 엔티티는 비영속(`isPersistent = false`)이며 퇴장·종료 시 제거하고,
 *   기동 시 남아 있는 유령 엔티티를 태그로 찾아 청소한다.
 */
class NametagService(private val plugin: TitleForgePlugin) {

    private val displays = ConcurrentHashMap<UUID, TextDisplay>()

    /** 마지막으로 렌더링한 원문. 같은 내용이면 다시 보내지 않는다. */
    private val rendered = ConcurrentHashMap<UUID, String>()

    private val settings get() = plugin.settings.display.nametag

    // ── 수명 주기 ──────────────────────────────────────────────────────

    /** 서버 재시작 등으로 남은 유령 엔티티를 정리한다. */
    fun cleanupOrphans() {
        var removed = 0
        for (world in Bukkit.getWorlds()) {
            for (entity in world.getEntitiesByClass(TextDisplay::class.java)) {
                if (!entity.scoreboardTags.contains(ENTITY_TAG)) continue
                entity.remove()
                removed++
            }
        }
        if (removed > 0) plugin.logger.info("남아 있던 이름표 엔티티 ${removed}개를 정리했습니다.")
    }

    fun handleQuit(player: Player) {
        remove(player.uniqueId)
        restoreVanillaNametag(player)
    }

    fun removeAll() {
        displays.keys.toList().forEach(::remove)
        rendered.clear()
    }

    private fun remove(uuid: UUID) {
        displays.remove(uuid)?.let { display ->
            runCatching { display.remove() }
        }
        rendered.remove(uuid)
    }

    // ── 갱신 ───────────────────────────────────────────────────────────

    /**
     * 플레이어 1명의 이름표를 갱신한다. 해당 플레이어를 소유한 스레드에서 호출할 것.
     */
    fun refresh(player: Player) {
        if (!settings.enabled) {
            if (displays.containsKey(player.uniqueId)) {
                remove(player.uniqueId)
                restoreVanillaNametag(player)
            }
            return
        }

        val content = renderLines(player)
        if (content.isEmpty()) {
            remove(player.uniqueId)
            return
        }

        if (rendered[player.uniqueId] == content) return
        rendered[player.uniqueId] = content

        val display = displays[player.uniqueId]?.takeIf { it.isValid } ?: spawn(player) ?: return
        display.text(Text.mini(content))
        hideVanillaNametag(player)
    }

    /** 설정을 다시 읽었을 때 전원 재생성. */
    fun refreshAll() {
        rendered.clear()
        for (player in Bukkit.getOnlinePlayers()) {
            Sched.entity(plugin, player) {
                remove(player.uniqueId)
                refresh(player)
            }
        }
    }

    /**
     * 설정된 줄들을 MiniMessage 원문 하나로 합친다.
     * 치환 결과가 빈 줄은 자동으로 빠진다.
     */
    private fun renderLines(player: Player): String {
        val profile = plugin.profiles.of(player)
        val display = plugin.nameDisplay
        val nickname = display.nicknameText(profile, player.name)

        return settings.lines.mapNotNull { line ->
            val resolved = plugin.placeholders.apply(player, line)
                .replace("<seal>", display.sealMini(profile))
                .replace("<title>", display.titleMini(profile))
                .replace("<nickname>", Text.escape(nickname))
                .replace("<player>", player.name)
            // 치환 결과가 사실상 비어 있으면(태그만 남으면) 그 줄은 버린다.
            if (Text.plain(Text.mini(resolved)).isBlank()) null else resolved
        }.joinToString("<newline>")
    }

    private fun spawn(player: Player): TextDisplay? = runCatching {
        val display = player.world.spawn(player.location, TextDisplay::class.java) { entity ->
            entity.isPersistent = false
            entity.scoreboardTags.add(ENTITY_TAG)
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
                Vector3f(0f, settings.heightOffset.toFloat(), 0f),
                current.leftRotation,
                current.scale,
                current.rightRotation,
            )
        }
        player.addPassenger(display)
        if (!settings.showToSelf) player.hideEntity(plugin, display)
        displays[player.uniqueId] = display
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
        const val ENTITY_TAG = "titleforge_nametag"
        const val TEAM_NAME = "tf_hidden_name"
    }
}

package kr.inmc.titleforge.display

import io.papermc.paper.event.player.AsyncChatEvent
import kr.inmc.core.integration.PlayerSettings
import kr.inmc.core.integration.Vanish
import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Sched
import kr.inmc.titleforge.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 머리 위 말풍선(2026-10-10 — CMI 말풍선을 옮김).
 *
 * - **받는 사람만 본다.** 채팅 이벤트의 최종 `viewers()`(거리 채팅·무시·안 받기가 고친 결과)를 MONITOR 에서 떠 두고,
 *   엔티티는 `isVisibleByDefault = false` 로 띄운 뒤 그 사람들에게만 `showEntity` 한다. 나중에 다가온 사람에게도 안 보인다.
 * - 개인 설정 `titleforge.chat-bubble` 을 끈 사람에게는 보여 주지 않는다.
 * - 관전자·투명(vanish)인 사람의 말은 띄우지 않는다. 떠 있는 동안 투명이 되면 곧바로 지운다([onVanish]).
 * - 따라가기·만료는 **새 타이머 없이** [DisplayTicker] 가 말풍선이 있는 사람에게만 [refresh] 를 부른다.
 * - 이름표와 같은 이유로 승객으로 태우지 않는다([NametagService] 참고).
 */
class ChatBubbleService(private val plugin: TitleForgePlugin) : Listener {

    private class Bubble(val display: TextDisplay, val expiresAt: Long)

    private val bubbles = ConcurrentHashMap<UUID, Bubble>()

    private val settings get() = plugin.settings.display.chatBubble

    /** [DisplayTicker] 가 이 사람에게 [refresh] 를 걸어야 하는가. 없으면 태스크를 만들지 않는다. */
    fun has(uuid: UUID): Boolean = bubbles.containsKey(uuid)

    // MONITOR — 받는 사람·취소가 다 정해진 뒤. 글을 바꾸지 않는다(렌더러 주인은 NameDisplayService).
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        if (!settings.enabled) return
        val speaker = event.player
        // 비동기 스레드에서 목록만 복사해 둔다. Player 객체는 넘기지 않고 uuid 로 — 그 사이 나간 사람은 빠진다.
        val viewers = event.viewers().mapNotNullTo(HashSet()) { (it as? Player)?.uniqueId }
        val message = event.message()
        Sched.entity(plugin, speaker) { show(speaker, message, viewers) }
    }

    /** 말한 사람 스레드에서. */
    private fun show(speaker: Player, message: Component, viewers: Set<UUID>) {
        val s = settings
        if (!s.enabled || !speaker.isOnline || hidden(speaker)) return
        // 새 말이 오면 앞 말풍선은 지운다 — 받는 사람이 달라졌을 수 있어 글만 바꾸지 않는다.
        remove(speaker.uniqueId)

        val plain = Text.plain(message)
        if (plain.isBlank()) return
        val length = plain.codePointCount(0, plain.length)
        // 긴 말은 평문으로 잘라 붙인다(서식은 잃는다 — 자르는 자리가 태그 안일 수 있어서).
        val text = if (length > s.maxLength) {
            Component.text(plain.substring(0, plain.offsetByCodePoints(0, s.maxLength)) + "…")
        } else {
            message
        }

        val display = runCatching {
            speaker.world.spawn(anchorOf(speaker), TextDisplay::class.java) { entity ->
                entity.isPersistent = false
                entity.scoreboardTags.add(TAG)
                // 받는 사람에게만 보인다 — 기본은 아무에게도 안 보이고 아래에서 하나씩 보여 준다.
                entity.isVisibleByDefault = false
                entity.billboard = Display.Billboard.CENTER
                entity.alignment = TextDisplay.TextAlignment.CENTER
                entity.text(text)
                entity.lineWidth = s.lineWidth
                entity.backgroundColor = Color.fromARGB(s.background)
                entity.isShadowed = s.shadow
                entity.teleportDuration = plugin.settings.display.refreshTicks.toInt().coerceIn(1, 59)
                if (s.scale != 1f) {
                    entity.transformation = Transformation(
                        Vector3f(), Quaternionf(), Vector3f(s.scale, s.scale, s.scale), Quaternionf(),
                    )
                }
            }
        }.getOrElse {
            plugin.logger.warning("말풍선 엔티티 생성 실패 (${speaker.name}): ${it.message}")
            return
        }
        bubbles[speaker.uniqueId] = Bubble(display, System.currentTimeMillis() + s.durationMillis(length))

        for (id in viewers) {
            val viewer = Bukkit.getPlayer(id) ?: continue
            if (!PlayerSettings.enabled(viewer.uniqueId, TitleForgeSettings.CHAT_BUBBLE, true)) continue
            // viewer 엔티티 호출은 viewer 스레드에서만 안전하다(Folia). 한 틱 늦게 보이는 것은 괜찮다 — 새는 쪽이 아니다.
            Sched.entity(plugin, viewer) { if (display.isValid) runCatching { viewer.showEntity(plugin, display) } }
        }
    }

    /**
     * [DisplayTicker] 가 말풍선이 있는 사람마다, 그 사람 스레드에서 부른다. 만료·월드 이동·숨음이면 지우고, 아니면 따라간다.
     */
    fun refresh(player: Player) {
        val bubble = bubbles[player.uniqueId] ?: return
        val display = bubble.display
        if (System.currentTimeMillis() >= bubble.expiresAt || !display.isValid ||
            display.world != player.world || hidden(player)
        ) {
            remove(player.uniqueId)
            return
        }
        val target = anchorOf(player)
        if (display.location.distanceSquared(target) < FOLLOW_EPSILON_SQ) return
        Sched.entity(plugin, display) { runCatching { display.teleport(target) } }
    }

    /** 투명이 됐다 — 떠 있는 말풍선을 곧바로 지운다(다음 표시 주기를 기다리지 않는다). */
    fun onVanish(player: Player, vanished: Boolean) {
        if (vanished) remove(player.uniqueId)
    }

    /** 개인 설정을 껐다 — 지금 떠 있는 남의 말풍선도 이 사람에게서 숨긴다. 켜는 것은 다음 말부터. */
    fun settingChanged(viewer: Player) {
        if (PlayerSettings.enabled(viewer.uniqueId, TitleForgeSettings.CHAT_BUBBLE, true)) return
        for (bubble in bubbles.values) {
            val display = bubble.display
            Sched.entity(plugin, viewer) { runCatching { viewer.hideEntity(plugin, display) } }
        }
    }

    fun handleQuit(player: Player) = remove(player.uniqueId, immediate = true)

    fun removeAll() {
        bubbles.keys.toList().forEach { remove(it, immediate = true) }
    }

    /**
     * @param immediate 지금 이 스레드에서 지운다. 퇴장·종료처럼 다음 틱을 기대할 수 없을 때만.
     */
    private fun remove(uuid: UUID, immediate: Boolean = false) {
        val display = bubbles.remove(uuid)?.display ?: return
        if (immediate) runCatching { display.remove() }
        else Sched.entity(plugin, display) { runCatching { display.remove() } }
    }

    /** 말풍선을 띄우면 안 되는 사람 — 관전자·투명(vanish), 이름표가 숨는 설정이면 투명 물약도. */
    private fun hidden(player: Player): Boolean {
        if (player.gameMode == GameMode.SPECTATOR || Vanish.isVanished(player)) return true
        if (!plugin.settings.display.nametag.hideWhenInvisible) return false
        return player.isInvisible || player.hasPotionEffect(PotionEffectType.INVISIBILITY)
    }

    /**
     * 이름표 위. TextDisplay 는 기준점에서 위로 그려지므로, 가장 높은 이름표 묶음의 기준점 + 한 줄 + [offset] 에 둔다.
     * 이름표가 꺼져 있으면 머리 위 [offset].
     */
    private fun anchorOf(player: Player): Location {
        val nametag = plugin.settings.display.nametag
        val above = if (nametag.enabled && nametag.hasAnyLine) {
            maxOf(nametag.sharedHeightOffset, nametag.othersHeightOffset) + NAMETAG_LINE_HEIGHT
        } else {
            0.0
        }
        val base = player.location
        return Location(base.world, base.x, base.y + player.height + above + settings.offset, base.z)
    }

    companion object {
        /** 남은 엔티티 정리([NametagService.cleanupOrphans])가 찾는 태그. */
        const val TAG = "titleforge_bubble"

        /** 이름표 한 줄의 대략 높이(블록, 배율 1). 말풍선이 그 위에 오게. */
        private const val NAMETAG_LINE_HEIGHT = 0.25

        /** 이 거리(제곱) 안이면 안 옮긴다 — 이름표와 같다. */
        private const val FOLLOW_EPSILON_SQ = 0.0001
    }
}

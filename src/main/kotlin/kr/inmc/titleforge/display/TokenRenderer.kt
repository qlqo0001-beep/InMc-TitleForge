package kr.inmc.titleforge.display

import kr.inmc.titleforge.TitleForgePlugin
import kr.inmc.titleforge.util.Text
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 이름표·탭리스트 줄의 `%토큰%` 을 값으로 바꾼다.
 *
 * ### 왜 필요한가
 * 표시 갱신 티커는 초당 여러 번 돌고, 줄에는 `%javascript_biome%` 처럼 무거운 외부
 * 플레이스홀더가 섞여 있다. 매번 전부 다시 계산하면 인원수에 비례해 메인 스레드가 녹는다.
 * 여기서 **토큰 단위로 마지막 계산 결과를 캐시**하고, 설정된 주기가 지난 토큰만 다시 계산한다.
 *
 * ### 캐시 범위
 * - 서버 공통 토큰(TPS·인원·시간 등)은 **전역 캐시 1벌**. 인원수와 무관하게 주기당 1회만 계산한다.
 * - 플레이어마다 값이 다른 토큰(외부 플레이스홀더 포함)은 **플레이어별 캐시**.
 * - 메모리 조회만으로 끝나는 토큰(닉네임·칭호·좌표 등)은 캐시가 오히려 손해라 항상 즉시 계산한다.
 *
 * ### 치환 규칙
 * 토큰은 **한 번만** 치환한다. 치환 결과에 다시 `%...%` 가 들어 있어도 재해석하지 않으므로,
 * 닉네임에 `%foo%` 를 넣어 남의 플레이스홀더를 실행시키는 장난이 통하지 않는다.
 */
class TokenRenderer(private val plugin: TitleForgePlugin) {

    /**
     * 토큰 이름에 쓸 수 있는 문자.
     *
     * `%` 사이를 무엇이든 허용하면 `'50% 확률 / 70% 저항'` 같은 평범한 문장에서 가운데가
     * 통째로 토큰으로 잡혀 사라진다. PlaceholderAPI 가 쓰는 문자만 받아들인다.
     */
    private val tokenPattern = Regex("%([A-Za-z0-9_+\\-.<>{}:]+)%")

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private class Entry(val value: String, val at: Long)

    /** 서버 공통 토큰 캐시. */
    private val globalCache = ConcurrentHashMap<String, Entry>()

    /** 플레이어별 토큰 캐시. */
    private val playerCache = ConcurrentHashMap<UUID, ConcurrentHashMap<String, Entry>>()

    private val intervals get() = plugin.settings.display.placeholderIntervals

    fun handleQuit(uuid: UUID) {
        playerCache.remove(uuid)
    }

    fun clear() {
        globalCache.clear()
        playerCache.clear()
    }

    /** 한 줄을 치환한다. 토큰이 없으면 원문을 그대로 돌려준다. */
    fun render(player: Player, line: String): String {
        if (line.indexOf('%') < 0) return line
        val bleed = plugin.settings.display.colorBleed
        return tokenPattern.replace(line) { match ->
            isolate(resolve(player, match.groupValues[1], match.value), bleed)
        }
    }

    /**
     * 치환한 값이 남긴 서식이 **뒤따르는 내용까지 번지지 않도록** 가둔다.
     *
     * 예: CMI 접두사가 `<dark_green>중` 처럼 닫지 않은 색을 돌려주면, 그대로 이어 붙일 경우
     * 뒤에 오는 `<title>`·`<nickname>` 까지 전부 그 색이 된다.
     *
     * 여는 태그가 없으면(숫자·평문 등) 감쌀 이유가 없어 그대로 둔다.
     *
     * `<reset>` 을 쓰지 않는 이유: `<reset>` 은 **바깥에서 열어 둔 서식까지** 지운다.
     * `<gray>이름: %토큰% 님` 에서 " 님" 의 회색까지 날아가 버린다. 태그로 감싸면 안쪽만
     * 닫히고 바깥 서식은 그대로 이어진다.
     */
    private fun isolate(value: String, bleed: Boolean): String {
        if (bleed || value.indexOf('<') < 0) return value
        return "$ISOLATE_OPEN$value$ISOLATE_CLOSE"
    }

    private fun resolve(player: Player, token: String, raw: String): String {
        val lower = token.lowercase()

        // 1) 메모리 조회로 끝나는 값은 캐시를 거치지 않는다. 맵 조회가 더 비싸다.
        cheapBuiltin(player, lower)?.let { return it }

        // 2) 서버 공통 값은 전역 캐시 1벌을 공유한다.
        if (lower in SERVER_TOKENS) {
            return cached(globalCache, lower) { serverBuiltin(lower) }
        }

        // 3) 나머지(외부 플레이스홀더)는 플레이어별로 캐시한다.
        //    CMI 등은 레거시 색 코드(§a)를 돌려주는데 MiniMessage 가 이를 거부하므로
        //    끼워 넣기 전에 MiniMessage 표기로 바꾼다. 변환 결과째로 캐시된다.
        val cache = playerCache.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        return cached(cache, lower) { Text.fromLegacy(plugin.placeholders.apply(player, raw)) }
    }

    private inline fun cached(cache: ConcurrentHashMap<String, Entry>, key: String, compute: () -> String): String {
        val now = System.currentTimeMillis()
        val existing = cache[key]
        if (existing != null && now - existing.at < intervals.intervalOf(key)) return existing.value
        val value = compute()
        cache[key] = Entry(value, now)
        return value
    }

    /**
     * 계산이 사실상 공짜인 내장 토큰. 없으면 null.
     *
     * 닉네임은 유저 입력이므로 반드시 이스케이프한다 (맞춤 지침 7.5-22).
     * 칭호·인장은 관리자가 넣은 MiniMessage 원문이라 그대로 통과시킨다.
     */
    private fun cheapBuiltin(player: Player, token: String): String? {
        val profile by lazy { plugin.profiles.of(player) }
        val display = plugin.nameDisplay
        return when (token) {
            "tf_player" -> player.name
            "tf_world" -> player.world.name
            "tf_ping" -> player.ping.toString()
            "tf_x" -> player.location.blockX.toString()
            "tf_y" -> player.location.blockY.toString()
            "tf_z" -> player.location.blockZ.toString()
            // 저장된 닉네임은 입력 경계에서 이미 안전해진 MiniMessage 원문이다.
            // 여기서 이스케이프하면 관리자가 /it setnick 으로 넣은 색이 죽는다.
            "tf_nickname" -> display.nicknameText(profile, player.name)
            "tf_seal" -> display.sealMini(profile)
            "tf_title" -> display.titleMini(profile)
            else -> null
        }
    }

    private fun serverBuiltin(token: String): String = when (token) {
        "tf_tps" -> colorizeTps(Bukkit.getTPS().firstOrNull() ?: 20.0)
        "tf_mspt" -> colorizeMspt(runCatching { Bukkit.getAverageTickTime() }.getOrDefault(0.0))
        "tf_online" -> Bukkit.getOnlinePlayers().size.toString()
        "tf_max" -> Bukkit.getMaxPlayers().toString()
        "tf_time" -> LocalDateTime.now().format(timeFormat)
        "tf_date" -> LocalDateTime.now().format(dateFormat)
        else -> ""
    }

    private fun colorizeTps(value: Double): String {
        val settings = plugin.settings.display.tablist
        val clamped = minOf(value, 20.0)
        val color = when {
            clamped >= settings.tpsGood -> "green"
            clamped >= settings.tpsWarn -> "yellow"
            else -> "red"
        }
        return "<$color>${Text.number(clamped, 2)}</$color>"
    }

    private fun colorizeMspt(value: Double): String {
        val settings = plugin.settings.display.tablist
        val color = when {
            value <= settings.msptGood -> "green"
            value <= settings.msptWarn -> "yellow"
            else -> "red"
        }
        return "<$color>${Text.number(value, 2)}</$color>"
    }

    internal companion object {
        /** 전 인원이 같은 값을 보는 토큰. 주기당 1회만 계산한다. */
        val SERVER_TOKENS = setOf("tf_tps", "tf_mspt", "tf_online", "tf_max", "tf_time", "tf_date")

        /**
         * 치환값을 가두는 태그.
         *
         * MiniMessage 는 여는 태그를 닫을 때 **그 안에서 열린 태그도 함께 닫는다.**
         * 그래서 서식에 영향이 거의 없는 태그로 감싸기만 하면 안쪽 색이 밖으로 새지 않는다.
         * 기본 폰트를 명시하는 것뿐이라 눈에 보이는 변화는 없다
         * (바깥에서 커스텀 폰트를 지정해 둔 경우에만 그 값이 기본으로 돌아간다).
         */
        const val ISOLATE_OPEN = "<font:default>"
        const val ISOLATE_CLOSE = "</font>"
    }
}

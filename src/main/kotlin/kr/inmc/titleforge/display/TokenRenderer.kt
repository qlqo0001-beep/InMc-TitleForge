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

    /** 여러 줄을 한 묶음으로 치환한다. 자리표시자 이름을 공유해야 하므로 줄마다 새로 만들지 않는다. */
    fun session(): Session = Session(plugin.settings.display.colorBleed)

    /**
     * 한 번의 표시 갱신 동안의 치환 단위.
     *
     * ### 왜 값을 Component 로 넘기는가
     * 예전에는 치환값을 원문에 그대로 이어 붙였다. 그러면 외부 플레이스홀더가 닫지 않은 색이
     * 뒤따르는 칭호·닉네임까지 번진다. 태그로 감싸는 방법도 써 봤지만, 값 안에 `<reset>` 이
     * 있으면(CMI 는 `§r` 로 끝내는 경우가 많다) **감싼 태그까지 닫혀** 닫는 태그가 짝을 잃고
     * 글자로 출력됐다.
     *
     * Component 로 넘기면 그 자체가 완결된 조각이라 안쪽 서식이 밖으로 나갈 수도, 바깥 구조를
     * 깨뜨릴 수도 없다. `<reset>` 이 들어 있어도 그 조각 안에서만 작용한다.
     */
    inner class Session(private val bleed: Boolean) {

        private val values = ArrayList<Pair<String, Any?>>()
        private val nameByToken = HashMap<String, String>()

        /** 변경 감지용. 치환된 값들의 원문을 이어 붙인 것. */
        private val signatureParts = ArrayList<String>()

        /** @return 자리표시자 이름으로 치환된 템플릿. [placeholders] 와 함께 파싱해야 한다. */
        fun render(player: Player, line: String): String {
            if (line.indexOf('%') < 0) return line
            return tokenPattern.replace(line) { match ->
                val token = match.groupValues[1]
                val value = resolve(player, token, match.value)
                when {
                    // 옵션을 켜 두면 예전처럼 그대로 흘려보낸다.
                    bleed -> value
                    // 서식이 없는 값(숫자·평문)은 번질 것이 없어 그대로 넣는다.
                    value.indexOf('<') < 0 -> value
                    else -> "<${nameByToken.getOrPut(token) { register(value) }}>"
                }
            }
        }

        private fun register(value: String): String {
            val name = "$PLACEHOLDER_PREFIX${values.size}"
            values += name to (Text.mini(value) as Any?)
            signatureParts += value
            return name
        }

        fun placeholders(): Array<Pair<String, Any?>> = values.toTypedArray()

        /** 템플릿만으로는 값 변화를 알 수 없으므로 함께 비교할 문자열. */
        fun signature(): String = signatureParts.joinToString("")
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
         * 치환값 자리표시자 이름의 접두사.
         *
         * MiniMessage 태그 이름 규칙(소문자·숫자·밑줄·하이픈)을 지켜야 하고,
         * 설정에 등장할 법한 이름과 겹치지 않아야 한다.
         */
        const val PLACEHOLDER_PREFIX = "tfph_"
    }
}

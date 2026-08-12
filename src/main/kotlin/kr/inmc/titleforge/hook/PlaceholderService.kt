package kr.inmc.titleforge.hook

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.logging.Logger

/**
 * 외부 플레이스홀더(PlaceholderAPI) 치환기.
 *
 * 이름표·탭리스트 줄에 다른 플러그인의 플레이스홀더를 그대로 쓸 수 있게 해준다.
 * PlaceholderAPI 가 없으면 원문을 그대로 돌려주므로 기능이 죽지 않는다.
 *
 * `PlaceholderAPI.setPlaceholders` 는 리플렉션으로 호출한다. 훅 클래스 밖으로
 * 외부 타입이 새어나가지 않아 PAPI 미설치 서버에서도 안전하다.
 */
class PlaceholderService(private val logger: Logger) {

    private var setPlaceholders: Method? = null

    @Volatile
    private var broken = false

    val available: Boolean get() = setPlaceholders != null && !broken

    fun setup() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) return
        setPlaceholders = runCatching {
            Class.forName("me.clip.placeholderapi.PlaceholderAPI")
                .getMethod("setPlaceholders", Player::class.java, String::class.java)
        }.getOrElse {
            logger.warning("PlaceholderAPI 치환기를 준비하지 못했습니다: ${it.message}")
            null
        }
    }

    /**
     * 문자열 안의 `%...%` 를 치환한다. `%` 가 없으면 즉시 원문을 돌려주므로
     * 고빈도로 호출해도 비용이 거의 없다.
     */
    fun apply(player: Player, input: String): String {
        if (broken || input.indexOf('%') < 0) return input
        val method = setPlaceholders ?: return input
        return runCatching { method.invoke(null, player, input) as? String ?: input }
            .getOrElse {
                broken = true
                logger.warning("플레이스홀더 치환에 실패해 외부 치환을 끕니다: ${it.message}")
                input
            }
    }

    /** 입력에 외부 플레이스홀더가 들어 있는지. 갱신 주기 판단에 쓴다. */
    fun hasExternal(input: String): Boolean = input.indexOf('%') >= 0
}

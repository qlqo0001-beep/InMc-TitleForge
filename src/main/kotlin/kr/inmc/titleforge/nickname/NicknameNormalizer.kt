package kr.inmc.titleforge.nickname

import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.text.Normalizer
import java.util.Locale

/**
 * 닉네임 중복 비교용 정규화.
 *
 * 중복 판정 기준을 코드 여러 곳에 흩어 두면 애플리케이션 검사와 DB 제약이 서로 다른 기준을
 * 쓰게 되어, 검사에서는 통과하는데 저장에서 터지는 상황이 생긴다. 그래서 **비교 키를 만드는
 * 곳은 여기 한 곳뿐**이며 `tf_player.nickname_normalized` 컬럼에도 이 결과가 그대로 들어간다.
 *
 * 정규화 단계
 *  1. **서식 제거** — 색이 붙은 이름과 안 붙은 이름을 같은 것으로 본다.
 *     관리자가 `/it setnick` 으로 `<red>윤` 을 주고 다른 사람이 `윤` 을 쓰면 화면에는 똑같이
 *     "윤" 으로 보이므로, 색만 다른 이름은 중복으로 막아야 사칭을 방지할 수 있다.
 *  2. 앞뒤 공백 제거
 *  3. 유니코드 NFKC — 전각 `Ａ` 와 반각 `A`, 조합형/완성형 한글을 같은 것으로 본다
 *  4. 소문자화([Locale.ROOT] — 터키어 `I` 문제를 피하려 로캘 의존을 없앤다)
 *
 * 시각적으로 유사한 다른 문자(라틴 `A` 와 키릴 `А` 같은 confusable)까지는 접지 않는다.
 * 그건 정규화가 아니라 별도의 사칭 방지 정책이고, 정상 닉네임을 잘못 막을 위험이 있다.
 */
object NicknameNormalizer {

    private val MM = MiniMessage.miniMessage()
    private val PLAIN = PlainTextComponentSerializer.plainText()

    /** @return 비교 키. 입력이 null 이거나 (서식을 뺀 뒤) 비면 null. */
    fun normalize(nickname: String?): String? {
        val raw = nickname ?: return null
        val trimmed = stripFormatting(raw).trim()
        if (trimmed.isEmpty()) return null
        return Normalizer.normalize(trimmed, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }

    /**
     * MiniMessage 태그와 레거시 색 코드를 걷어내고 눈에 보이는 글자만 남긴다.
     *
     * 저장된 닉네임은 항상 MiniMessage 원문이지만, 태그가 망가진 값이 들어와도 비교 자체가
     * 실패하면 안 되므로 파싱에 실패하면 원문을 그대로 쓴다.
     */
    private fun stripFormatting(raw: String): String {
        val withoutLegacy = raw.replace(LEGACY_CODE, "")
        return runCatching { PLAIN.serialize(MM.deserialize(withoutLegacy)) }.getOrDefault(withoutLegacy)
    }

    /** `§a` `&l` `&#rrggbb` 같은 레거시 표기. */
    private val LEGACY_CODE = Regex("[§&](#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])")
}

package kr.inmc.titleforge.nickname

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
 *  1. 앞뒤 공백 제거
 *  2. 유니코드 NFKC — 전각 `Ａ` 와 반각 `A`, 조합형/완성형 한글을 같은 것으로 본다
 *  3. 소문자화([Locale.ROOT] — 터키어 `I` 문제를 피하려 로캘 의존을 없앤다)
 *
 * 시각적으로 유사한 다른 문자(라틴 `A` 와 키릴 `А` 같은 confusable)까지는 접지 않는다.
 * 그건 정규화가 아니라 별도의 사칭 방지 정책이고, 정상 닉네임을 잘못 막을 위험이 있다.
 */
object NicknameNormalizer {

    /** @return 비교 키. 입력이 null 이거나 공백뿐이면 null. */
    fun normalize(nickname: String?): String? {
        val trimmed = nickname?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return Normalizer.normalize(trimmed, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}

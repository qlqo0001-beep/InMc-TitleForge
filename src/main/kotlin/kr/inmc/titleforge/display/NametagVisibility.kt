package kr.inmc.titleforge.display

/**
 * 이름표를 보여 줄지 말지의 판정.
 *
 * 이름표는 플레이어와 **별개의 엔티티**라, 본체가 안 보이게 돼도 저절로 사라지지 않는다.
 * 투명 물약을 마시거나 베니시한 사람의 위치가 이름표로 그대로 새는 것을 막는다.
 *
 * 판정을 두 갈래로 나누는 기준은 **뷰어를 구분할 수 있느냐** 다.
 *
 *  - [concealedFromEveryone] — 구분할 수 없는 은신(관전자·투명). 전원에게 숨긴다.
 *  - [visibleTo] — 뷰어별 판정. 베니시는 `Player#canSee` 가 곧 "이 사람에게 본체가 보이는가" 다.
 *
 * Bukkit 에 의존하지 않는 순수 로직이라 단위 테스트로 고정한다.
 */
object NametagVisibility {

    /**
     * 뷰어를 구분할 방법이 없어 **전원에게** 숨겨야 하는 상태인가.
     *
     * @param spectator 관전자 모드. 일반 플레이어에게 본체가 보이지 않는다.
     * @param invisible 엔티티의 invisible 플래그. 플러그인이 직접 줄 수도 있다.
     * @param potion 투명 물약 효과.
     * @param vanishedMeta `vanished` 메타데이터(CMI·EssentialsX·SuperVanish 공통 표식).
     * @param useVanishMeta 위 표식을 판정에 쓸지. **끄는 것이 기본**이다 —
     *   이 표식은 뷰어를 구분하지 못해, 켜면 베니시를 볼 권한이 있는 관리자에게도
     *   이름표가 사라진다. `hidePlayer` 를 쓰는 일반적인 베니시는 [visibleTo] 의
     *   `canSee` 로 이미 걸러지므로 켤 필요가 없다.
     * @param enabled 기능 자체가 켜져 있는지. 꺼져 있으면 어떤 상태든 숨기지 않는다.
     */
    fun concealedFromEveryone(
        spectator: Boolean,
        invisible: Boolean,
        potion: Boolean,
        vanishedMeta: Boolean,
        useVanishMeta: Boolean,
        enabled: Boolean,
    ): Boolean {
        if (!enabled) return false
        return spectator || invisible || potion || (useVanishMeta && vanishedMeta)
    }

    /**
     * 이 뷰어에게 이름표를 보여 줄지.
     *
     * @param concealed [concealedFromEveryone] 결과.
     * @param canSee 이 뷰어가 대상 본체를 볼 수 있는지(`viewer.canSee(owner)`).
     *   베니시 플러그인이 `hidePlayer` 로 숨긴 대상은 여기서 false 가 된다.
     *   **볼 권한이 있는 관리자는 true 라 이름표가 유지된다.**
     * @param lineOfSight 블록에 가리지 않고 실제로 보이는지(레이캐스트 결과).
     * @param useLineOfSight 시야 가림 판정을 쓸지(`hide-when-not-visible`).
     *   꺼져 있으면 [lineOfSight] 값은 무시한다.
     */
    fun visibleTo(
        concealed: Boolean,
        canSee: Boolean,
        lineOfSight: Boolean,
        useLineOfSight: Boolean,
    ): Boolean {
        if (concealed) return false
        if (!canSee) return false
        return !useLineOfSight || lineOfSight
    }
}

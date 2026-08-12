# InMc-TitleForge

칭호(Title) · 인장(Seal) · 닉네임(Nickname)을 한 플러그인에서 관리합니다.
Paper 26.1+ / Java 25 / Kotlin / Gradle.

전체 설계와 개발 규칙은 [PLAN.md](PLAN.md) 를 참고하세요.

---

## 핵심 개념

| 개념 | 설명 |
|---|---|
| **칭호** | 모을수록 강해지는 수집형 요소. **보유스텟**(가지고만 있어도 적용) + **장착스텟**(장착 시 추가) |
| **인장** | 칭호와 동일한 구조의 별도 시스템. **명예 표시 전용, 스텟 없음** |
| **3개 슬롯** | `표시 칭호`(보이는 것) / `능력치 칭호`(스텟만) / `인장` — 서로 독립이며 동시 장착 |
| **닉네임** | 인게임 표시 이름 변경. 허용 문자·길이·비용·쿨타임 전부 설정 가능 |
| **보유 기한** | 기본은 영구. 기간을 붙여 지급하면 만료 시 자동 회수됩니다 |
| **수집 순위** | 만료되지 않은 보유 개수 기준 서버 순위 |

표시 칭호와 능력치 칭호를 다르게 둘 수 있습니다.
예) 보이는 건 `[전설의 개척자]`, 실제 스텟은 `[광전사]`.

## 빌드

```bash
./gradlew build          # build/libs/InMc-TitleForge-1.0.0.jar
./gradlew test           # 순수 로직 단위 테스트
python3 tools/verify_gui.py   # 컴파일 없이 GUI 정적 검증
```

`gradle.properties` 의 `paperApiVersion` 이 서버 버전과 맞는지 확인하세요.

## 스텟 편집 (관리자)

`/it admin` → 칭호 클릭 → **스텟 편집**. 장착 스텟과 보유 스텟을 한 화면에서 설정합니다.

분류별로 한 줄씩, 8개를 넘으면 다음 줄로 이어지고 4줄을 넘으면 **페이지**로 넘어갑니다.
기본 구성은 **63종 · 3페이지**입니다.

```
1페이지  [전투]    공격력 공격속도 공격넉백 휩쓸기피해 치명타확률 치명타피해 …
         [전투+]   무기피해 물리피해 마법피해 투사체피해 스킬피해 PvE피해 …
         [방어]    최대체력 방어력 방어강도 넉백저항 흡수체력 폭발넉백저항 …
2페이지  [방어+]   피해감소 PvE방어 PvP방어 체력재생 막기위력 막기확률 …
         [이동]    이동속도 점프력 안전낙하거리 중력 계단높이 웅크리기속도 …
3페이지  [자원]    최대마나 마나재생 최대스태미나 스태미나재생 최대스텔륨 …
         [상호작용] 채굴속도 채굴효율 수중채굴속도 블록도달거리 개체도달거리 산소보너스
         [유틸리티] 행운 쿨다운감소 경험치보너스 드랍률보너스
```

각 아이콘 로어에 **현재 장착/보유 값**과 함께 스텟 종류(바닐라 / MMOItems + 연동 여부 / 가상),
설명, 바닐라 Attribute 키, 적용 방식, 플레이어 기본값, 권장 범위가 표시되므로
따로 찾아볼 필요가 없습니다.

| 클릭 | 동작 |
|---|---|
| 좌클릭 | 장착 스텟 값 입력 |
| Shift+좌클릭 | 보유 스텟 값 입력 |
| 우클릭 | 장착 스텟 제거 (확인 창) |
| Shift+우클릭 | 보유 스텟 제거 (확인 창) |

값은 채팅으로 입력하며 **입력한 문장은 채팅·콘솔 어디에도 기록되지 않습니다**
(입력 세션 동안 `AsyncChatEvent` 를 `LOWEST` 우선순위에서 즉시 취소합니다.
동일 우선순위에서 채팅을 기록하는 다른 플러그인이 있다면 순서가 보장되지 않으니,
그런 환경에서는 `nickname.input-mode` 처럼 모루 입력으로 전환하는 것을 권장합니다).

- 입력 단위는 **화면에 보이는 그대로**입니다. 이동 속도에 `10` → `+10%`
- `0` 또는 `제거` → 해당 값 삭제 / `취소` → 변경 없이 종료
- 권장 범위를 벗어나면 경고 후 적용되며, 대기 시간은 `gui.chat-input-timeout-seconds`(기본 60초)

## 보유 기한

기본은 **영구**입니다. 지급할 때 기간을 붙이면 만료 시 자동으로 회수되고,
장착 중이었다면 해제되며 접속 중이면 알림이 갑니다.

```
/it give <플레이어> title <ID> 30d      30일
/it give <플레이어> seal <ID> 12h       12시간
/it extend <플레이어> title <ID> perm   영구로 전환
/it giveall title <ID> 1w               전체 지급 (7일)
```

표기: `30d` `12h` `90m` `2w` `3mo` `1y` `1d12h` `7일` `3시간` `perm`(영구)
보관함 로어에 `보유 기한: 6일 3시간 남음` 으로 표시됩니다.

## 스텟 체계 (기본 63종)

MythicLib(MMOItems)의 `SharedStat` 목록 전체를 담았습니다. 중복 적용을 피하기 위해
**바닐라 Attribute 로 처리되는 항목은 바닐라 쪽에**, MythicLib 전용 항목은 MMO 쪽에 넣었습니다.

### 바닐라 28종 — 코드 내장, 외부 플러그인 없이 항상 동작

```
전투      공격력 · 공격 속도 · 공격 넉백 · 휩쓸기 피해
방어      최대 체력 · 방어력 · 방어 강도 · 넉백 저항 · 흡수 체력 ·
          폭발 넉백 저항 · 낙하 피해 배율 · 화상 지속시간
이동      이동 속도 · 점프력 · 안전 낙하 거리 · 중력 · 계단 높이 ·
          웅크리기 속도 · 지형 이동 효율 · 수중 이동 효율 · 크기
상호작용  채굴 속도 · 채굴 효율 · 수중 채굴 속도 · 블록 도달 거리 ·
          개체 도달 거리 · 산소 보너스
유틸      행운
```

### MMOItems 34종 — `stats.yml`, MythicLib 있을 때 적용

```
전투    치명타 확률/피해 · 주문 치명타 확률/피해 · 무기·물리·마법·투사체·스킬 피해 ·
        PvE/PvP/언데드 피해 · 생명력 흡수 · 주문 흡혈
방어    피해 감소 · PvE/PvP 방어 · 체력 재생 ·
        막기 위력/확률/쿨감 · 회피 확률/쿨감 · 패링 확률/쿨감
이동    감속 저항
자원    최대 마나/스태미나/스텔륨 + 각 재생
유틸    쿨다운 감소 · 경험치 보너스
```

MythicLib 이 없으면 MMO 스텟은 값만 보관되고 API·플레이스홀더로 노출되며, GUI 에
`미연동` 으로 표시됩니다. **바닐라 28종은 그 경우에도 그대로 동작합니다.**

원소 스텟(`FIRE_DAMAGE` 등)은 서버마다 원소 이름이 달라 기본 제공하지 않으며,
`stats.yml` 하단 주석의 예시대로 추가하면 됩니다. 스텟 추가·삭제·설명 변경이 모두
가능하고, 바닐라 스텟의 설명·아이콘·권장 범위도 같은 파일에서 덮어쓸 수 있습니다
(정의 자체는 지워지지 않습니다).

## 여러 줄 이름표 / 탭리스트

둘 다 기본은 꺼짐이며 `config.yml` 에서 켭니다.

```yaml
display:
  nametag:
    enabled: true
    lines:
      - '<seal>'          # 인장
      - '%vault_prefix%'  # 다른 플러그인 칭호
      - '<title><nickname>'
    show-to-self: true    # 본인에게도 보임 (자기 인장 확인용)
```

`TextDisplay` 하나에 줄바꿈으로 렌더링하므로 줄이 늘어도 **엔티티는 플레이어당 1개**입니다.
탑승 방식이라 위치 갱신 패킷이 없고, 내용이 바뀔 때만 다시 보냅니다.

탭리스트 머리말/꼬리말에는 `<tps> <mspt> <online> <max> <ping> <time> <date> <world>`
같은 내장 토큰과 다른 플러그인의 `%플레이스홀더%` 를 함께 쓸 수 있습니다.

## 명령어

`/it` 을 입력하면 권한에 맞는 명령어 목록이 출력됩니다. (별칭: `/titleforge`, `/tf`, `/칭호`)

### 유저

```
/it menu                     메인 GUI
/it title | /it seal         칭호 · 인장 보관함
/it info [플레이어]           내 정보
/it equip <칭호ID>            능력치 슬롯 장착
/it show  <칭호ID>            표시 슬롯 장착
/it seal  <인장ID>            인장 장착
/it unequip <stat|show|seal>  해제
/it nick                     닉네임 변경 (팝업 입력)
/it rank [title|seal]        수집 개수 순위
```

### 관리자 (`titleforge.admin`)

```
/it create <title|seal> <ID> [표시이름...]
/it delete <title|seal> <ID>
/it edit <title|seal> <ID> name|lore|rarity|icon|permission|hidden|order <값...>
/it edit <title|seal> <ID> stat <equip|own> <스텟> <수치>
/it give <플레이어> <title|seal> <ID> [기간]   (오프라인 지원)
/it take <플레이어> <title|seal> <ID>
/it extend <플레이어> <title|seal> <ID> <기간>
/it giveall <title|seal> <ID> [기간]
/it rank refresh                              순위 캐시 비우기
/it setnick <플레이어> <닉네임> | /it resetnick <플레이어>
/it admin                                     관리 GUI
/it reload
```

ID 에는 **한글도 사용할 수 있습니다** (`/it create title 전설의개척자 ...`).

## 권한

| 권한 | 기본값 | 설명 |
|---|---|---|
| `titleforge.use` | 모두 | 명령어·GUI 사용 |
| `titleforge.nickname` | 모두 | 닉네임 변경 |
| `titleforge.info.other` | OP | 타인 정보 조회 |
| `titleforge.admin` | OP | 관리 전체 |

칭호별로 `permission` 을 지정하면 해당 권한이 있어야 장착할 수 있습니다.

## 플레이스홀더 (PlaceholderAPI)

```
%titleforge_nickname%          현재 닉네임 (없으면 실제 아이디)
%titleforge_realname%          실제 아이디
%titleforge_title_display%     표시 칭호
%titleforge_title_stat%        능력치 칭호
%titleforge_seal%              인장
%titleforge_nameplate%         인장 + 표시칭호 + 닉네임
%titleforge_title_count%       보유 칭호 수
%titleforge_title_total%       전체 칭호 수
%titleforge_title_percent%     수집률
%titleforge_seal_count/total/percent%
%titleforge_stat_<스텟ID>%      스텟 총합
%titleforge_stat_equip_<스텟ID>%  장착분
%titleforge_stat_own_<스텟ID>%    보유분
%titleforge_has_title_<ID>%    yes / no
%titleforge_has_seal_<ID>%     yes / no
%titleforge_expiry_title_<ID>% 남은 기간 ("6일 3시간" / "영구")
%titleforge_expiry_seconds_title_<ID>%  남은 초 (영구는 -1)
%titleforge_rank_title%        내 칭호 수집 순위
%titleforge_rank_seal%         내 인장 수집 순위
%titleforge_rank_top_title_1%  1위 이름
%titleforge_rank_top_title_1_count%  1위 보유 수
```

`_mini` 접미사(`%titleforge_title_display_mini%`)를 붙이면 MiniMessage 원문을 그대로 돌려줍니다.

## 다른 플러그인에서 사용하기

```kotlin
import kr.inmc.titleforge.api.TitleForgeApi

TitleForgeApi.stat(player.uniqueId, "max_health")
TitleForgeApi.owned(player.uniqueId, BadgeType.TITLE)
TitleForgeApi.grant(player, BadgeType.TITLE, "first_join")            // 영구
TitleForgeApi.grant(player, BadgeType.TITLE, "이벤트", 7 * 86400_000L)  // 7일
TitleForgeApi.remainingSeconds(player.uniqueId, BadgeType.TITLE, "이벤트")
TitleForgeApi.statDefinitions()
TitleForgeApi.nameplate(player)
```

이벤트: `BadgeGrantEvent`, `BadgeEquipEvent`, `NicknameChangeEvent` (모두 취소 가능)

## 설정 요약

- `storage.type`: `SQLITE`(기본) 또는 `MYSQL`
- `nickname.*`: 허용 문자(한글/영문/숫자/공백/추가문자), 최소·최대 길이, 한글 2칸 계산 여부, 금지어, 중복 금지, 쿨타임, Vault 비용, 아이템 비용
- `display.*`: displayName(기본 켜짐), 탭 이름·채팅·이름표·탭리스트(기본 꺼짐), 갱신 주기
- `rank.*`: 순위 사용 여부, 캐시 시간, 표시 인원
- `title.collection-milestones`: 칭호 N개 보유 시 추가 스텟

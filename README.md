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

## 값 입력 방식

이름 변경, ID 변경, 스텟 값 입력 등은 Paper 네이티브 **Dialog UI**로 받습니다
(`nickname.input-mode: DIALOG`, 기본값). 확인/취소 버튼으로만 닫히며 ESC로는
닫히지 않아 콜백이 유실되지 않습니다. 채팅으로 받고 싶으면 `CHAT` 으로 바꾸세요 —
이 경우 입력한 문장은 **채팅·콘솔 어디에도 기록되지 않습니다**
(입력 세션 동안 `AsyncChatEvent` 를 `LOWEST` 우선순위에서 즉시 취소).

## 칭호/인장 편집 (관리자)

`/it admin` → 칭호 클릭하면 편집창이 열립니다. 표시 이름·등급·아이콘·숨김·정렬·권한 외에
**ID 변경**도 이 창에서 할 수 있습니다 — 보유 기록과 장착 슬롯까지 새 ID로 함께 옮겨지므로
기존 보유자가 끊기지 않습니다.

### 스텟 편집

편집창의 **스텟 편집** 버튼을 누르면 종류 선택 화면(바닐라 / MMOItems / 커스텀)이 먼저
뜨고, 원하는 종류를 골라 들어갑니다. 종류당 최대 60여 종이라 한 화면에 다 담을 수 없어
분류별로 한 줄씩 배치하고, 8개를 넘으면 다음 줄로 이어지며 4줄을 넘으면 페이지가 늘어납니다.

각 아이콘 로어에 **현재 장착/보유 값**과 함께 설명, 바닐라 Attribute 키, 적용 방식,
플레이어 기본값, 권장 범위가 표시되므로 따로 찾아볼 필요가 없습니다.

| 클릭 | 동작 |
|---|---|
| 좌클릭 | 장착 스텟 값 입력 |
| Shift+좌클릭 | 보유 스텟 값 입력 |
| 우클릭 | 장착 스텟 제거 (확인 창) |
| Shift+우클릭 | 보유 스텟 제거 (확인 창) |

- 입력 단위는 **화면에 보이는 그대로**입니다. 이동 속도에 `10` → `+10%`
- `0` 또는 `제거` → 해당 값 삭제 / `취소` → 변경 없이 종료
- 권장 범위를 벗어나면 경고 후 적용되며, 대기 시간은 `gui.chat-input-timeout-seconds`(기본 60초)

### 편집 성능

등급·아이콘 등을 연타해도 DB 쓰기는 **2초 단위로 배치 처리**되며, 온라인 플레이어
갱신도 실제로 영향받는 사람(그 칭호를 보유·장착한 사람)만으로 좁혀져 있습니다.
서버 종료·`/it reload` 전에는 대기 중인 쓰기를 먼저 내보내므로 편집이 유실되지 않습니다.

## 관리자용 플레이어 조회

```
/it player <플레이어>
```

대상의 칭호·인장 보유 현황을 GUI로 보여줍니다. **오프라인 플레이어도 조회 가능**합니다.

| 클릭 | 동작 |
|---|---|
| 좌클릭 (미보유) | 영구 지급 |
| Shift+좌클릭 (미보유) | 기간을 입력받아 지급 (`30d`, `12h` 등) |
| 우클릭 (보유) | 회수 (확인 창) |

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

## 닉네임 변경 비용

Vault 경제 비용과 아이템 비용을 함께 쓸 수 있습니다. 아이템 비용은 **바닐라 아이템**과
**MMOItems 아이템** 두 종류를 각각 정의할 수 있고, 항목을 여러 개 켜두면 **그중 하나만
만족해도** 지불로 인정됩니다(이중 차감 없음). 갑옷·오프핸드는 검사 대상에서 제외됩니다.

```yaml
nickname:
  cost:
    economy:
      enabled: false
      amount: 10000.0
    item:
      vanilla_nickitem:
        enabled: false
        use-type: vanilla
        vanilla-material: PAPER
        amount: 1
        vanilla-name: '<light_purple>닉네임 변경권</light_purple>'
      mmo_nickitem:
        enabled: false
        use-type: mmoitems
        mmoitems-type: CONSUMABLE
        mmoitems-id: 닉네임변경권
        amount: 1
```

MMOItems 비용 아이템 인식은 MythicLib 의 `NBTItem`(있을 때)과 바닐라
`ItemMeta#getAsString()` 파싱(없을 때) 두 경로를 모두 시도하므로, MMOItems 없이도
아이템에 심어진 `MMOITEMS_ITEM_TYPE`/`MMOITEMS_ITEM_ID` 태그만으로 판별됩니다.
`/it checkitem` 으로 손에 든 아이템이 어떻게 인식되는지 바로 확인할 수 있습니다.

## 이름표 / 탭리스트

둘 다 기본은 켜짐이며 `config.yml` 의 `display.nametag` / `display.tablist` 로 조절합니다.

### 이름표 — 본인/타인 다른 내용

이름표는 두 묶음으로 나뉩니다. **`shared-lines`** 는 본인 포함 모두에게 보이고,
**`others-lines`** 는 다른 플레이어에게만 보입니다(본인 화면에서는 숨겨짐).
기본값은 본인은 인장만, 남들은 인장 + 칭호 + 닉네임을 모두 보는 구성입니다.

```yaml
display:
  nametag:
    enabled: true
    shared-lines:
      - '%tf_seal%'
    others-lines:
      - '%cmi_user_prefix%'
      - '%tf_title%%tf_nickname%'
    others-height-offset: 0.4
    shared-height-offset: 0.72
```

각 묶음은 `TextDisplay` 1개로 렌더링하므로(줄바꿈은 엔티티가 스스로 처리) 플레이어당
엔티티는 최대 2개입니다. 탑승 방식이라 위치 갱신 패킷이 없고, 내용이 바뀔 때만 다시
보냅니다. 텔레포트 직전에는 자동으로 분리했다가 다음 갱신 주기에 새 위치로 다시
붙이므로, 이름표가 붙어 있어서 다른 플러그인의 순간이동이 실패하는 일은 없습니다.

### 내장 토큰

이름표·탭리스트 모두 다른 플러그인의 `%플레이스홀더%` 와 같은 문법을 씁니다.

```
%tf_seal% %tf_title% %tf_nickname% %tf_player%
%tf_world% %tf_ping% %tf_x% %tf_y% %tf_z%
%tf_tps% %tf_mspt% %tf_online% %tf_max% %tf_time% %tf_date%
```

### 플레이스홀더 재계산 주기

토큰마다 재계산 주기를 밀리초 단위로 지정할 수 있습니다. `%javascript_biome%` 처럼
무거운 외부 플레이스홀더를 매 틱 재평가하지 않도록, 지정한 주기가 지나기 전까지는
캐시된 값을 재사용합니다. TPS·인원·시간처럼 전 인원이 같은 값을 보는 토큰은 주기당
서버 전체에서 1회만 계산합니다.

```yaml
placeholder-refresh-intervals:
  default-refresh-interval: 500
  '%player_x%': 50
  '%server_uptime%': 1000
  '%javascript_biome%': 1000
```

레거시 색 코드(`§a` 등)를 돌려주는 플레이스홀더(CMI 등)는 자동으로 MiniMessage
표기로 변환되므로 따로 처리할 필요가 없습니다.

## 명령어

`/it` 을 입력하면 메인 GUI가 바로 열립니다(콘솔에서는 도움말). `/it help` 로 언제든
도움말을 볼 수 있습니다. (별칭: `/titleforge`, `/tf`, `/칭호`)

### 유저

```
/it | /it menu                메인 GUI
/it help                     도움말
/it title | /it seal         칭호 · 인장 보관함
/it info [플레이어]           내 정보
/it equip <칭호ID>            능력치 슬롯 장착
/it show  <칭호ID>            표시 슬롯 장착
/it seal  <인장ID>            인장 장착
/it unequip <stat|show|seal>  해제
/it nick                     닉네임 변경 (다이얼로그 입력)
/it rank [title|seal]        수집 개수 순위
```

### 관리자 (`titleforge.admin`)

```
/it create <title|seal> <ID> [표시이름...]
/it delete <title|seal> <ID>
/it edit <title|seal> <ID> name|lore|rarity|icon|permission|hidden|order|id <값...>
/it edit <title|seal> <ID> stat <equip|own> <스텟> <수치>
/it give <플레이어> <title|seal> <ID> [기간]   (오프라인 지원)
/it take <플레이어> <title|seal> <ID>
/it extend <플레이어> <title|seal> <ID> <기간>
/it giveall <title|seal> <ID> [기간]
/it rank refresh                              순위 캐시 비우기
/it setnick <플레이어> <닉네임> | /it resetnick <플레이어>
/it resetcooldown <플레이어|all>               닉네임 변경 쿨타임 초기화
/it player <플레이어>                          보유 현황 조회 · 지급/회수 GUI (오프라인 지원)
/it checkitem                                 손에 든 아이템의 비용 아이템 인식 여부 진단
/it admin                                     관리 GUI
/it reload
```

ID 에는 **한글도 사용할 수 있습니다** (`/it create title 전설의개척자 ...`).
`edit ... id <새ID>` 는 기존 보유·장착 기록을 유지한 채 ID만 바꿉니다.

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
- `nickname.*`: 입력 방식(DIALOG/CHAT), 허용 문자(한글/영문/숫자/공백/추가문자), 최소·최대 길이,
  한글 2칸 계산 여부, 금지어, 중복 금지, 쿨타임, Vault 비용, 아이템 비용(바닐라/MMOItems, 다중 OR)
- `display.*`: displayName(기본 켜짐), 탭 이름·채팅·이름표·탭리스트, 갱신 주기
- `placeholder-refresh-intervals`: 토큰별 재계산 주기(밀리초)
- `rank.*`: 순위 사용 여부, 캐시 시간, 표시 인원
- `title.collection-milestones`: 칭호 N개 보유 시 추가 스텟

## soft-depend

`PlaceholderAPI`, `Vault`, `MythicLib`, `MMOItems` — 전부 선택 연동이며 없어도 플러그인은
정상 동작합니다. 관련 기능만 자동으로 비활성화되고 시작 로그에 안내가 남습니다.

# InMc-TitleForge

칭호(Title) · 인장(Seal) · 닉네임(Nickname)을 한 플러그인에서 관리합니다.
**Paper 26.1+ / Java 25 / Kotlin / Gradle**

> **TPS·MSPT 무영향이 1순위 설계 전제입니다.**
> 모든 조회는 메모리 캐시에서만 이뤄지고, DB 접근은 100% 비동기이며,
> 상시 반복 태스크는 표시 갱신용 티커 **1개**뿐입니다(이름표·탭리스트를 모두 끄면 그마저 생성되지 않습니다).

| 문서 | 용도 |
|---|---|
| **README.md** (이 문서) | 설치 · 설정 · 명령어 · API |
| [CLAUDE.md](CLAUDE.md) | 개발 규칙. 코드를 고칠 때 따르는 22개 규칙 |
| [PLAN.md](PLAN.md) | 최초 설계 기록 (**종결됨** — 갱신하지 않습니다) |

---

## 핵심 개념

| 개념 | 설명 |
|---|---|
| **칭호** | 모을수록 강해지는 수집형 요소. **보유스텟**(가지고만 있어도 적용) + **장착스텟**(장착 시 추가) |
| **인장** | 칭호와 동일한 구조의 별도 시스템. **명예 표시 전용, 스텟 없음** |
| **3개 슬롯** | `표시 칭호`(보이는 것) / `능력치 칭호`(스텟만) / `인장` — 서로 독립이며 동시 장착 |
| **닉네임** | 인게임 표시 이름 변경. 허용 문자·길이·비용·쿨타임 전부 설정 가능 |
| **보유 기한** | 기본은 영구. 기간을 붙여 지급하면 만료 시 자동 회수 |
| **수집 순위** | 만료되지 않은 보유 개수 기준 서버 순위 |

표시 칭호와 능력치 칭호를 다르게 둘 수 있습니다.
예) 화면에는 `[전설의 개척자]`가 보이지만 실제 적용되는 스텟은 `[광전사]` 것.

수집 마일스톤(`title.collection-milestones`)으로 "칭호 N개 보유 시 추가 스텟"도 줄 수 있습니다.

---

## 설치

1. `build/libs/InMc-TitleForge-1.0.0.jar` 를 서버 `plugins/` 에 넣습니다.
2. 서버를 켜면 `config.yml` · `messages.yml` · `stats.yml` 이 생성됩니다.
3. 기본값은 SQLite 이므로 **추가 설정 없이 바로 동작합니다.**

선택 연동(soft-depend) — 전부 없어도 정상 동작하며, 해당 기능만 자동으로 꺼지고 시작 로그에 안내가 남습니다.

| 플러그인 | 없을 때 |
|---|---|
| `PlaceholderAPI` | `%titleforge_...%` 제공 및 외부 플레이스홀더 치환이 비활성 |
| `Vault` | 닉네임 경제 비용이 자동 면제 |
| `MythicLib` | MMO 스텟 35종은 값만 보관(API·플레이스홀더로는 노출). **바닐라 28종은 그대로 동작** |
| `MMOItems` | MMOItems 비용 아이템을 획득할 수 없음 (태그 판별 자체는 가능) |

## 빌드

```bash
./gradlew build
```

산출물은 `build/libs/InMc-TitleForge-1.0.0.jar` (shadowJar) 입니다.

```bash
./gradlew test               # 순수 로직 단위 테스트 49개
pip install pyyaml           # 아래 검증기는 PyYAML 이 필요합니다
python tools/verify_gui.py   # 컴파일 없이 GUI·설정 키 정적 검증
```

Windows 에서는 `build.bat` 이 테스트를 건너뛴 빠른 빌드를 수행합니다.

| 항목 | 값 |
|---|---|
| Java toolchain | 25 (`JVM_25`) |
| Kotlin | 2.4.10 |
| Shadow | 9.0.0 (`com.gradleup.shadow`) |
| Paper API | `paperApiVersion=26.1.2.build.74-stable` |
| `plugin.yml` | `api-version: '1.21'` |
| 셰이딩 | HikariCP 7.0.2 (→ `kr.inmc.titleforge.lib.hikari` 로 relocate), sqlite-jdbc 3.50.3.0, mariadb-java-client 3.5.4 |

JDBC 드라이버는 드라이버 이름 문자열 로딩과 충돌하지 않도록 **relocate 하지 않습니다.**
서버 버전을 바꿀 때는 `gradle.properties` 의 `paperApiVersion` 한 줄만 조정하면 됩니다.

## 저장소

SQLite(기본) 또는 MySQL 계열을 씁니다. MySQL 계열은 **MariaDB 드라이버**(`jdbc:mariadb`)로 접속하며
MySQL 서버에도 그대로 연결됩니다. 커넥션 풀은 HikariCP 입니다.

```yaml
storage:
  type: SQLITE          # SQLITE 또는 MYSQL
  sqlite:
    file: data.db
  mysql:
    host: localhost
    port: 3306
    database: titleforge
    username: root
    password: ''
    properties: 'useUnicode=true&characterEncoding=utf8mb4'
    pool-size: 6
  autosave-seconds: 300     # 변경된 프로필 일괄 저장 주기 (0 = 끔)
  cache-keep-seconds: 60    # 퇴장 후 캐시 유지 시간
```

테이블 `tf_badge` / `tf_player` / `tf_owned` 는 기동 시 자동 생성되며, 컬럼 추가도 자동 마이그레이션됩니다.

> **한글 ID·닉네임을 쓴다면** MySQL 쪽 DB를 utf8mb4 로 만들어 주세요.
> `ALTER DATABASE titleforge CHARACTER SET utf8mb4;`

### 닉네임 중복 방지

`nickname.unique: true` 일 때 중복은 **두 겹**으로 막습니다.

1. 확정 절차가 진행 중인 닉네임은 서버 안에서 잠기므로, 두 명이 동시에 신청해도 한 명만 통과합니다.
2. `tf_player.nickname_normalized` 컬럼에 **UNIQUE 제약**이 걸려 있어, 여러 서버가 한 DB를
   공유하더라도 같은 닉네임이 두 번 저장되지 않습니다.

비교 기준은 앞뒤 공백 제거 → 유니코드 NFKC → 소문자화입니다.
따라서 `Yun` · `yun` · `Ｙｕｎ` 은 같은 닉네임으로 봅니다.

> **기존 서버를 업그레이드할 때** — 이미 중복된 닉네임이 있으면 UNIQUE 제약을 걸 수 없습니다.
> 이때 플러그인은 **닉네임을 임의로 지우지 않고** 중복 목록을 콘솔에 출력한 뒤 제약 없이 기동합니다.
> 로그에 찍힌 계정을 `/it resetnick <플레이어>` 또는 `/it setnick <플레이어> <새 닉네임>` 으로
> 정리하고 서버를 다시 켜면 그때 제약이 적용됩니다.

**메인 스레드에서 JDBC 호출은 0회**입니다. 접속 시 로드는 이미 비동기인 `AsyncPlayerPreLoginEvent`
에서 수행하고, 변경은 dirty 플래그 + 배치 저장으로 처리합니다.

## 값 입력 방식

이름 변경, ID 변경, 스텟 값 입력 등은 Paper 네이티브 **Dialog UI**로 받습니다
(`nickname.input-mode: DIALOG`, 기본값). 확인/취소 버튼으로만 닫히며 ESC 로는
닫히지 않아 콜백이 유실되지 않습니다.

채팅으로 받고 싶으면 `CHAT` 으로 바꾸세요 — 이 경우 입력한 문장은
**채팅·콘솔 어디에도 기록되지 않습니다** (입력 세션 동안 `AsyncChatEvent` 를
`LOWEST` 우선순위에서 즉시 취소하므로 브로드캐스트 자체가 일어나지 않습니다).

## 칭호/인장 편집 (관리자)

`/it admin` → 칭호를 클릭하면 편집창이 열립니다. 표시 이름·등급·아이콘·숨김·정렬·권한 외에
**ID 변경**도 이 창에서 할 수 있습니다 — 보유 기록과 장착 슬롯까지 새 ID로 함께 옮겨지므로
기존 보유자가 끊기지 않습니다.

### 스텟 편집

편집창의 **스텟 편집** 버튼을 누르면 종류 선택 화면(바닐라 / MMOItems / 커스텀)이 먼저
뜨고, 원하는 종류를 골라 들어갑니다. 분류별로 한 줄씩 배치하고, 8개를 넘으면 다음 줄로
이어지며 4줄을 넘으면 페이지가 늘어납니다.

각 아이콘 로어에 **현재 장착/보유 값**과 함께 설명, 종류(바닐라/MMO/가상 + 연동 여부),
바닐라 Attribute 키, 적용 방식, 플레이어 기본값, 권장 범위가 표시되므로 따로 찾아볼 필요가 없습니다.

| 클릭 | 동작 |
|---|---|
| 좌클릭 | 장착 스텟 값 입력 |
| Shift+좌클릭 | 보유 스텟 값 입력 |
| 우클릭 | 장착 스텟 제거 (확인 창) |
| Shift+우클릭 | 보유 스텟 제거 (확인 창) |

- 입력 단위는 **화면에 보이는 그대로**입니다. 이동 속도에 `10` → `+10%`
- `0` 또는 `제거` → 해당 값 삭제 / `취소` → 변경 없이 종료
- 권장 범위를 벗어나면 경고 후 적용되며, 대기 시간은 `gui.chat-input-timeout-seconds`(기본 60초)
- **인장 편집창에는 스텟 편집 버튼이 없습니다** (인장은 어떤 경로로도 스텟을 가질 수 없음)

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
장착 중이었다면 해제되며 접속 중이면 알림이 갑니다. 오프라인 중 만료된 항목은 재접속 시 정리됩니다.

```
/it give <플레이어> title <ID> 30d      30일
/it give <플레이어> seal <ID> 12h       12시간
/it extend <플레이어> title <ID> perm   영구로 전환
/it giveall title <ID> 1w               전체 지급 (7일)
```

표기: `30d` `12h` `90m` `2w` `3mo` `1y` `1d12h` `7일` `3시간` `perm`(영구)
숫자만 쓰면 **일** 단위입니다. 보관함 로어에 `보유 기한: 6일 3시간 남음` 으로 표시됩니다.

> 만료 검사는 프로필별 `nextExpiry` 캐시를 1회 비교하는 방식이라 인원이 늘어도 비용이 늘지 않습니다.

## 스텟 체계 (기본 63종 = 바닐라 28 + MMO 35)

MythicLib(MMOItems)의 `SharedStat` 목록 전체를 담았습니다. 중복 적용을 피하기 위해
**바닐라 Attribute 로 처리되는 항목은 바닐라 쪽에**, MythicLib 전용 항목은 MMO 쪽에 넣었습니다.

분류는 `전투 · 방어 · 이동 · 자원 · 상호작용 · 유틸리티` 6종입니다.

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

바닐라 스텟은 `titleforge:` 네임스페이스의 `AttributeModifier` 로 부착되며,
재적용 시 같은 네임스페이스만 골라 제거하므로 **다른 플러그인의 모디파이어를 건드리지 않습니다.**
최대 체력이 줄어들 때는 현재 체력을 클램프해 즉사를 방지합니다.

### MMOItems 35종 — `stats.yml`, MythicLib 있을 때 적용

```
전투    치명타 확률/피해 · 주문 치명타 확률/피해 · 무기·물리·마법·투사체·스킬 피해 ·
        PvE/PvP/언데드 피해 · 생명력 흡수 · 주문 흡혈
방어    피해 감소 · PvE/PvP 방어 · 체력 재생 ·
        막기 위력/확률/쿨감 · 회피 확률/쿨감 · 패링 확률/쿨감
이동    감속 저항
자원    최대 마나/스태미나/스텔륨 + 각 재생
유틸    쿨다운 감소 · 경험치 보너스 · 드롭 보너스
```

MythicLib 이 없으면 MMO 스텟은 값만 보관되고 API·플레이스홀더로 노출되며, GUI 에
`미연동` 으로 표시됩니다. **바닐라 28종은 그 경우에도 그대로 동작합니다.**

원소 스텟(`FIRE_DAMAGE` 등)은 서버마다 원소 이름이 달라 기본 제공하지 않으며,
`stats.yml` 하단 주석의 예시대로 추가하면 됩니다. 스텟 추가·삭제·설명 변경이 모두
가능하고, 바닐라 스텟의 설명·아이콘·권장 범위도 같은 파일에서 덮어쓸 수 있습니다
(정의 자체는 지워지지 않습니다).

> `kind: virtual` 로 두면 어디에도 적용되지 않고 값만 보관되어 API·플레이스홀더로만 노출됩니다.
> 다른 플러그인이 읽어가는 커스텀 수치를 만들 때 씁니다.

## 색상 표기

설정·메시지·칭호 이름·로어 등 **관리자가 쓰는 모든 문자열**에서 두 표기를 함께 지원합니다.

```
MiniMessage   <red>글자</red>  <gradient:#38BDF8:#EC4899>글자</gradient>  <bold>
레거시 코드    &c글자   &l글자   &#ff8800글자   (§ 도 동일하게 인식)
```

섞어 써도 됩니다. `&` 뒤가 색 코드가 아니면(`Tom & Jerry`) 그냥 글자로 남습니다.
표현력은 MiniMessage 가 더 넓으므로 그라디언트·호버 등은 MiniMessage 쪽을 쓰세요.

> **닉네임 색상은 관리자 전용입니다.** 아래 [닉네임](#닉네임) 을 보세요.

### messages.yml 공용 토큰

`messages.yml` 의 **어느 문장에서나** 쓸 수 있는 토큰입니다. 설정값을 문장에 그대로 끌어다
쓸 수 있어, 설정을 바꾸면 안내 문구가 저절로 따라옵니다.

| 토큰 | 값 | 예 |
|---|---|---|
| `<allowed>` | 닉네임 허용 문자 설명 | `한글(완성형), 영문, 숫자, _-` |
| `<nick_min>` | 닉네임 최소 길이 | `2` |
| `<nick_max>` | 닉네임 최대 길이 | `8` |
| `<nick_cooldown>` | 닉네임 변경 쿨타임 | `7일` |

```yaml
nickname:
  prompt-chat: '<yellow>변경할 닉네임을 채팅에 입력하세요.   <gray>허용: <allowed>'
  too-short: '<red>닉네임은 <nick_min>자 이상이어야 합니다.'
```

각 메시지가 원래 받던 전용 토큰(`<name>` `<amount>` 등)도 그대로 쓸 수 있고,
이름이 겹치면 **전용 토큰이 우선**합니다. 값은 `/it reload` 때 다시 읽습니다.

## 닉네임

허용 문자(한글/영문/숫자/공백/추가 문자), 최소·최대 길이, 한글 2칸 계산 여부(`length-mode`),
금지어, 중복 금지를 전부 `config.yml` 에서 정합니다.

### 색상은 `/it setnick` 으로만

| 경로 | 색상 |
|---|---|
| `/it nick` (유저 다이얼로그) | **불가**. `&c`·`<red>`·`§c` 모두 무력화되어 글자로 남습니다 |
| `/it setnick <플레이어> <닉네임>` (관리자) | **가능**. MiniMessage·`&` 둘 다 |

유저 입력은 저장 시점에 서식이 제거·이스케이프되므로, 허용 문자 설정을 넓혀
`&` 나 `<` 를 열어 주더라도 색상이 붙지 않습니다.

```
/it setnick Steve &c윤              빨간 윤
/it setnick Steve <gradient:red:gold>윤</gradient>
```

중복 검사는 **색을 무시하고** 판정합니다. 누군가 `윤` 을 쓰고 있으면 `&c윤` 도 막힙니다
(색만 바꿔 같은 이름을 쓰는 사칭 방지).

변경 제한은 **권한 · 쿨타임 · Vault 경제 비용 · 아이템 소모** 4종이며 각각 개별로 켜고 끕니다
(`nickname.cooldown-seconds` 기본값은 604800 = 7일).

### 변경 비용

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
**둘 다 끄면 표시 갱신 티커 자체가 생성되지 않습니다.**

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
    view-range: 32.0
    see-through: false            # 벽 너머로 비쳐 보일지
    text-shadow: true
    background: true
    background-color: 1073741824  # ARGB. 0x40000000 = 25% 검정
    hide-vanilla: true            # 바닐라 이름표(실제 아이디) 숨김
    hide-when-not-visible: true   # 벽 뒤에 완전히 숨은 사람은 이름표도 숨김
    visibility-check-ticks: 10    # 위 판정 재계산 주기
```

각 묶음은 `TextDisplay` 1개로 렌더링하므로(줄바꿈은 엔티티가 스스로 처리) 플레이어당
엔티티는 최대 2개입니다. 탑승 방식이라 위치 갱신 패킷이 없고, 내용이 바뀔 때만 다시
보냅니다. 텔레포트 직전에는 자동으로 분리했다가 다음 갱신 주기에 새 위치로 다시
붙으므로, 이름표 때문에 다른 플러그인의 순간이동이 실패하는 일은 없습니다.
퇴장·종료 시 제거되고, 기동 시 태그 기준으로 유령 엔티티를 청소합니다.

> `hide-when-not-visible` 은 몹이 플레이어를 찾을 때 쓰는 것과 같은 블록 레이캐스트로 판정합니다.
> 뷰어 × 대상 조합마다 1회이므로 인원이 많은 서버에서 `visibility-check-ticks` 를
> 너무 짧게 잡지 마세요(기본 10틱 = 0.5초).

### 내장 토큰 (15종)

이름표·탭리스트 모두 다른 플러그인의 `%플레이스홀더%` 와 같은 문법을 씁니다.

```
%tf_seal%  %tf_title%  %tf_nickname%  %tf_player%
%tf_world% %tf_ping%   %tf_x% %tf_y% %tf_z%
%tf_tps%   %tf_mspt%   %tf_online% %tf_max% %tf_time% %tf_date%
```

`%tf_tps%` · `%tf_mspt%` 는 임계값(`tps-good`/`tps-warn`/`mspt-good`/`mspt-warn`)에 따라
색이 자동으로 바뀝니다. TPS·인원·시간처럼 전원이 같은 값을 보는 토큰은 주기마다
**서버 전체에서 1회만** 계산해 공유합니다.

### 플레이스홀더 재계산 주기

토큰마다 재계산 주기를 밀리초 단위로 지정할 수 있습니다. `%javascript_biome%` 처럼
무거운 외부 플레이스홀더를 매 틱 재평가하지 않도록, 지정한 주기가 지나기 전까지는
캐시된 값을 재사용합니다.

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
도움말을 볼 수 있습니다. 별칭: `/titleforge`, `/tf`, `/칭호`

### 유저

```
/it | /it menu                메인 GUI
/it help                      도움말
/it title | /it seal          칭호 · 인장 보관함
/it info [플레이어]            내 정보 (타인은 권한 필요, 온라인만)
/it equip [칭호ID]             능력치 슬롯 장착 (생략 시 목록 GUI)
/it show  [칭호ID]             표시 슬롯 장착 (생략 시 목록 GUI)
/it seal  [인장ID]             인장 장착 (생략 시 목록 GUI)
/it unequip <stat|show|seal>  해제
/it nick                      닉네임 변경
/it rank [title|seal]         수집 개수 순위
```

### 관리자 (`titleforge.admin`)

```
/it create <title|seal> <ID> [표시이름...]
/it delete <title|seal> <ID>
/it edit <title|seal> <ID> name|lore|rarity|icon|permission|hidden|order|id <값...>
/it edit <title|seal> <ID> stat <equip|own> <스텟> <수치>
/it give <플레이어> <title|seal> <ID> [기간]   (오프라인 지원)
/it take <플레이어> <title|seal> <ID>          (오프라인 지원)
/it extend <플레이어> <title|seal> <ID> <기간>
/it giveall <title|seal> <ID> [기간]
/it rank refresh                               순위 캐시 비우기
/it setnick <플레이어> <닉네임> | /it resetnick <플레이어>
/it resetcooldown <플레이어|all>                닉네임 변경 쿨타임 초기화
/it player <플레이어>                           보유 현황 조회 · 지급/회수 GUI (오프라인 지원)
/it checkitem                                  손에 든 아이템의 비용 아이템 인식 여부 진단
/it admin                                      관리 GUI
/it reload                                     config · messages · stats.yml 리로드
```

`edit` 필드 값 참고 — `lore` 는 `|` 로 줄을 나눕니다.
`rarity` 는 `common` `uncommon` `rare` `epic` `legendary` `mythic`.
`hidden` 은 값을 생략하면 토글됩니다. `stat` 의 수치는 **화면에 보이는 단위**로 넣습니다.

### 한글 별칭과 탭 완성

거의 모든 서브커맨드에 한글 별칭이 있습니다 —
`칭호` `인장` `내정보` `장착` `표시` `해제` `닉네임` `순위` `생성` `삭제` `수정`
`지급` `회수` `연장` `쿨타임초기화` `플레이어` `관리` `도움말`.
타입 인자에도 `title`/`칭호`/`t`, `seal`/`인장`/`s` 를 모두 쓸 수 있습니다.

> **알아둘 점**: 탭 완성은 **영문 서브커맨드 이름 기준**으로만 제안됩니다.
> `/it 지급 <TAB>` 은 제안이 뜨지 않지만 명령 자체는 정상 동작합니다.

칭호·인장 ID 에는 **한글도 사용할 수 있습니다** — `^[a-z0-9_가-힣]{1,32}$`
(소문자 영문·숫자·밑줄·완성형 한글. 공백과 특수문자는 금지).
`edit ... id <새ID>` 는 기존 보유·장착 기록을 유지한 채 ID만 바꿉니다.

## 권한

| 권한 | 기본값 | 설명 |
|---|---|---|
| `titleforge.use` | 모두 | 명령어·GUI 사용 |
| `titleforge.nickname` | 모두 | 닉네임 변경 |
| `titleforge.info.other` | OP | 타인 정보 조회 |
| `titleforge.admin` | OP | 관리 전체 |

`titleforge.admin` 은 나머지 3개를 children 으로 포함하므로 따로 줄 필요가 없습니다.
칭호별로 `permission` 을 지정하면 해당 권한이 있어야 장착할 수 있습니다.

## 플레이스홀더 (PlaceholderAPI)

```
%titleforge_nickname%           현재 닉네임 (없으면 실제 아이디). 색은 § 코드로 반환
%titleforge_nickname_mini%      같은 값을 MiniMessage 원문으로
%titleforge_realname%           실제 아이디
%titleforge_has_nickname%       yes / no
%titleforge_title_display%      표시 칭호
%titleforge_title_stat%         능력치 칭호
%titleforge_seal%               인장
%titleforge_nameplate%          인장 + 표시칭호 + 닉네임
%titleforge_title_count%        보유 칭호 수
%titleforge_title_total%        전체 칭호 수
%titleforge_title_percent%      수집률
%titleforge_seal_count/total/percent%
%titleforge_stat_<스텟ID>%       스텟 총합 (보유 + 장착 + 마일스톤)
%titleforge_stat_equip_<스텟ID>% 장착분
%titleforge_stat_own_<스텟ID>%   보유분
%titleforge_has_<title|seal>_<ID>%          yes / no
%titleforge_expiry_<title|seal>_<ID>%       남은 기간 ("6일 3시간" / "영구")
%titleforge_expiry_seconds_<title|seal>_<ID>%  남은 초 (영구는 -1)
%titleforge_rank_title% / %titleforge_rank_seal%    내 수집 순위
%titleforge_rank_top_<title|seal>_<n>%       n위 이름
%titleforge_rank_top_<title|seal>_<n>_count% n위 보유 수
```

- `_mini` 접미사(`%titleforge_title_display_mini%`, `_seal_mini`, `_title_stat_mini`,
  `_nickname_mini`)를 붙이면 MiniMessage 원문을 그대로 돌려줍니다.
- `%titleforge_nickname%` 은 TAB·채팅 플러그인이 바로 해석할 수 있도록 레거시(`§`) 표기로
  나갑니다. 색이 없는 닉네임은 예전과 똑같은 평문입니다.
- `<title|seal>` 자리에는 `t` `s` `칭호` `인장` 도 쓸 수 있고, 전부 대소문자를 가리지 않습니다.
- 순위 관련 값은 캐시가 채워지기 전에는 빈 문자열을 돌려주고 비동기로 채웁니다.

**전부 메모리 캐시 조회**이므로 TAB 같은 플러그인이 초당 수십 번 호출해도 안전합니다.

> `display.nameplate-format` 안에 쓴 **외부** `%플레이스홀더%` 는 온라인 플레이어일 때만
> 치환됩니다. `<seal>` `<title>` `<nickname>` 은 항상 치환됩니다.

## 다른 플러그인에서 사용하기

```kotlin
import kr.inmc.titleforge.api.TitleForgeApi

TitleForgeApi.stat(player.uniqueId, "max_health")
TitleForgeApi.owned(player.uniqueId, BadgeType.TITLE)
TitleForgeApi.grant(player, BadgeType.TITLE, "first_join")             // 영구
TitleForgeApi.grant(player, BadgeType.TITLE, "이벤트", 7 * 86400_000L)  // 7일
TitleForgeApi.remainingSeconds(player.uniqueId, BadgeType.TITLE, "이벤트")
TitleForgeApi.nameplate(player)
```

Java 에서는 `TitleForgeApi.INSTANCE.stat(...)` 형태로 호출합니다.

### 조회 (캐시 전용 · 메인 스레드 안전)

| 메서드 | 반환 |
|---|---|
| `all(type)` | 등록된 칭호/인장 전체 |
| `badge(type, id)` | 정의 1개 (`null` 가능) |
| `owned(uuid, type)` | 보유 ID 집합 |
| `has(uuid, type, id)` | 보유 여부 |
| `equipped(uuid, slot)` | 슬롯에 장착된 것 (`EquipSlot.STAT/DISPLAY/SEAL`) |
| `stat(uuid, statId)` / `stat(uuid, stat)` | 총합 (보유 + 장착 + 마일스톤) |
| `stats(uuid)` / `equipStats(uuid)` / `ownStats(uuid)` | 스텟 맵 |
| `statDefinitions()` / `statDefinition(id)` | 스텟 정의 |
| `remainingSeconds(uuid, type, id)` | 남은 초. **영구이거나 미보유면 `null`** |
| `nickname(uuid)` | 닉네임 (`null` 가능) |
| `nameplate(player)` | 인장 + 표시칭호 + 닉네임 `Component` |

오프라인(미캐시) 플레이어는 `null` 또는 빈 값 / `0.0` 을 돌려줍니다.

### 변경 (메인 스레드 전용)

| 메서드 | 설명 |
|---|---|
| `grant(player, type, id, durationMillis = 0L)` | 지급. `0L` = 영구. 성공 시 프로필 자동 저장 |
| `revoke(player, type, id)` | 회수 |
| `equip(player, slot, id)` | 장착. `id = null` 이면 해제 |

### 이벤트

전부 취소 가능(`Cancellable`)하며 동기 이벤트입니다.

| 이벤트 | 시점 |
|---|---|
| `BadgeGrantEvent(target: OfflinePlayer, badge)` | 지급 직전. 오프라인 지급도 발생 |
| `BadgeEquipEvent(player, slot, badge)` | 장착/해제 직전. `badge == null` 이면 해제 |
| `NicknameChangeEvent(player, oldNickname, newNickname)` | 닉네임 변경 직전. `newNickname` 은 **`var`** 라 리스너가 값을 고쳐 쓸 수 있습니다 |

> **`BadgeRevokeEvent` 는 없습니다.** 회수는 전용 이벤트를 발생시키지 않으므로
> 지급/회수가 대칭이라고 가정하지 마세요.

## 설정 요약

| 섹션 | 내용 |
|---|---|
| `storage` | `SQLITE`(기본) / `MYSQL`, 자동 저장 주기, 캐시 유지 시간 |
| `title` / `seal` | 미장착 시 표시 문구, 수집 마일스톤 보너스 |
| `nickname` | 입력 방식(DIALOG/CHAT), 허용 문자, 길이, 금지어, 중복 금지, 쿨타임, Vault·아이템 비용 |
| `display` | displayName · 탭 이름 · 채팅 포맷 · 이름표 · 탭리스트 · 갱신 주기 |
| `gui` | 페이지 크기, 미보유 노출, 입력 대기 시간, 아이콘 |
| `rank` | 사용 여부, 캐시 시간(기본 300초), 표시 인원 |
| `placeholder-refresh-intervals` | 토큰별 재계산 주기(밀리초) |

`display.tab` 과 `display.chat.enabled` 는 **기본 켜짐**입니다.
TAB·채팅 전용 플러그인을 쓰는 서버라면 `false` 로 꺼서 충돌을 피하세요.

메시지는 전부 `messages.yml` 에 있고 MiniMessage 형식입니다.
`/it reload` 로 `config.yml` · `messages.yml` · `stats.yml` 을 함께 다시 읽습니다.

## 라이선스 / 제작

InMc 서버용으로 제작되었습니다.

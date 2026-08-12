# InMc-TitleForge 개발 계획서

> 칭호(Title) · 인장(Seal) · 닉네임(Nickname) 통합 관리 플러그인
> Paper / Kotlin / Gradle 기반, TPS·MSPT 무영향을 1순위 전제로 설계

---

## 0. 개발 환경 및 전제

| 항목 | 값 |
|---|---|
| 서버 | Paper 26.1 이상 (Bukkit/Spigot API 대신 Paper API 우선) |
| Java | 25 (toolchain 25) |
| 언어 | Kotlin (JVM) |
| 빌드 | Gradle (Kotlin DSL) + Shadow (의존성 셰이딩) |
| 저장소 | SQLite 기본 / MySQL·MariaDB 선택 (HikariCP 풀링) |
| 연동 | PlaceholderAPI (soft), Vault (soft), MMOItems/MythicLib (soft, 리플렉션) |
| 성능 전제 | 메인 스레드에서 I/O 금지, 반복 태스크는 표시 갱신용 1개(선택), 모든 조회는 메모리 캐시 |

### 확정된 설계 결정 (사용자 확인 완료)

1. **스텟 적용**: 바닐라 Attribute Modifier 기반. 커스텀 스텟(치명타 등)은 자체 컨테이너에 보관하고 API/플레이스홀더로 노출.
2. **저장소**: SQLite 기본, config에서 MySQL 전환.
3. **닉네임 표시**: displayName은 기본 적용, **탭/채팅은 온·오프 가능하며 기본값 OFF**. 나머지는 플레이스홀더로 외부 플러그인에 위임.
4. **닉네임 변경 제한**: 권한 + 쿨타임 + Vault 경제 비용 + 아이템 소모 **3종 모두 구현**, 각각 config에서 개별 on/off.

### ⚠️ 환경상 확인 불가 항목 (작업 시 조정 필요)

- 개발 컨테이너에서 외부 Maven 저장소 접근이 차단되어 **의존성 해석 및 컴파일 검증을 수행하지 못했습니다.**
- `gradle.properties`의 `paperApiVersion`, `plugin.yml`의 `api-version` 두 값은 실제 Paper 26.1 좌표에 맞춰 한 번 조정이 필요할 수 있습니다. 그 외 코드는 표준 Paper API만 사용합니다.
- 네이티브 Paper Dialog API(`Player#showDialog`)는 26.1 시그니처를 검증할 수 없어, 닉네임 입력은 **모루(Anvil) 팝업 입력을 기본**으로 구현하고 채팅 입력을 폴백으로 둡니다. `NicknameInput` 인터페이스(SPI)로 분리해 두었으므로 Dialog 구현체만 추가하면 교체됩니다.

---

## 1. 시스템 개요

### 1.1 칭호 (Title) — 수집형 성장 요소

- 모을수록 강해진다: 보유한 **모든** 칭호의 **보유스텟(ownStats)** 이 영구 합산.
- 장착하면 더 강해진다: 장착한 1개의 **장착스텟(equipStats)** 이 추가 합산.
- 수집 마일스톤: 보유 개수 구간별 추가 보너스(config).

### 1.2 장착 슬롯 3종 (요청 사항의 핵심)

| 슬롯 | 대상 | 효과 | 설명 |
|---|---|---|---|
| `STAT` | 칭호 | 능력치만 적용 | 겉으로 안 보임. 실전용 |
| `DISPLAY` | 칭호 | 표시만 | 이름 옆에 보이는 칭호 |
| `SEAL` | 인장 | 표시만 | 명예용. 칭호와 **동시 표시 가능** |

> 예: 표시 = `[전설의 개척자]`, 능력치 = `[광전사]`, 인장 = `⚜첫 서버 오픈 참가자⚜`
> → 화면에는 `⚜첫 서버 오픈 참가자⚜ [전설의 개척자] 닉네임` 이 보이고, 실제 스텟은 `[광전사]` 것이 적용.

### 1.3 인장 (Seal) — 명예 전용

- 칭호와 완전히 동일한 데이터 구조·GUI·명령어를 공유하되 **스텟 필드를 강제로 비웁니다.**
- 보유 인장 역시 수집 대상이나 능력치에는 일절 관여하지 않습니다.

### 1.4 닉네임

- 인게임 표시 이름 변경. `내 정보` GUI에서 확인·변경.
- 변경 시 팝업 입력창(모루) → 검증 → 비용 차감 → 즉시 반영.
- 허용 문자(한글/영문/숫자/공백/추가 문자), 최소·최대 길이, 한글 2칸 계산 여부, 금지어, 중복 금지 모두 config.

---

## 2. 아키텍처

```
kr.inmc.titleforge
├─ TitleForgePlugin.kt        플러그인 진입점 / 서비스 컨테이너
├─ TitleForgeApi.kt           외부 플러그인용 공개 API
├─ config/  Settings.kt       타입 안전 설정 스냅샷 (불변, reload 시 통째 교체)
│           Messages.kt       MiniMessage 메시지 번들
├─ badge/   BadgeType.kt      TITLE / SEAL
│           Badge.kt          칭호·인장 정의 (불변 data class)
│           Rarity.kt         등급(색상·정렬 가중치)
│           BadgeRegistry.kt  메모리 레지스트리 (읽기 O(1))
├─ stat/    Stat.kt           스텟 1개 정의 (id 기준 동일성)
│           StatRegistry.kt   바닐라 내장 + stats.yml 병합 레지스트리
│           StatCategory.kt   전투/방어/이동/유틸리티
│           StatLayout.kt     편집 GUI 슬롯 배치 (순수 로직)
│           StatValueParser.kt 채팅 입력값 해석 (순수 로직)
│           StatApplier.kt    AttributeModifier + MMOItems 적용/회수
├─ player/  PlayerProfile.kt  플레이어 상태 + 스텟 캐시
│           ProfileManager.kt 비동기 로드/저장/캐시 수명 관리
├─ storage/ Storage.kt        저장소 인터페이스
│           SqlStorage.kt     Hikari + SQLite/MariaDB 방언
├─ gui/     Menu.kt           경량 GUI 프레임워크 (홀더 기반)
│           MainMenu, BadgeListMenu, ProfileMenu, AdminMenu, BadgeEditMenu, ConfirmMenu
├─ command/ TitleForgeCommand.kt  /it 전체 트리 + 탭완성
├─ nickname/ NicknameService.kt   검증·비용·쿨타임
│            NicknameInput.kt     입력 SPI (Anvil / Chat 구현)
│            NameDisplayService.kt displayName·탭·채팅·네임태그
├─ display/ NametagService.kt  여러 줄 머리 위 이름표 (TextDisplay 1개)
│           TablistService.kt  탭리스트 머리말/꼬리말
│           DisplayTicker.kt   유일한 반복 태스크 (이름표·탭리스트·만료)
├─ rank/    RankService.kt     수집 개수 순위 집계 + TTL 캐시
├─ input/   TextInput.kt       범용 입력 SPI (Anvil / Chat)
├─ hook/    PlaceholderHook.kt  %titleforge_...% 제공
│           PlaceholderService.kt 외부 %플레이스홀더% 치환
│           MythicLibHook.kt   MMOItems 스텟 적용 (리플렉션)
│           VaultHook.kt
└─ util/    Text.kt, Items.kt, Sched.kt, DurationParser.kt
```

### 2.1 데이터 흐름

```
AsyncPlayerPreLogin ──(비동기 DB 조회)──> PlayerProfile 캐시 적재
        │
PlayerJoin ──> 스텟 재계산(메모리) ──> AttributeModifier 적용(엔티티 스레드) ──> 표시 이름 갱신
        │
GUI/명령어 조작 ──> 캐시 즉시 변경 + dirty 표시 ──> 비동기 저장(즉시 or 배치)
        │
PlayerQuit ──> 비동기 저장 ──> 지연 후 캐시 해제
```

### 2.2 DB 스키마

```sql
tf_badge(type, id, display_name, lore, rarity, icon, permission, hidden, sort_order,
         stats_equip, stats_own, PRIMARY KEY(type, id))
tf_player(uuid PK, name, nickname, nickname_changed_at,
          equip_stat, equip_display, equip_seal, updated_at)   -- INDEX(nickname), INDEX(name)
tf_owned(uuid, type, badge_id, obtained_at, expires_at, PRIMARY KEY(uuid, type, badge_id))
         -- expires_at = 0 이면 영구. 기존 설치는 기동 시 ALTER TABLE 로 자동 추가
```

스텟은 외부 JSON 라이브러리 없이 `max_health=2.0;attack_damage=1.5` 형태로 직렬화합니다(의존성 최소화).

---

## 3. 성능 설계 (TPS/MSPT 방어)

| 위험 요소 | 대응 |
|---|---|
| DB 조회로 인한 틱 지연 | 모든 쿼리는 `AsyncScheduler`. 메인 스레드 JDBC 호출 0회. 접속 시점 로드는 `AsyncPlayerPreLoginEvent`(이미 비동기)에서 수행 |
| 플레이스홀더 폭주 (TAB 등이 초당 수십 회 호출) | 플레이스홀더는 **메모리 캐시만 조회**. DB·계산 없음. 스텟 총합은 변경 시점에만 재계산해 저장 |
| Attribute 재적용 비용 | 장착/획득/탈착 등 **상태 변경 시에만** 호출. 주기적 재적용 없음 |
| 상시 반복 태스크 | 자동 저장(N분, dirty 프로필만) + 표시 갱신 티커 1개. 이름표·탭리스트가 모두 꺼져 있으면 티커를 만들지 않음 |
| GUI 아이템 생성 비용 | 페이지 단위 생성(45칸), 정적 버튼은 재사용. 클릭 시 필요한 페이지만 재빌드 |
| 대량 지급(`giveall`) | 온라인은 캐시 갱신, 오프라인은 단일 배치 트랜잭션으로 비동기 처리 |
| 저장 폭주 | dirty 플래그 + 배치 저장. 매 조작마다 전체 저장하지 않음 |
| 보유 기한 검사 | 프로필별 `nextExpiry` 캐시 비교 1회. 만료 예정이 없으면 즉시 건너뜀 |
| 순위 집계 | 전부 비동기 + TTL 캐시(기본 5분). 개인 순위 조회도 같은 주기로 스로틀 |
| 이름표 | 플레이어당 **엔티티 1개**(TextDisplay 줄바꿈). 탑승 방식이라 위치 패킷 없음. 내용이 바뀔 때만 전송 |
| Folia 호환 | `GlobalRegionScheduler` / `Entity#getScheduler` 사용으로 리전 스레드 안전 |

---

## 4. 명령어 설계 (`/it`, 별칭 `titleforge`, `칭호`)

`/it` 단독 입력 시 **권한에 맞춘 명령어 목록(도움말)** 출력.

### 유저

| 명령어 | 설명 |
|---|---|
| `/it menu` | 메인 GUI |
| `/it title` / `/it seal` | 칭호·인장 목록 GUI |
| `/it info [플레이어]` | 내 정보 GUI (타인은 권한 필요) |
| `/it equip <칭호ID>` | 능력치 슬롯 장착 |
| `/it show <칭호ID>` | 표시 슬롯 장착 |
| `/it seal <인장ID>` | 인장 장착 |
| `/it unequip <stat\|show\|seal>` | 해제 |
| `/it nick` | 닉네임 변경 팝업 |
| `/it rank [title\|seal]` | 수집 개수 순위 |

### 관리자 (`titleforge.admin`)

| 명령어 | 설명 |
|---|---|
| `/it create <title\|seal> <ID> <표시이름...>` | 생성 |
| `/it delete <title\|seal> <ID>` | 삭제 (보유 기록까지 정리) |
| `/it edit <type> <ID> name\|lore\|rarity\|icon\|permission\|hidden\|order <값...>` | 수정 |
| `/it edit <type> <ID> stat <equip\|own> <스텟> <수치>` | 스텟 수정 |
| `/it give <플레이어> <type> <ID> [기간]` | 지급 (오프라인 지원, 기간 생략 시 영구) |
| `/it take <플레이어> <type> <ID>` | 회수 (오프라인 지원) |
| `/it extend <플레이어> <type> <ID> <기간>` | 보유 기한 변경 |
| `/it giveall <type> <ID> [기간]` | 전체 지급 |
| `/it setnick <플레이어> <닉네임>` / `/it resetnick <플레이어>` | 닉네임 관리 |
| `/it admin` | 관리 GUI |
| `/it rank refresh` | 순위 캐시 비우기 |
| `/it reload` | 설정 리로드 (stats.yml 포함) |

모든 단계에 문맥 인식 탭 완성 제공.

---

## 4-A. 스텟 편집 GUI (관리자)

칭호 편집 → `스텟 편집` 하나로 진입하며, **장착 스텟과 보유 스텟을 한 화면에서** 다룹니다.

```
행0  [뒤로]   ·  ·  ·  [편집 중 칭호 + 장착/보유 요약]  ·  ·  ·  [전체 초기화]
행1  [전투]      공격력  공격속도  치명타확률  치명타피해
행2  [방어]      최대체력  방어력  방어강도  넉백저항  흡수체력
행3  [이동]      이동속도
행4  [유틸리티]  행운  경험치보너스  드랍률보너스
행5  [조작 방법]      ·      [표기 안내]      ·      [뒤로]
```

배치는 `stat/StatLayout.kt` 가 계산합니다. Bukkit 의존이 없는 순수 로직이라 단위 테스트로
슬롯 충돌·범위 초과·누락을 검증하며, 스텟이 늘어나면 다음 행으로 자동으로 넘어갑니다.

**각 아이콘 로어** — 관리자가 문서를 찾지 않아도 되도록 다음을 모두 표시합니다.

| 항목 | 예시 |
|---|---|
| 분류 | `[전투]` |
| **종류** | `바닐라 · minecraft:attack_damage (외부 플러그인 없이 적용)` / `MMOItems · CRITICAL_STRIKE_CHANCE (연동됨/미연동)` / `가상 스텟` |
| 설명 | 스텟이 무슨 능력인지 한두 줄 설명 |
| **현재 값** | `장착 스텟 +2` / `보유 스텟 +0.5` (미설정은 `-`) |
| 적용 방식 | `합연산 (ADD_NUMBER)` / `기본값 비례 (ADD_SCALAR)` |
| 플레이어 기본값 | `1` (최대 체력 20, 공격 속도 4 …) |
| 권장 범위 | `-20 ~ +20` |
| 조작 | 좌클릭·Shift+좌클릭·우클릭·Shift+우클릭 안내 |

**조작**

| 클릭 | 동작 |
|---|---|
| 좌클릭 | 장착 스텟 값 입력 (채팅) |
| Shift+좌클릭 | 보유 스텟 값 입력 (채팅) |
| 우클릭 | 장착 스텟 제거 (확인 창) |
| Shift+우클릭 | 보유 스텟 제거 (확인 창) |

**채팅 입력 — 로그가 남지 않음**

값 입력은 `input/TextInput.kt` 의 `ChatTextInput` 이 담당합니다. 입력 세션이 있는 동안
`AsyncChatEvent` 를 `EventPriority.LOWEST` 에서 **즉시 취소**하므로 브로드캐스트 자체가
일어나지 않아 다른 플레이어 화면 · 본인 화면 · 서버 콘솔 어디에도 기록되지 않습니다.
(클라이언트가 로컬에 보관하는 입력 히스토리는 서버 영역 밖입니다.)

- 입력은 **항상 표시 단위**입니다. 이동 속도에 `10` → `+10%`(내부 0.1) 저장
- `0` 또는 `제거` → 스텟 삭제, `취소` → 변경 없이 종료
- 권장 범위를 벗어나면 경고 후 적용, 내부값 10만을 넘으면 거부
- 대기 시간은 `gui.chat-input-timeout-seconds`(기본 60초), 명령어를 입력하면 자동 취소

---

## 4-B. 2차 확장 기능

### 보유 기한

- 기본은 **영구**(`expires_at = 0`). 지급 시 기간을 붙이면 만료 시각이 저장됩니다.
- `/it give <플레이어> <type> <ID> [기간]`, `/it extend <플레이어> <type> <ID> <기간>`,
  `/it giveall <type> <ID> [기간]` — 표기: `30d` `12h` `90m` `2w` `3mo` `1y` `1d12h` `7일` `perm`
- 만료 시 **자동 회수**: 보유 목록에서 제거 → 장착 중이었으면 해제 → 스텟 재계산 → 접속 중이면 알림
- 검사는 프로필별 `nextExpiry` 캐시를 비교하는 방식이라 인원이 늘어도 비용이 늘지 않습니다.
  접속 시점과 표시 갱신 티커에서 확인합니다.
- 보관함 로어에 `보유 기한: 6일 3시간 남음` 또는 `영구` 표시.

### MMOItems 스텟

- 커스텀 스텟은 `stats.yml` 에서 MMOItems 스텟 ID 로 정의합니다(치명타 확률/피해, 마나, 쿨다운 감소 등).
- 적용은 `hook/MythicLibHook.kt` 가 **리플렉션으로만** 수행합니다. MythicLib 이 없거나 시그니처가
  다르면 연동만 꺼지고 값은 계속 보관·노출됩니다.
- **바닐라 스텟 9종은 코드에 내장되어 항상 동작합니다.** MMOItems 를 쓰지 않는 서버도
  이것만으로 완전히 운영할 수 있으며, GUI 는 각 스텟이 바닐라인지 MMO 인지(그리고 연동 여부까지)
  로어에 항상 명시합니다.

### 한글 ID

- 칭호·인장 ID 에 완성형 한글 허용: `^[a-z0-9_가-힣]{1,32}$` (공백·특수문자는 계속 금지)
- MySQL 은 `utf8mb4` 를 기본 접속 문자열로 사용합니다.

### 수집 개수 순위

- 만료되지 않은 보유만 집계합니다. 조회는 전부 비동기 + TTL 캐시.
- `/it rank [title|seal]`, 관리자 `/it rank refresh`, 메인 GUI 의 순위 버튼
- 순위 GUI 하단에 **내 순위 / 전체 인원**을 함께 표시합니다.

### 여러 줄 이름표

요청 예시(인장 / 다른 플러그인 칭호 / 칭호+닉네임)를 그대로 구성할 수 있습니다.

**핵심 설계 판단**: `TextDisplay` 는 텍스트 안의 줄바꿈을 자체 렌더링하므로
**줄 수와 무관하게 플레이어당 엔티티는 1개**입니다. 줄마다 엔티티를 띄우는 방식보다
트래픽과 정리 비용이 크게 줄어듭니다.

| 항목 | 방식 |
|---|---|
| 위치 | `addPassenger` 로 탑승 — 위치 갱신 패킷 불필요, 높이는 Transformation |
| 본인 시야 | **기본으로 본인에게도 보입니다** (자기 인장을 확인할 수 있어야 하므로). `show-to-self` 로 조정 |
| 바닐라 이름표 | 스코어보드 팀 `NAME_TAG_VISIBILITY = NEVER` 로 숨김 (`hide-vanilla`) |
| 갱신 | 렌더 결과가 바뀐 경우에만 전송 |
| 정리 | 비영속 + 퇴장/종료 시 제거 + 기동 시 태그 기준 유령 엔티티 청소 |

각 줄은 MiniMessage + PlaceholderAPI 를 지원하므로 다른 플러그인의 칭호를 그대로 한 줄로 넣을 수 있습니다.

### 탭리스트

- `display.tablist` 의 머리말/꼬리말을 줄 목록으로 작성합니다(기본 꺼짐).
- 내장 토큰: `<tps>` `<mspt>` `<online>` `<max>` `<ping>` `<time>` `<date>` `<world>` `<player>`
  `<nickname>` `<seal>` `<title>` — TPS/MSPT 는 임계값에 따라 자동으로 색이 바뀝니다.
- 서버 공통 값은 주기마다 1회만 계산해 전 인원이 공유하고, 내용이 바뀐 경우에만 전송합니다.

## 5. GUI 설계

```
[메인 메뉴]
 ├ 칭호 보관함   → 페이지형 목록 (좌클릭: 표시 장착 / 우클릭: 능력치 장착 / Shift+좌: 해제)
 ├ 인장 보관함   → 페이지형 목록 (좌클릭: 장착 / Shift+좌: 해제)
 ├ 내 정보       → 닉네임(클릭 시 변경 팝업), 3개 슬롯 현황, 수집률, 스텟 총합 상세
 └ 관리 메뉴     → (관리자) 칭호/인장 생성·수정·삭제
```

- 목록은 **보유/미보유 토글** 지원, 미보유는 회색 유리판 + 획득 조건 표시.
- 아이콘 하단에 장착스텟/보유스텟이 색상 구분되어 표시됩니다.
- 관리 GUI의 생성·이름 변경은 모루 입력창, 삭제는 확인 GUI 2단계.

---

## 6. 플레이스홀더 (`%titleforge_...%`)

| 플레이스홀더 | 반환 |
|---|---|
| `title_display` / `title_stat` | 표시·능력치 칭호 이름 (미장착 시 기본값) |
| `title_count` / `title_total` / `title_percent` | 수집 현황 |
| `seal` / `seal_count` | 인장 |
| `nickname` / `realname` | 닉네임 / 실제 아이디 |
| `nameplate` | `인장 + 표시칭호 + 닉네임` 조합 (config 포맷) |
| `stat_<스텟ID>` | 총합 |
| `stat_equip_<스텟ID>` / `stat_own_<스텟ID>` | 장착분 / 보유분 |
| `has_title_<ID>` / `has_seal_<ID>` | yes/no |
| `expiry_title_<ID>` / `expiry_seal_<ID>` | 남은 기간 ("6일 3시간" / "영구") |
| `expiry_seconds_title_<ID>` | 남은 초 (영구는 -1) |
| `rank_title` / `rank_seal` | 내 수집 순위 |
| `rank_top_title_1` / `rank_top_title_1_count` | 1위 이름 / 보유 수 |

전부 캐시 조회이므로 TAB 플러그인이 고빈도로 호출해도 안전합니다.

---

## 7. 칭호 플러그인 맞춤 지침 (개발 규칙)

이 플러그인에 코드를 추가·수정할 때 반드시 지키는 규칙입니다.

### 7.1 스레드 규칙 (최우선)

1. **메인 스레드에서 JDBC·파일 I/O·네트워크 호출 금지.** 예외 없음. 저장소 접근은 `Sched.async` 안에서만.
2. Bukkit 엔티티/인벤토리 API는 **반드시** 메인(또는 해당 엔티티 리전) 스레드에서. 비동기 → `Sched.entity(player) { }` 로 복귀.
3. `Thread.sleep`, `Future.get()`, `join()` 을 메인 스레드에서 호출하지 않습니다.
4. 반복 태스크는 **표시 갱신용 티커 1개만** 허용합니다(`display/DisplayTicker.kt`). 새 태스크를 만들지 말고
   여기에 얹으며, 주기는 설정 가능해야 하고 **내용이 바뀐 경우에만** 전송해야 합니다.
   해당 기능이 모두 꺼져 있으면 티커 자체를 만들지 않습니다.

### 7.2 데이터 규칙

5. 조회는 **항상 캐시**. DB는 로드/저장 경로에서만 등장합니다.
6. 상태 변경은 `profile.mutate { }` 로 감싸 dirty 플래그를 남기고, 저장은 배치에 맡깁니다(중요 조작만 즉시 저장).
7. 스텟 합계는 변경 시점에만 재계산(`profile.recalculate()`)하고 결과를 캐시합니다. 조회 시 계산하지 않습니다.
8. `Settings`는 불변 스냅샷입니다. reload는 새 객체로 통째 교체하며, 참조를 필드에 오래 보관하지 않습니다.

### 7.3 Paper API 규칙

9. Spigot/Bukkit 대체 API가 있으면 Paper 쪽을 씁니다: `Component`(String 대신), `AsyncChatEvent`(`AsyncPlayerChatEvent` 대신), `AsyncScheduler`/`RegionScheduler`(`BukkitScheduler` 대신), `RegistryAccess`(deprecated Registry 상수 대신).
10. 문자열 색 코드(`§`, `&`)를 코드에 직접 쓰지 않습니다. 모든 텍스트는 **MiniMessage**로 파싱합니다.
11. 유저에게 보이는 문장은 코드에 하드코딩하지 않고 `messages.yml` 키로 관리합니다.
12. 외부 플러그인(PlaceholderAPI, Vault) 클래스는 **훅 클래스 안에서만** 참조합니다. 존재 확인 후에만 훅을 로드해 NoClassDefFoundError를 원천 차단합니다.

### 7.4 도메인 규칙

13. 칭호와 인장은 `BadgeType` 하나로 분기합니다. 인장 전용 코드를 복제하지 않습니다.
14. **인장은 어떤 경로로도 스텟을 가질 수 없습니다.** 저장 시점에 강제로 비웁니다(방어적 정규화).
15. 장착 슬롯 3종(`STAT`/`DISPLAY`/`SEAL`)은 서로 독립입니다. 한 슬롯 변경이 다른 슬롯을 건드리지 않습니다.
16. 스텟은 `StatRegistry` 한 곳에서만 정의합니다. 바닐라 9종은 코드 내장(외부 플러그인 없이 항상 동작),
    그 외는 `stats.yml`. 저장·GUI·플레이스홀더·명령어가 레지스트리를 따라오도록 유지하며
    하드코딩된 스텟 분기를 만들지 않습니다.
16-a. 스텟 값 맵의 키는 **스텟 id 문자열**입니다. 등록되지 않은 id 도 버리지 않고 보존합니다
    (설정 실수로 저장된 값이 지워지면 안 됩니다).
16-b. **MMOItems 연동은 선택 사항입니다.** 연동이 없어도 바닐라 스텟만으로 서버가 완전히 돌아가야 하며,
    GUI 는 각 스텟의 종류(바닐라/MMO/가상)와 연동 여부를 항상 명시합니다.
17. AttributeModifier는 반드시 `titleforge:` 네임스페이스 키로 부착하고, 재적용 전 같은 네임스페이스만 골라 제거합니다. 타 플러그인의 모디파이어를 건드리지 않습니다.
18. 최대 체력 감소 시 현재 체력 클램프를 반드시 수행합니다(즉사 방지).

### 7.5 UX·안전 규칙

19. 파괴적 동작(삭제, 전체 지급, 닉네임 초기화)은 확인 단계를 거칩니다.
20. GUI 클릭은 기본 전부 취소(`setCancelled(true)`) 후 명시적으로 허용된 동작만 수행합니다. 아이템 복사 경로를 만들지 않습니다.
21. 유저 입력(닉네임, 칭호 ID)은 화이트리스트 정규식으로 검증합니다. ID는 `[a-z0-9_]{1,32}` 소문자만 허용.
22. 유저가 입력한 문자열을 MiniMessage로 파싱할 때는 **관리자 입력에만** 서식 태그를 허용하고, 일반 유저 닉네임은 서식 태그를 이스케이프합니다(색상 주입 방지).

---

## 7-A. 검증 방법

컴파일이 필요 없는 정적 검증기와, 순수 로직 단위 테스트를 함께 둡니다.

```bash
python3 tools/verify_gui.py   # 메시지·설정 키, stats.yml 검증, 슬롯 배치 시뮬레이션, 메뉴 전환 규칙
./gradlew test                # StatRegistry / StatLayout / StatValueParser / Stats / DurationParser
```

`tools/verify_gui.py` 가 잡아내는 것:

1. 코드가 참조하는 `messages.yml` 키 누락 (`Gui.item` 의 `.name`/`.lore` 규칙 포함)
2. `messages.yml` 의 미사용 키 (오타·리팩터링 잔재)
3. `Settings` 가 읽는 `config.yml` 키 누락
4. 스텟 편집 창 슬롯 충돌 / 범위 초과 / 배치 누락 (배치도를 표로 출력)
5. 각 메뉴의 하드코딩 슬롯이 창 크기를 벗어나는지
6. GUI 안에서 `open()` 을 직접 호출하는 곳이 없는지 (반드시 `openLater()`)
7. `stats.yml` 의 kind/분류/필수 필드가 올바른지, id 규칙과 중복 여부
8. 스텟이 늘어난 상태(바닐라 9 + MMO 11)에서도 편집 GUI 슬롯이 충돌하지 않는지

**서버 수동 확인 체크리스트**

- [ ] `/it admin` → 칭호 클릭 → `스텟 편집` 진입
- [ ] 분류 4행이 보이고, 바닐라/커스텀이 로어에서 구분됨
- [ ] 값이 설정된 스텟만 발광하고 로어에 장착·보유가 모두 표시됨
- [ ] 좌클릭 → 채팅 프롬프트, 입력 시 **다른 플레이어 화면과 콘솔에 채팅이 뜨지 않음**
- [ ] 값 적용 후 GUI 가 자동으로 다시 열리고 로어가 갱신됨
- [ ] Shift+좌클릭이 보유 스텟에 반영됨
- [ ] 우클릭 → 확인 창 → 제거, ESC 로 닫으면 스텟 창으로 복귀
- [ ] `취소` 입력 / 60초 방치 / 명령어 입력 시 각각 안내 후 취소
- [ ] 이동 속도에 `10` 입력 → 로어가 `+10%` 로 표시
- [ ] `/it info` 에서 실제 Attribute 반영 확인
- [ ] 인장 편집 창에는 스텟 편집 버튼이 없음
- [ ] 편집 중 다른 관리자가 같은 칭호를 삭제하면 관리 목록으로 되돌아감
- [ ] 편집을 반복해도 TPS/MSPT 변화 없음

**2차 확장 확인**
- [ ] `stats.yml` 의 MMO 스텟이 분류별로 뜨고 설명·종류(연동 여부)가 보임
- [ ] MythicLib 없이도 바닐라 스텟이 정상 적용되고 경고 1줄만 남음
- [ ] `/it give <p> title <id> 30d` → 로어에 남은 기간 → 만료 시 자동 회수·해제·알림
- [ ] 오프라인 중 만료된 항목이 재접속 시 정리됨
- [ ] 한글 ID(`/it create title 전설 ...`) 생성·장착·플레이스홀더 동작
- [ ] 이름표 3줄 표시, **본인에게도 보임**, 퇴장 후 잔여 엔티티 없음, 재시작 후 유령 없음
- [ ] 탭리스트에 시간·TPS·인원·외부 플레이스홀더 반영, TPS 색상 변화
- [ ] `/it rank title` 순위와 내 순위 표시, 캐시 주기 동작
- [ ] 이름표·탭리스트 모두 끄면 티커가 생성되지 않음(콘솔 로그 확인)

## 8. 작업 순서

1. Gradle·플러그인 골격, 설정/메시지 로더
2. 저장소(SQLite/MySQL) + 스키마 마이그레이션
3. Badge 도메인 + 레지스트리 + 프로필 캐시
4. 스텟 계산 및 Attribute 적용
5. 명령어 트리 + 탭완성
6. GUI 전체
7. 닉네임 서비스(검증·비용·입력창·표시)
8. PlaceholderAPI / Vault 훅, 공개 API
9. 문서화(README, config 주석)

## 9. 향후 확장 후보

- 네이티브 Paper Dialog API 입력 구현체 추가 (SPI 교체만으로 적용)
- 칭호 획득 조건 자동화(통계 트리거: 접속시간, 처치수, 채굴수)
- 칭호 세트 효과 / 기간 한정 칭호 만료
- 시즌·랭킹 GUI, 획득 로그 조회

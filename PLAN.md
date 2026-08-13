# InMc-TitleForge 개발 계획서

> 칭호(Title) · 인장(Seal) · 닉네임(Nickname) 통합 관리 플러그인
> Paper / Kotlin / Gradle 기반, TPS·MSPT 무영향을 1순위 전제로 설계

---

## ✅ 종결 — 전 항목 구현 완료 (2026-08-13)

**이 문서는 완료된 설계 기록입니다. 더 이상 갱신하지 않습니다.**

| 지금 볼 문서 | 용도 |
|---|---|
| [README.md](README.md) | 현행 사용 설명서 (설치·설정·명령어·API) |
| [CLAUDE.md](CLAUDE.md) | 개발 규칙 (구 7절). 코드 수정 시 여기를 따릅니다 |

**검증 근거**

- 단위 테스트 **49개 전부 통과** (StatLayout 13 · StatRegistry 9 · StatValueParser 12 ·
  Stats 8 · DurationParser 7), 실패·에러 0
- 셰이드 JAR 빌드 성공 — `build/libs/InMc-TitleForge-1.0.0.jar`
- 4절 명령어(유저 9 + 관리자 12) 전부 구현, 6절 플레이스홀더 전부 구현
- 소스 전체에 미구현 스텁 없음

**계획을 넘어선 추가 구현** — 9절 "향후 확장 후보" 1번(네이티브 Paper Dialog 입력)은 이미 완료되었고,
그 밖에 `/it help` · `/it player`(오프라인 조회·지급 GUI) · `/it resetcooldown` · `/it checkitem` ·
전 서브커맨드 한글 별칭 · 칭호 ID 변경 · 공개 API 18개 메서드 · 취소 가능 이벤트 3종 ·
`_mini` 플레이스홀더 · 이름표 shared/others 분리와 시야 기반 가림이 추가되었습니다.
자세한 내용은 README 를 보세요.

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
3. **닉네임 표시**: displayName은 기본 적용, **탭/채팅은 온·오프 가능**. 나머지는 플레이스홀더로 외부 플러그인에 위임.
   → *구현 시 변경됨: 탭·채팅 기본값은 **ON** 입니다(`display.tab`, `display.chat.enabled`).
   TAB·채팅 전용 플러그인을 쓰는 서버는 `false` 로 끄세요.*
4. **닉네임 변경 제한**: 권한 + 쿨타임 + Vault 경제 비용 + 아이템 소모 **3종 모두 구현**, 각각 config에서 개별 on/off.

### ✅ 초안 당시의 미확인 항목 — 전부 해소됨

작성 시점에는 아래 항목을 검증할 수 없었으나, 이후 모두 확인·구현되었습니다.

- ~~외부 Maven 저장소 접근 차단으로 의존성 해석 및 컴파일 검증 불가~~
  → **해소.** 빌드·테스트 모두 성공합니다.
- ~~`paperApiVersion` / `api-version` 조정 필요~~
  → **확정.** `paperApiVersion=26.1.2.build.74-stable`, `plugin.yml` 의 `api-version: '1.21'`.
- ~~Dialog API 시그니처 미검증으로 모루(Anvil) 입력을 기본으로 구현~~
  → **해소.** 네이티브 Paper Dialog(`Dialog.create` + `Player#showDialog`)로 구현했고
  모루 입력은 쓰지 않습니다. 입력 SPI 는 `input/TextInput.kt` 의 `TextInput` 인터페이스이며
  `DialogTextInput`(기본) / `ChatTextInput` 두 구현체가 있습니다
  (`nickname.input-mode: DIALOG | CHAT`).

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
│           StatCategory.kt   전투/방어/이동/자원/상호작용/유틸리티
│           StatLayout.kt     편집 GUI 슬롯 배치 + 페이지 (순수 로직)
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
행1  [분류]      스텟 최대 8개
행2  [분류]      스텟 최대 8개
행3  [분류]      스텟 최대 8개
행4  [분류]      스텟 최대 8개
행5  [조작 방법] · [이전] · [표기 안내] · [다음] · [뒤로]
```

배치는 `stat/StatLayout.kt` 가 계산합니다. Bukkit 의존이 없는 순수 로직이라 단위 테스트로
슬롯 충돌·범위 초과·누락·페이지 계산을 검증합니다.

- 한 분류가 8개를 넘으면 다음 행으로 이어지고, 라벨에 `(계속)` 이 붙습니다.
- 4행을 넘으면 **페이지**로 넘어갑니다. 기본 구성(63종)은 3페이지입니다.

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

- MythicLib `SharedStat` 목록 **전체**를 반영했습니다. 바닐라 Attribute 로 처리되는 항목은
  바닐라 스텟(28종, 코드 내장)으로, MythicLib 전용 항목은 `stats.yml`(35종)로 나눠
  **중복 적용을 피했습니다.** 가상 스텟 예시까지 합쳐 기본 63종입니다(28 + 35).
- 실제 API 는 `StatInstance#registerModifier(StatModifier)` / `removeIf(Predicate<String>)`,
  `StatModifier(String key, String stat, double value)` 입니다.
- 원소 스텟은 서버마다 원소 이름이 달라 기본 제공하지 않고 `stats.yml` 주석에 추가 방법을 남겼습니다.
- 적용은 `hook/MythicLibHook.kt` 가 **리플렉션으로만** 수행합니다. MythicLib 이 없거나 시그니처가
  다르면 연동만 꺼지고 값은 계속 보관·노출됩니다.
- **바닐라 스텟 28종은 코드에 내장되어 항상 동작합니다.** MMOItems 를 쓰지 않는 서버도
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

> **이 절의 내용은 [CLAUDE.md](CLAUDE.md) 로 옮겼습니다.**
> 규칙이 두 곳에서 갈라지지 않도록 여기에는 본문을 두지 않습니다.
> 코드를 추가·수정할 때는 CLAUDE.md 를 따르세요.
> (7.1 스레드 / 7.2 데이터 / 7.3 Paper API / 7.4 도메인 / 7.5 UX·안전, 총 22개 규칙)

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
8. 스텟이 늘어난 상태(바닐라 28 + MMO 35)에서도 편집 GUI 슬롯이 충돌하지 않는지

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

**완료**

- [x] 네이티브 Paper Dialog API 입력 구현체 추가 — `input/TextInput.kt` 의 `DialogTextInput`.
      SPI 교체 없이 기본 입력 방식이 되었고 `nickname.input-mode` 로 `CHAT` 과 전환합니다.
- [x] 기간 한정 칭호 만료 — 보유 기한(`expires_at`)으로 구현. 만료 시 자동 회수·해제·알림.
- [x] 랭킹 GUI — `/it rank`, `rank/RankService.kt` + `gui/RankMenu.kt` (TTL 캐시).

**미착수**

- [ ] 칭호 획득 조건 자동화(통계 트리거: 접속시간, 처치수, 채굴수)
- [ ] 칭호 세트 효과
- [ ] 시즌 단위 순위, 획득 로그 조회

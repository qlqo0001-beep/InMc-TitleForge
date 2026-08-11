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
| 연동 | PlaceholderAPI (soft), Vault (soft) |
| 성능 전제 | 메인 스레드에서 I/O 금지, 상시 반복 태스크 0개, 모든 조회는 메모리 캐시 |

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
├─ stat/    StatType.kt       스텟 정의 + 바닐라 Attribute 매핑
│           StatApplier.kt    AttributeModifier 적용/회수
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
├─ hook/    PlaceholderHook.kt, VaultHook.kt
└─ util/    Text.kt, Items.kt, Sched.kt
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
tf_owned(uuid, type, badge_id, obtained_at, PRIMARY KEY(uuid, type, badge_id))
```

스텟은 외부 JSON 라이브러리 없이 `max_health=2.0;attack_damage=1.5` 형태로 직렬화합니다(의존성 최소화).

---

## 3. 성능 설계 (TPS/MSPT 방어)

| 위험 요소 | 대응 |
|---|---|
| DB 조회로 인한 틱 지연 | 모든 쿼리는 `AsyncScheduler`. 메인 스레드 JDBC 호출 0회. 접속 시점 로드는 `AsyncPlayerPreLoginEvent`(이미 비동기)에서 수행 |
| 플레이스홀더 폭주 (TAB 등이 초당 수십 회 호출) | 플레이스홀더는 **메모리 캐시만 조회**. DB·계산 없음. 스텟 총합은 변경 시점에만 재계산해 저장 |
| Attribute 재적용 비용 | 장착/획득/탈착 등 **상태 변경 시에만** 호출. 주기적 재적용 없음 |
| 상시 반복 태스크 | 없음. 자동 저장만 N분 간격 비동기 1개 (dirty 프로필만) |
| GUI 아이템 생성 비용 | 페이지 단위 생성(45칸), 정적 버튼은 재사용. 클릭 시 필요한 페이지만 재빌드 |
| 대량 지급(`giveall`) | 온라인은 캐시 갱신, 오프라인은 단일 배치 트랜잭션으로 비동기 처리 |
| 저장 폭주 | dirty 플래그 + 배치 저장. 매 조작마다 전체 저장하지 않음 |
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

### 관리자 (`titleforge.admin`)

| 명령어 | 설명 |
|---|---|
| `/it create <title\|seal> <ID> <표시이름...>` | 생성 |
| `/it delete <title\|seal> <ID>` | 삭제 (보유 기록까지 정리) |
| `/it edit <type> <ID> name\|lore\|rarity\|icon\|permission\|hidden\|order <값...>` | 수정 |
| `/it edit <type> <ID> stat <equip\|own> <스텟> <수치>` | 스텟 수정 |
| `/it give\|take <플레이어> <type> <ID>` | 지급/회수 (오프라인 지원) |
| `/it giveall <type> <ID>` | 전체 지급 |
| `/it setnick <플레이어> <닉네임>` / `/it resetnick <플레이어>` | 닉네임 관리 |
| `/it admin` | 관리 GUI |
| `/it reload` | 설정 리로드 |

모든 단계에 문맥 인식 탭 완성 제공.

---

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

전부 캐시 조회이므로 TAB 플러그인이 고빈도로 호출해도 안전합니다.

---

## 7. 칭호 플러그인 맞춤 지침 (개발 규칙)

이 플러그인에 코드를 추가·수정할 때 반드시 지키는 규칙입니다.

### 7.1 스레드 규칙 (최우선)

1. **메인 스레드에서 JDBC·파일 I/O·네트워크 호출 금지.** 예외 없음. 저장소 접근은 `Sched.async` 안에서만.
2. Bukkit 엔티티/인벤토리 API는 **반드시** 메인(또는 해당 엔티티 리전) 스레드에서. 비동기 → `Sched.entity(player) { }` 로 복귀.
3. `Thread.sleep`, `Future.get()`, `join()` 을 메인 스레드에서 호출하지 않습니다.
4. 새 반복 태스크(`runTaskTimer`)를 추가하지 않습니다. 필요하면 이벤트 기반으로 바꿉니다.

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
16. 스텟을 새로 추가할 때는 `StatType`에만 항목을 추가하면 저장·GUI·플레이스홀더·명령어가 자동으로 따라오도록 유지합니다(하드코딩된 스텟 분기 금지).
17. AttributeModifier는 반드시 `titleforge:` 네임스페이스 키로 부착하고, 재적용 전 같은 네임스페이스만 골라 제거합니다. 타 플러그인의 모디파이어를 건드리지 않습니다.
18. 최대 체력 감소 시 현재 체력 클램프를 반드시 수행합니다(즉사 방지).

### 7.5 UX·안전 규칙

19. 파괴적 동작(삭제, 전체 지급, 닉네임 초기화)은 확인 단계를 거칩니다.
20. GUI 클릭은 기본 전부 취소(`setCancelled(true)`) 후 명시적으로 허용된 동작만 수행합니다. 아이템 복사 경로를 만들지 않습니다.
21. 유저 입력(닉네임, 칭호 ID)은 화이트리스트 정규식으로 검증합니다. ID는 `[a-z0-9_]{1,32}` 소문자만 허용.
22. 유저가 입력한 문자열을 MiniMessage로 파싱할 때는 **관리자 입력에만** 서식 태그를 허용하고, 일반 유저 닉네임은 서식 태그를 이스케이프합니다(색상 주입 방지).

---

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

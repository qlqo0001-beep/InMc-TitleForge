# InMc-TitleForge 사용 안내

> 운영자·플레이어용 안내서입니다. 저장소·스텟·이름표의 자세한 사양은 `README.md` 를 보세요.

## 이 플러그인은

**칭호 · 인장 · 닉네임**을 한 플러그인에서 관리합니다.

| | |
|---|---|
| 칭호 | 모을수록 강해지는 수집 요소. **보유 스텟**(갖고만 있어도) + **장착 스텟**(장착 시) |
| 인장 | 칭호와 같은 구조의 명예 표시. **스텟 없음** |
| 슬롯 3개 | 표시 칭호(보이는 것) · 능력치 칭호(스텟만) · 인장 — 서로 독립 |
| 닉네임 | 표시 이름 변경. 허용 문자·길이·비용(화폐 — 예: 캐시)·쿨타임 설정 가능. **처음 한 번은 비용 없이**(`first-change-free`) |
| 보유 기한 | 기본 영구. 기간을 붙여 지급하면 만료 시 자동 회수 |

## 설치

1. `inmc-core` 와 `InMc-TitleForge-1.0.0.jar` 를 `plugins/` 에 넣습니다.
2. 서버를 켜면 `config.yml` · `messages.yml` · `stats.yml` 이 생깁니다.
3. 기본 저장소는 SQLite 라 **추가 설정 없이 바로 동작합니다** (MariaDB 로 바꿀 수 있습니다).

## 명령어

`/it` · 별칭 `/titleforge` `/tf` `/칭호` — 인자 없이 쓰면 메인 GUI

플레이어 (`titleforge.use`, 기본 모두)

| 명령어 | 하는 일 |
|---|---|
| `/it` · `/it menu` | 메인 GUI |
| `/it title` · `/it seal` | 칭호 · 인장 보관함 |
| `/it info [플레이어]` | 내 정보 (남의 것은 `titleforge.info.other`) |
| `/it equip [칭호]` · `/it show [칭호]` · `/it seal [인장]` | 능력치 · 표시 · 인장 슬롯 장착 |
| `/it unequip <stat\|show\|seal>` | 해제 |
| `/it nick` · `/it nick reset` | 닉네임 변경 (`titleforge.nickname`) · 원래 아이디로 |
| `/it rank [title\|seal]` | 수집 순위 |

관리자 (`titleforge.admin`, 기본 OP — 나머지 권한을 포함합니다)

| 명령어 | 하는 일 |
|---|---|
| `/it admin` | 관리 GUI |
| `/it create\|delete <title\|seal> <ID>` | 만들기·지우기 |
| `/it edit <title\|seal> <ID> <필드> <값>` | 이름·설명·등급·아이콘·권한·숨김·순서·ID·스텟 |
| `/it give\|take <플레이어> <title\|seal> <ID> [기간]` | 지급·회수 (오프라인 가능) |
| `/it extend` · `/it giveall` | 기간 연장 · 전원 지급 |
| `/it setnick` · `/it resetnick` · `/it resetcooldown` | 닉네임 관리 |
| `/it player <플레이어>` | 보유 현황 · 지급/회수 GUI (오프라인 가능) |
| `/it checkitem` | 손에 든 아이템의 비용 아이템 인식 여부 |
| `/it reload` | 설정 다시 읽기 |
| `/it verify` | 서버 안 자동 검증 — 지급·장착·회수(원래대로 복구)·닉네임 형식·표시 이름·이름표·PAPI. 결과는 `plugins/InMc-TitleForge/verify/` |

거의 모든 서브커맨드에 한글 별칭이 있습니다(`칭호` `인장` `장착` `지급` `회수` …). 탭 완성은
영문 이름 기준입니다.

## 처음 해 볼 것

1. `/it create title 개척자 <gold>개척자` 로 칭호를 만듭니다.
2. `/it admin` 에서 스텟(보유/장착)과 설명을 채웁니다.
3. `/it give <플레이어> title 개척자 7d` 로 7일짜리로 지급해 봅니다.
4. 플레이어는 `/it` 에서 장착합니다.

## 설정 파일

| 파일 | 내용 |
|---|---|
| `config.yml` | 저장소 · 닉네임 규칙·비용 · 이름표/탭리스트 · 수집 마일스톤 |
| `messages.yml` | 메시지 |
| `stats.yml` | MMOItems 계열 스텟 35종 (바닐라 28종은 코드에 내장). `ci-stat` 로 커스텀아이템 능력치 id 를 지정할 수 있다(2026-10-09, 비우면 기본 대응표 — `stat/CustomItemStats`) |
| `places.yml` | 월드·생물군계를 보여줄 이름 — `/it admin` → **장소 이름**에서 고칩니다 |

## 연동 (전부 선택)

| 플러그인 | 없을 때 |
|---|---|
| PlaceholderAPI | `%titleforge_nickname%` · `%titleforge_title_display%` · `%titleforge_seal%` · `%titleforge_stat_<스텟>%` · `%titleforge_rank_title%` · `%titleforge_world%` · `%titleforge_biome%` 등 비활성 |
| Vault | 닉네임 돈 비용 자동 면제 |
| MythicLib | MMO 스텟은 MythicLib 로 적용. 없고 **커스텀아이템**이 있으면 그쪽 능력치로 적용(2026-10-09, core 바깥 능력치 출처) — 둘 다 없으면 값만 보관(바닐라 28종은 그대로 동작) |
| MMOItems | MMOItems 비용 아이템 획득 불가. 비용 아이템은 `use-type: custom` + `custom-id: inmc:아이디`(커스텀아이템, 2026-10-09)로도 둘 수 있다 |
| 업적(inmc-achievements) | 업적 보상으로 칭호를 줄 수 있습니다 |

## 주의할 점

- 칭호·인장 ID 는 소문자 영문·숫자·밑줄·한글, 1~32자입니다.
- 칭호 편집 화면의 **칭호 꾸미기**: 괄호 모양 · 괄호 색 · 글자 색(색 표 좌클릭 = 시작 색, 우클릭 = 끝 색 → 그라데이션) · 굵게를 골라 **적용**.
  만든 이름은 태그를 다 닫아서 뒤따르는 닉네임에 색이 번지지 않습니다(`&3[` 처럼 직접 적으면 번질 수 있습니다).
- 인장은 어떤 경로로도 스텟을 가질 수 없습니다.
- 일반 유저 닉네임의 서식 태그는 무력화됩니다(색은 관리자의 `/it setnick` 으로만).

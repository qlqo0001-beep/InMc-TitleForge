# InMc-TitleForge 변경 기록

---

## 미배포 — 개인 표시 토글·표시명 API

- 테섭 "개인설정에 닉네임·칭호·인장 on/off + 타인 인장 끄기" — core 개인설정에 4키 등록
  (`titleforge.show-nickname/title/seal` 기본 ON, `titleforge.hide-others-seal` 기본 OFF).
  끄면 이름표·탭·채팅·PAPI가 미설정 문구/실명으로 보인다. 타인 인장 끄기는 공유 이름표(인장 줄)를 뷰어별로 숨긴다
- 타 플러그인 표시용 `TitleForgeApi.displayNameOf` 신설(평문, 표시 OFF면 null) + 플러그인 브릿지 `displayNameOf`.
  core `TitleForgeNames` 가 리플렉션으로 읽는다

> 이 폴더는 별도 git 저장소입니다(기본 브랜치 `claude/title-seal-system-plugin-m2b2lh`).

---

## 2026-10-02 — 닉네임 비용(화폐·첫 변경 무료)

- 테섭 의견 "닉변을 공짜로 마구 하면 서버에 안 좋다 — 캐시로, 처음 한 번은 무료": `nickname.cost.economy.currency`(inmc-economy 화폐 id —
  예 `cash`, 비우면 기본 화폐)와 `nickname.cost.first-change-free`(기본 켜짐 — 스스로 바꾼 적 없는 사람의 첫 변경은 비용 없이). 금액·켜기는
  `nickname.cost.economy.enabled`·`amount`

## 2026-10-02 — 칭호 꾸미기

- 테섭 요청 "괄호·색을 표에서 골라 편하게, 내용은 그라데이션": 칭호 편집 화면에 **칭호 꾸미기**(`gui/TitleDecorateMenu.kt`) — 괄호 9가지 ·
  괄호 색 9 · 글자 색 18(좌클릭 시작 · 우클릭 끝 = 그라데이션) · 굵게 · 글자. 미리 보고 [적용]. 만든 원문은 태그를 모두 닫고 글자는
  이스케이프한다(`badge/TitleStyle.kt` — 순수, `TitleStyleTest`)

## 2026-10-02 — 레거시 색만 쓴 칭호가 머리 위 닉네임까지 물들이던 것

- 테섭 신고 "칭호 괄호 색을 닉네임이 따라간다(채팅은 안 따라감)" — 칭호 `&3&l[&4&l깔깔슨&3&l]` 처럼 **`&` 색 코드만** 쓴 값은 `<` 가 없어
  "서식 없는 값"으로 보고 이름표 원문에 그대로 이어 붙였고, 다음 줄의 닉네임이 청록·굵게 됐다. 이제 레거시 색 코드도 서식으로 보고
  (`Text.hasFormatting`) Component 로 가둔다(`TokenRenderer.Session.render`)

## 2026-10-02 — 탭 완성에 닉네임만

- 다른 플러그인 명령어의 플레이어 이름 탭 완성에 **실제 아이디와 닉네임이 함께** 떴다. 이제 닉네임이 있는 사람은
  아이디를 빼고 닉네임만 띄운다(`ninesik` · `나인` → `나인`). 닉네임이 없는 사람은 아이디 그대로
- 닉네임으로 지목할 수 없을 때는 아이디를 남긴다 — `resolve: false`, `excluded-commands` 에 든 명령어, 닉네임이 겹치는
  사람(`unique: false`), 닉네임이 접속자 아이디와 같은 사람. 감추면 그 사람을 고를 방법이 없어서다.
  판정은 "고른 닉네임이 `realNameOf` 로 다시 그 사람에게 돌아오는가" 하나다(`NicknameIndex.isReplacedByNickname`)
- 아이디를 직접 쳐서 지목하는 것은 지금처럼 된다. 탭 목록에서만 빠진다
- 바닐라 명령어(`/msg`, `/tp`)의 제안은 클라이언트가 만들므로 여전히 아이디가 뜬다
- 확인: 실제 `NicknameIndex` · `NicknameNormalizer` · `NicknameCommandBridge` 를 스텁에 대고 탭 완성 8가지와 명령어 치환을
  실행해 봤다. 빌드는 못 했다(워크스페이스 없음)

## 2026-10-02 — 명령어 다리가 `/it` 인자까지 바꾸던 문제

- `NicknameCommandBridge` 는 자기 명령어를 건너뛰려고 `plugin.getCommand(label)` 을 봤는데, Brigadier 로 등록한
  `/it` 은 `PluginCommand` 가 아니라 **늘 null** 이었다. 그래서 접속자의 닉네임과 같은 인자가 그 사람의 아이디로 바뀌었다
  (`/it create title 나인` → 칭호 ID `ninesik`, `/it setnick … 나인` → 닉네임 `ninesik`)
- `TitleForgeCommand.LABELS`(이름·별칭)로 알아본다. 대상 자리는 지금처럼 `/it` 이 직접 닉네임을 푼다(`onlineTarget` · `resolveBlocking`)
- 확인: 수정 전 코드로 위 두 경우가 재현되는 것과, 수정 후 `/it`·`/tf`·`/칭호`·`/inmc-titleforge:it` 은 그대로이고
  `/lands trust 은효`·`/msg 나인 …` 은 여전히 바뀌는 것을 스텁에 대고 실행해 봤다. 빌드는 못 했다(워크스페이스 없음)

## 2026-10-02 — 다른 플러그인 다이얼로그에서도 닉네임으로 지목

- Lands 의 "Add Player" 창에 닉네임을 치면 대상을 못 찾았다. 그 창은 입력값을 명령어가 아니라 다이얼로그(커스텀 클릭)로
  받아 `NicknameCommandBridge` 를 지나지 않는다
- `NicknameDialogBridge`: `PlayerCustomClickEvent`(LOWEST)에서 입력칸 값 **전체**가 접속 중인 사람의 닉네임이면 실제
  아이디로 바꿔 둔다. Paper 는 같은 NBT 객체를 이벤트 → `customClick` 콜백 순으로 넘기므로 어느 쪽으로 받든 바뀐 값을 본다.
  API 가 읽기 전용이라 쓰기는 리플렉션이고, 실패하면 경고 한 번 남기고 꺼진다
- 새 설정 `nickname.command-bridge.resolve-dialog`(기본 `true`) — 이미 깔린 `config.yml` 에 없어도 켜진다
- 자기 입력창 키 `value` → `titleforge_value`. 닉네임 변경 창 등 자기 창은 치환하지 않으려고 이 키로 알아본다
- 한계: 접속 중인 사람만(명령어 다리와 같다). Lands 가 패킷을 직접 가로채 읽는다면 효과가 없다 — **테스트 서버에서 아직
  확인하지 못했다.** 빌드도 못 해 봤고(워크스페이스 없음), 새 파일만 스텁에 대고 컴파일·동작 검사했다

## 2026-09-30 — 옛 자바스크립트 장소 토큰을 직접 푼다(서버 멈춤)

- 테섭 워치독(05:24, "10초 응답 없음"): 접속 직후 탭리스트의 `%javascript_world_name%`·`%javascript_biome%` 가 PAPI 자바스크립트 확장(Nashorn)을
  메인 스레드에서 처음 컴파일하며 서버를 멈췄다. 이 둘은 이 플러그인의 장소 이름(`places.yml`)과 같은 값이라 **PAPI 에 넘기지 않고 직접** 푼다
  (`TokenRenderer.LEGACY_SCRIPTS` → `world_mini`·`biome_mini`). 설정을 `%titleforge_world_mini%`·`%titleforge_biome_mini%` 로 바꾸는 것을 권장

## 2026-09-30 — 저장소에 올림

- 워크스페이스 작업(2026-09-11 ~ 25)을 처음으로 이 저장소에 올렸다. 그 전 저장소는 2026-08-19 그대로였다
- **이 폴더 혼자서는 빌드되지 않는다** — 자체 `gradlew`·`settings.gradle.kts`·`build.bat` 을 지웠다. 워크스페이스 루트에서
  `./gradlew :titleforge:build`(옆에 `inmc-core` 가 있어야 한다). `plugin.yml` → `paper-plugin.yml`
- `VaultHook` 을 지우고 core `EconomyHook` 으로
- 2026-08-23 브랜치 `claude/nametag-lag-issue-jbgz3u`(다른 플러그인 명령어에서 닉네임으로 사람 지목하기)가 들어 있다 — 그 브랜치는 지웠다
- 설계 문서: 보류된 계획이 적힌 둘(`IMPLEMENTATION_PLAN.md` · 가상 TextDisplay 이름표 전환 계획)은 `docs/` 로 옮겼다.
  `NicknameCommandIntegration` 두 문서는 구현돼(`NicknameCommandBridge`) 지웠다 — git 기록에 남아 있다
- `bin/`(VS Code 자바 확장이 만드는 소스 복사본)을 `.gitignore` 에 넣었다

## 2026-09-25 — 자기 플레이스홀더는 PAPI 없이

- 이름표·탭리스트의 `%titleforge_…%` 를 **PlaceholderAPI 없이** 직접 푼다(`hook/TitleForgeValues` — PAPI 확장은 이걸 부르기만).
  전에는 자기 값인데도 PAPI 를 거쳐야 해서, PAPI 가 없거나 안 켜지면 월드·생물군계 줄이 글자 그대로 나왔다
- 새 내장 토큰 `%tf_balance%` — 기본 화폐 잔고(core 화폐 → 없으면 Vault). `%inmceco_formatted%`(PAPI)도 그대로 된다
- 기본 탭리스트: 좌표 `%player_x/y/z%`(PAPI 의 Player 확장 필요) → 내장 `%tf_x/y/z%`, 소지금 → `%tf_balance%`.
  이미 깔린 `config.yml` 은 직접 바꿔야 합니다

## 2026-09-24 — 탭리스트 소지금

- 기본 `config.yml` 탭리스트의 `%cmi_user_balance_formatted%` → `%inmceco_formatted%`(inmc-economy 기본 화폐), 재계산 주기 표도.
  CMI 경제를 쓰지 않게 되어서. 이미 깔린 `config.yml` 은 직접 바꿔야 합니다

## 2026-09-24 — 월드 · 생물군계 이름

- **PlaceholderAPI 스크립트 두 개(`world_name.js` · `biome.js`)를 옮겼습니다.**
  `%titleforge_world%` · `%titleforge_biome%` (레거시 `§`), `_mini`(MiniMessage 원문 — 옛 스크립트와 같은 값), `_id`(실제 이름·id)
- 이름은 새 파일 `places.yml`. 기본값은 두 스크립트의 월드 7개 · 생물군계 185개에, 운영 서버 데이터팩과 대조해
  스크립트가 빠뜨린 4개(Terralith 2.6.2 `alpha_islands` · `alpha_islands_winter` · `deep_warm_ocean`, 바닐라 26.2
  `sulfur_caves`)를 더했습니다. 옛 `biome.js` 의 산악 스텝 색(`#AABAA`, 다섯 자리라 태그가 글자로 찍혔음)은 `#AABAAA` 로 고쳤습니다
- **기본 이름이 늘면 켤 때 이미 깔린 `places.yml` 에도 자동으로 더합니다**(고친 것은 그대로, 지운 것은 `''` 로 남아 안 되살아남).
  테스트 서버의 옛 파일에 4개가 저절로 들어가는 것 확인
- **GUI**: `/it admin` → 장소 이름. 이 서버의 월드·생물군계(데이터팩 포함)와 이름만 적힌 것을 보여 줍니다.
  좌클릭으로 이름을 정하고(입력창에 지금 값이 채워져 있음) Shift+우클릭으로 지웁니다(확인창). "지금 있는 곳" 버튼
- 스레드: TAB 처럼 비동기로 묻는 쪽에는 마지막으로 읽은 위치를 줍니다. 생물군계 칸을 넘을 때만 읽고,
  아무도 안 물으면 이동 사건에서 바로 돌아갑니다. 새 반복 작업은 없습니다
- 저장: GUI 로 고치면 `places.yml` 을 워커에서 임시 파일에 쓴 뒤 바꿔 끼웁니다(머리 주석 유지)
- 기본 `config.yml` 탭리스트 머리말의 `%javascript_world_name%` · `%javascript_biome%` 를 `%titleforge_world_mini%` ·
  `%titleforge_biome_mini%` 로, 재계산 주기 표의 열쇠도 같이 바꿨습니다. 이미 깔린 `config.yml` 은 직접 바꿔야 합니다
- 테스트 118 → **131**(바닐라 생물군계 전부에 이름이 있는지 — paper-api 를 올리면 새 생물군계가 여기서 걸린다). PlaceholderAPI 를 거친 실제 치환은 확인하지 못했습니다 — 테스트 서버(Paper 26.2)에서
  PlaceholderAPI 2.12.3-DEV 가 버전 문자열을 못 읽어 켜지지 않았습니다

## 2026-09-23 — 검증

- 테스트 **118개 통과**. 권한 선언(`titleforge.use` · `nickname` · `info.other` · `admin`) · 명령어 · 메시지 키를 점검했고 결함은 없었습니다
- 업적 플러그인이 `BadgeGrantEvent` 를 **구독만** 하고, 칭호 보상은 `TitleForgeApi.grant` 를 리플렉션으로 부릅니다 —
  이 플러그인 코드는 바뀌지 않았습니다
- `GUIDE.md` 추가

## 2026-09-11 — 10b: core 연결

- `hook/VaultHook` 34줄 삭제 → core `EconomyHook` (닉네임 비용 `has`/`withdraw`/`format`)
- 나머지(`Text` · `Messages` · `Menu` · `Sched` · `DurationParser` · `MMOItemsHook` · `SqlStorage`)는 옮기지 않았습니다 —
  치환 문법이 달라 설정 파일이 깨지거나 Folia 호환을 잃는 대가가 따랐습니다

## 2026-09-11 — 10a: 워크스페이스 빌드 편입

- `:titleforge` 모듈로 편입, `inmc.paper-plugin` 관례 사용 (`api-version` 이 컴파일 대상에서 유도됨)
- `plugin.yml` → `paper-plugin.yml` (선택 연동은 `load: OMIT`, `inmc-core` 필수)
- 명령어를 `BasicCommand` + `LifecycleEvents.COMMANDS` 로 (본문은 그대로)
- kotlin-stdlib 번들 제거(core 가 제공), HikariCP·bStats relocate 유지

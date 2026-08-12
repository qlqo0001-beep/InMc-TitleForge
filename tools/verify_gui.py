#!/usr/bin/env python3
"""
GUI 정적 검증기.

컴파일 없이 확인할 수 있는 것들을 점검한다.

  1. 코드가 참조하는 messages.yml 키가 전부 존재하는지 (Gui.item 의 .name/.lore 규칙 포함)
  2. messages.yml 에 있지만 아무 데서도 안 쓰는 키 (오타·잔재 탐지)
  3. Settings 가 읽는 config.yml 키가 전부 존재하는지
  4. 스텟 편집 창 슬롯 배치 시뮬레이션 — 충돌 / 범위 초과 / 누락
  5. 메뉴 슬롯 하드코딩 값이 창 크기를 넘지 않는지

사용: python3 tools/verify_gui.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("PyYAML 이 필요합니다: pip install pyyaml")

ROOT = Path(__file__).resolve().parent.parent
KOTLIN = ROOT / "src/main/kotlin"
RESOURCES = ROOT / "src/main/resources"

failures: list[str] = []
warnings: list[str] = []


def fail(msg: str) -> None:
    failures.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def flatten(node, prefix="") -> set[str]:
    keys = set()
    if isinstance(node, dict):
        for key, value in node.items():
            keys.add(f"{prefix}{key}")
            keys |= flatten(value, f"{prefix}{key}.")
    return keys


def kotlin_sources() -> list[Path]:
    return sorted(KOTLIN.rglob("*.kt"))


def read_all() -> str:
    return "\n".join(path.read_text(encoding="utf-8") for path in kotlin_sources())


# ── 1~3. 키 교차검증 ────────────────────────────────────────────────────────

MESSAGE_ROOTS = (
    "gui", "stat", "input", "badge", "nickname", "general", "player", "help",
    "rank", "placeholder",
)
CONFIG_ROOTS = ("storage", "title", "seal", "nickname", "display", "gui", "rank", "debug")

KEY_PATTERN = re.compile(r'"((?:%s)\.[a-z0-9._-]+)"' % "|".join(MESSAGE_ROOTS))
CONFIG_PATTERN = re.compile(r'(?:getString|getInt|getLong|getBoolean|getDouble|getStringList|getConfigurationSection)\(\s*"([a-z0-9._-]+)"')


def check_keys() -> None:
    source = read_all()
    messages = yaml.safe_load((RESOURCES / "messages.yml").read_text(encoding="utf-8"))
    config = yaml.safe_load((RESOURCES / "config.yml").read_text(encoding="utf-8"))

    message_keys = flatten(messages)
    config_keys = flatten(config)

    # config.yml 을 읽는 곳은 Settings 뿐이다. (다른 파일의 getString 은 JDBC 컬럼명이다)
    settings_source = (KOTLIN / "kr/inmc/titleforge/config/Settings.kt").read_text(encoding="utf-8")
    config_used = set(CONFIG_PATTERN.findall(settings_source))
    # 스텟/마일스톤처럼 동적으로 만들어지는 하위 키는 섹션 단위로만 검사한다.
    for key in sorted(config_used):
        if key in config_keys:
            continue
        if any(existing.startswith(key + ".") for existing in config_keys):
            continue
        fail(f"config.yml 누락: {key}")

    used_messages = set(KEY_PATTERN.findall(source))
    # Gui.item(path) 는 messages 에서 "<path>.name" / "<path>.lore" 를 읽는다.
    dynamic = {"gui.button.slot-"}  # 슬롯 id 로 조립되는 경로
    for key in sorted(used_messages):
        if key in config_used or key in config_keys:
            continue  # config 경로와 이름이 겹치는 경우
        if key in message_keys or f"{key}.name" in message_keys:
            continue
        if any(key.startswith(prefix) for prefix in dynamic):
            continue
        fail(f"messages.yml 누락: {key}")

    # 조립 경로 확인
    for slot_id in ("stat", "show", "seal"):
        key = f"gui.button.slot-{slot_id}.name"
        if key not in message_keys:
            fail(f"messages.yml 누락: {key}")

    # 미사용 키 (leaf 만)
    leaves = {
        key for key in message_keys
        if not any(other.startswith(key + ".") for other in message_keys)
    }
    for key in sorted(leaves):
        base = key.rsplit(".", 1)[0]
        if key in used_messages or base in used_messages:
            continue
        if key.startswith(("help.", "prefix")) or base.startswith("help"):
            continue
        if key.rsplit(".", 1)[-1].isdigit():
            continue  # 리스트 항목
        if base.startswith("gui.button.slot-"):
            continue
        warn(f"messages.yml 미사용 추정: {key}")


# ── 4. 스텟 슬롯 배치 시뮬레이션 (StatLayout.kt 규칙과 동일) ────────────────

ROWS, COLUMNS = 6, 9
SIZE = ROWS * COLUMNS
HEADER_ROW, FOOTER_ROW = 0, ROWS - 1
CATEGORY_ROWS = list(range(HEADER_ROW + 1, FOOTER_ROW))
MAX_PER_ROW = COLUMNS - 1
HEADER_SLOTS = {0: "back", 4: "summary", 8: "reset-all"}
FOOTER_SLOTS = {
    FOOTER_ROW * COLUMNS: "help",
    FOOTER_ROW * COLUMNS + 2: "prev",
    FOOTER_ROW * COLUMNS + 4: "legend",
    FOOTER_ROW * COLUMNS + 6: "next",
    FOOTER_ROW * COLUMNS + 8: "back",
}


def parse_stats() -> list[tuple[str, str]]:
    """
    실제 레지스트리 구성과 동일하게 (스텟 id, 분류) 를 모은다.

    바닐라 기본값은 StatRegistry.defaults() 에서, MMO/가상 스텟은 stats.yml 에서 읽는다.
    """
    stats: dict[str, str] = {}

    # 1) 코드에 내장된 바닐라 기본 정의
    text = (KOTLIN / "kr/inmc/titleforge/stat/StatRegistry.kt").read_text(encoding="utf-8")
    body = text.split("fun defaults()", 1)[1]
    pattern = re.compile(
        r'id\s*=\s*"([a-z0-9_]+)".*?category\s*=\s*StatCategory\.([A-Z_]+)',
        re.DOTALL,
    )
    for stat_id, category in pattern.findall(body):
        stats[stat_id] = category

    # 2) stats.yml 정의 (같은 id 면 덮어쓴다 — 레지스트리와 동일한 규칙)
    stats_file = RESOURCES / "stats.yml"
    if stats_file.exists():
        data = yaml.safe_load(stats_file.read_text(encoding="utf-8")) or {}
        for stat_id, node in data.items():
            if not isinstance(node, dict):
                continue
            category = str(node.get("category", "")).upper() or stats.get(stat_id, "UTILITY")
            kind = str(node.get("kind", "")).lower()
            if kind not in ("", "vanilla", "mmo", "virtual"):
                fail(f"stats.yml: {stat_id} 의 kind 가 잘못됐습니다: {kind}")
            if kind == "vanilla" and "attribute" not in node and stat_id not in stats:
                fail(f"stats.yml: {stat_id} 는 kind=vanilla 인데 attribute 가 없습니다.")
            if kind == "mmo" and "mmo-stat" not in node:
                fail(f"stats.yml: {stat_id} 는 kind=mmo 인데 mmo-stat 이 없습니다.")
            if not re.fullmatch(r"[a-z0-9_]{1,48}", str(stat_id)):
                fail(f"stats.yml: 잘못된 스텟 id: {stat_id}")
            stats[stat_id] = category

    return list(stats.items())


def parse_categories() -> list[str]:
    text = (KOTLIN / "kr/inmc/titleforge/stat/StatCategory.kt").read_text(encoding="utf-8")
    body = text.split("enum class StatCategory", 1)[1].split("\n    ;", 1)[0]
    return re.findall(r'^\s{4}([A-Z_]+)\(', body, re.MULTILINE)


def check_layout() -> None:
    """StatLayout.kt 와 동일한 규칙으로 페이지 배치를 시뮬레이션한다."""
    stats = parse_stats()
    categories = parse_categories()
    if not stats or not categories:
        fail("StatRegistry / StatCategory 파싱 실패")
        return

    unknown = {c for _, c in stats} - set(categories)
    if unknown:
        fail(f"StatCategory 에 없는 분류를 참조하는 스텟이 있습니다: {unknown}")
        return

    grouped = {c: [s for s, cat in stats if cat == c] for c in categories}

    # 1) 분류별로 8개씩 끊어 행을 만든다.
    rows = []
    for category in categories:
        members = grouped.get(category, [])
        for index in range(0, len(members), MAX_PER_ROW):
            rows.append((category, members[index:index + MAX_PER_ROW], index > 0))

    rows_per_page = len(CATEGORY_ROWS)
    page_count = max(1, (len(rows) + rows_per_page - 1) // rows_per_page)

    chrome = set(HEADER_SLOTS) | set(FOOTER_SLOTS)
    placed = {}

    for page in range(page_count):
        occupied = dict.fromkeys(chrome, "chrome")
        slice_rows = rows[page * rows_per_page:(page + 1) * rows_per_page]
        print(f"  ── {page + 1}/{page_count} 페이지 ──")
        grid = {}
        for index, (category, members, continuation) in enumerate(slice_rows):
            gui_row = CATEGORY_ROWS[index]
            label_slot = gui_row * COLUMNS
            if label_slot in occupied:
                fail(f"슬롯 충돌: {label_slot} (label:{category})")
            occupied[label_slot] = f"label:{category}"
            grid[label_slot] = category + ("+" if continuation else "")
            for column, stat in enumerate(members):
                slot = gui_row * COLUMNS + 1 + column
                if slot in occupied:
                    fail(f"슬롯 충돌: {slot} (stat:{stat} vs {occupied[slot]})")
                if not (0 <= slot < SIZE):
                    fail(f"슬롯 범위 초과: {slot} (stat:{stat})")
                occupied[slot] = f"stat:{stat}"
                grid[slot] = stat
                if stat in placed:
                    fail(f"스텟이 두 번 배치되었습니다: {stat}")
                placed[stat] = (page, slot)

        for row in range(ROWS):
            cells = []
            for column in range(COLUMNS):
                slot = row * COLUMNS + column
                text = grid.get(slot) or ({**HEADER_SLOTS, **FOOTER_SLOTS}.get(slot, "")) or "·"
                cells.append(text[:13].ljust(13))
            print("   |" + "|".join(cells) + "|")

    missing = [s for s, _ in stats if s not in placed]
    if missing:
        fail(f"어느 페이지에도 배치되지 않은 스텟: {missing}")

    print(f"  스텟 {len(stats)}개 / 분류 {len(categories)}개 / {page_count}페이지 배치 확인")


# ── 5. 메뉴 슬롯 하드코딩 범위 검사 ────────────────────────────────────────

ROWS_PATTERN = re.compile(r"rows\s*=\s*(\d+)")
BUTTON_PATTERN = re.compile(r"^\s*button\(\s*(\d+)\s*,", re.MULTILINE)


def check_menu_slots() -> None:
    for path in (KOTLIN / "kr/inmc/titleforge/gui").glob("*.kt"):
        text = path.read_text(encoding="utf-8")
        rows_match = ROWS_PATTERN.search(text)
        if not rows_match:
            continue
        size = int(rows_match.group(1)) * 9
        for raw in BUTTON_PATTERN.findall(text):
            slot = int(raw)
            if not (0 <= slot < size):
                fail(f"{path.name}: 하드코딩 슬롯 {slot} 이 창 크기 {size} 를 벗어납니다.")
        print(f"  {path.name}: rows={rows_match.group(1)} (크기 {size}) 슬롯 검사 통과")


# ── 6. 인벤토리 이벤트 중 open() 직접 호출 금지 ────────────────────────────

def check_open_later() -> None:
    """GUI 안에서의 메뉴 전환은 반드시 openLater() 를 써야 한다."""
    for path in (KOTLIN / "kr/inmc/titleforge/gui").glob("*.kt"):
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith(("*", "//", "/*")):
                continue
            if re.search(r"\.open\(\)", stripped) and "openLater" not in stripped:
                fail(f"{path.name}:{number} GUI 안에서는 openLater() 를 쓰세요 → {stripped}")
    print("  GUI 내부 메뉴 전환이 모두 openLater() 사용")


def main() -> int:
    print("[1/4] 메시지 · 설정 키 교차검증")
    check_keys()
    print("[2/4] 스텟 편집 창 슬롯 배치")
    check_layout()
    print("[3/4] 메뉴 슬롯 범위")
    check_menu_slots()
    print("[4/4] 메뉴 전환 규칙")
    check_open_later()

    print()
    for message in warnings:
        print(f"  경고: {message}")
    if failures:
        print()
        for message in failures:
            print(f"  실패: {message}")
        print(f"\n실패 {len(failures)}건")
        return 1
    print(f"\n통과 (경고 {len(warnings)}건)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

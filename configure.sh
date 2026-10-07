#!/usr/bin/env bash
# Configure this Kotlin Multiplatform project template.
ROOT="$(cd "$(dirname "$0")" && pwd)" || exit 1
export CONFIGURE_ROOT="$ROOT"
exec python3 - "$@" <<'PY'
import html
import os
import re
import sys
from pathlib import Path

ROOT = Path(os.environ["CONFIGURE_ROOT"]).resolve()

SKIP_DIRS = {".git", ".gradle", ".idea", ".kotlin", "build", "kotlin-js-store"}
TEXT_SUFFIXES = {".kt", ".kts", ".html", ".md", ".properties", ".xml", ".json", ".toml"}
SOURCE_ROOTS = (
    "composeApp/src/commonMain/kotlin",
    "composeApp/src/desktopMain/kotlin",
    "composeApp/src/wasmJsMain/kotlin",
)
INDEX_HTML = Path("composeApp/src/wasmJsMain/resources/index.html")
PROPS_FILE = Path("gradle.properties")
MARKER = "configure:app.displayName"
MARKER_RE = re.compile(
    r'^(\s*const val APP_NAME:\s*String\s*=\s*")(?:\\.|[^"\\])*("\s*//\s*configure:app\.displayName)\s*$'
)
VERSION_MARKER = "configure:app.version"
VERSION_MARKER_RE = re.compile(
    r'^(\s*const val NAME:\s*String\s*=\s*")(?:\\.|[^"\\])*("\s*//\s*configure:app\.version)\s*$'
)
VERSION_RE = re.compile(r"^(?:0|[1-9]\d*)(?:\.(?:0|[1-9]\d*)){0,2}$")
TITLE_RE = re.compile(r"<title>(.*?)</title>", re.DOTALL)
IDENT_KEYS = ("app.displayName", "app.rootName", "app.group", "app.version")
HELP_COMMANDS = {"", "list", "help", "-h", "--help", "—help", "–help"}
# Hard keywords cannot be package segments. Soft keywords such as "get" can.
HARD_KEYWORDS = {
    "as", "break", "class", "continue", "do", "else", "false", "for", "fun",
    "if", "in", "interface", "is", "null", "object", "package", "return",
    "super", "this", "throw", "true", "try", "typealias", "typeof", "val",
    "var", "when", "while",
}
FORBIDDEN_RE = re.compile(r'["\\$#=\r\n]')


def die(message, code=1):
    print(message, file=sys.stderr)
    raise SystemExit(code)


def rel(path):
    return path.resolve().relative_to(ROOT).as_posix()


def load_props(text):
    values = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def read_props():
    path = ROOT / PROPS_FILE
    if not path.is_file():
        die("找不到 gradle.properties。")
    text = path.read_text(encoding="utf-8")
    values = load_props(text)
    missing = [key for key in IDENT_KEYS if not values.get(key)]
    if missing:
        die("gradle.properties 缺少：" + "、".join(missing))
    return text, values


def help_text(values=None):
    if values is None:
        display, root_name, group, version = (
            "（尚未設定）",
            "（尚未設定）",
            "（尚未設定）",
            "（尚未設定）",
        )
    else:
        display = values.get("app.displayName") or "（尚未設定）"
        root_name = values.get("app.rootName") or "（尚未設定）"
        group = values.get("app.group") or "（尚未設定）"
        version = values.get("app.version") or "（尚未設定）"
    return f"""Kotlin Multiplatform 專案模板設定

把這個目錄複製成新專案的起點後，用這個指令換掉範本名稱或版號。
資料夾名稱請在複製時自己取。這個指令不會重新命名所在的目錄。

用法：
  ./configure.sh
  ./configure.sh list
  ./configure.sh --help
  ./configure.sh proj_name <名稱>
  ./configure.sh version <版號>

第一個參數：
  list, help, -h, --help
      顯示這份說明。

  proj_name <名稱>
      把專案顯示名稱改成 <名稱>。
      視窗標題、網頁標題、主畫面、About、macOS 選單與 Dock 都讀這個名稱。
      名稱可含空白；不加引號的多個參數會用空白接起來。

      若名稱含英文或數字，會一併更新：
        Gradle 專案名    Stock Viewer → StockViewer
        套件最後一段      只換最後一段，Stock Viewer → stockviewer
      組織前綴維持不變。只換最後一段。
      沒有英數內容的名稱（例如純中文）只改顯示字串。

      名稱不可包含 " \\ $ # = 或換行。

  version <版號>
      把產品版號改成 <版號>。About 的第二行、Gradle 版本與
      打包版號都讀這個值。
      格式是 MAJOR、MAJOR.MINOR 或 MAJOR.MINOR.PATCH，例如 0.2 或 0.2.0。
      不含 v 前綴。不會改 Kotlin、Compose 或函式庫的版號。

目前專案：
  顯示名稱  {display}
  Gradle    {root_name}
  套件      {group}
  版本      {version}

範例：
  ./configure.sh proj_name "Stock Viewer"
  ./configure.sh proj_name MyApp
  ./configure.sh version 0.2
"""


def print_help():
    try:
        _, values = read_props()
    except SystemExit:
        values = None
    print(help_text(values), end="")


def derive_technical(display):
    """Return (root_name, package_segment), or None when no ASCII token exists."""
    parts = []
    for token in re.split(r"[\s_\-]+", display.strip()):
        cleaned = re.sub(r"[^A-Za-z0-9]", "", token)
        if not cleaned:
            continue
        parts.append(cleaned[0].upper() + cleaned[1:])
    if not parts:
        return None
    root_name = "".join(parts)
    segment = root_name.lower()
    if not re.fullmatch(r"[a-z][a-z0-9_]*", segment):
        die(f"無法從「{display}」產生合法的套件名稱（須以英文字母開頭）。")
    if segment in HARD_KEYWORDS:
        die(f"套件名稱「{segment}」是 Kotlin 關鍵字，請改用其他名稱。")
    return root_name, segment


def new_group_for(old_group, segment):
    pieces = old_group.split(".")
    if len(pieces) < 2 or any(piece == "" for piece in pieces):
        die(f"app.group 不是合法套件：{old_group}")
    return ".".join(pieces[:-1] + [segment])


def iter_text_files():
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [
            name for name in dirnames
            if name not in SKIP_DIRS and not name.startswith(".")
        ]
        for name in filenames:
            if name == "configure.sh":
                continue
            path = Path(dirpath) / name
            if path.suffix not in TEXT_SUFFIXES:
                continue
            yield path


def marker_values(text):
    found = []
    for line in text.splitlines():
        if MARKER not in line:
            continue
        if not MARKER_RE.match(line):
            die("找到 configure 標記，但該行格式無法更新：\n" + line)
        value = re.match(
            r'\s*const val APP_NAME:\s*String\s*=\s*"((?:\\.|[^"\\])*)"',
            line,
        )
        if not value:
            die("找不到 APP_NAME 字串：" + line)
        found.append(value.group(1))
    return found


def find_marker():
    found = []
    for path in iter_text_files():
        if path.suffix != ".kt":
            continue
        text = path.read_text(encoding="utf-8")
        values = marker_values(text)
        for value in values:
            found.append((path, value))
    if len(found) != 1:
        die(f"APP_NAME 的 configure 標記必須正好一處，目前有 {len(found)} 處。")
    return found[0]


def find_title():
    path = ROOT / INDEX_HTML
    if not path.is_file():
        die(f"找不到 {INDEX_HTML.as_posix()}。")
    text = path.read_text(encoding="utf-8")
    matches = list(TITLE_RE.finditer(text))
    if len(matches) != 1:
        die(f"{INDEX_HTML.as_posix()} 必須正好有一個 <title>，目前有 {len(matches)} 個。")
    return path, html.unescape(matches[0].group(1).strip()), text


def planned_moves(old_group, new_group):
    moves = []
    if old_group == new_group:
        return moves
    for source_root in SOURCE_ROOTS:
        source = ROOT / source_root / Path(*old_group.split("."))
        dest = ROOT / source_root / Path(*new_group.split("."))
        if not source.is_dir():
            die(f"找不到套件目錄：{rel(source)}")
        if dest.exists():
            die(f"目的套件目錄已存在：{rel(dest)}")
        moves.append((source, dest))
    return moves


def apply_props(text, updates):
    lines = text.splitlines(keepends=True)
    seen = set()
    out = []
    for line in lines:
        ending = ""
        body = line
        for suffix in ("\r\n", "\n", "\r"):
            if body.endswith(suffix):
                body, ending = body[: -len(suffix)], suffix
                break
        stripped = body.lstrip()
        replaced = False
        if stripped and not stripped.startswith("#") and not stripped.startswith("!") and "=" in stripped:
            key = stripped.split("=", 1)[0].strip()
            if key in updates:
                indent = body[: len(body) - len(stripped)]
                body = f"{indent}{key}={updates[key]}"
                seen.add(key)
                replaced = True
        out.append(body + ending)
        if replaced:
            continue
    missing = [key for key in updates if key not in seen]
    if missing:
        die("gradle.properties 缺少：" + "、".join(missing))
    return "".join(out)


def apply_marker(text, name):
    lines = text.splitlines(keepends=True)
    count = 0
    out = []
    for line in lines:
        ending = ""
        body = line
        for suffix in ("\r\n", "\n", "\r"):
            if body.endswith(suffix):
                body, ending = body[: -len(suffix)], suffix
                break
        if MARKER in body:
            match = MARKER_RE.match(body)
            if not match:
                die("找到 configure 標記，但該行格式無法更新：\n" + body)
            body = f"{match.group(1)}{name}{match.group(2)}"
            count += 1
        out.append(body + ending)
    if count != 1:
        die(f"APP_NAME 標記更新次數是 {count}，預期 1。")
    return "".join(out)


def apply_title(text, name):
    matches = list(TITLE_RE.finditer(text))
    if len(matches) != 1:
        die("更新 <title> 失敗。")
    match = matches[0]
    escaped = html.escape(name, quote=False)
    return text[: match.start()] + f"<title>{escaped}</title>" + text[match.end():]


def replace_group(text, old_group, new_group):
    dotted = re.compile(re.escape(old_group) + r"(?![A-Za-z0-9_])")
    old_path = old_group.replace(".", "/")
    new_path = new_group.replace(".", "/")
    slashed = re.compile(re.escape(old_path) + r"(?![A-Za-z0-9_])")
    text = dotted.sub(new_group, text)
    text = slashed.sub(new_path, text)
    return text


def display_path(path, moves):
    for source, dest in moves:
        source_rel = rel(source)
        dest_rel = rel(dest)
        prefix = source_rel + "/"
        if path == source_rel or path.startswith(prefix):
            return dest_rel + path[len(source_rel):]
    return path


def remember(changed, path):
    item = rel(path) if isinstance(path, Path) else path
    if item not in changed:
        changed.append(item)


def write_if_changed(path, new_text, changed):
    old_text = path.read_text(encoding="utf-8")
    if old_text != new_text:
        path.write_text(new_text, encoding="utf-8")
        remember(changed, path)


def cmd_proj_name(args):
    if not args:
        print("proj_name 需要專案名稱。", file=sys.stderr)
        print('例如：./configure.sh proj_name "Stock Viewer"', file=sys.stderr)
        print(file=sys.stderr)
        print_help()
        raise SystemExit(1)

    name = " ".join(args).strip()
    if not name:
        die("專案名稱不可為空白。")
    if FORBIDDEN_RE.search(name) or any(ord(ch) < 32 for ch in name):
        die('專案名稱不可包含 " \\ $ # = 或換行。')

    props_text, values = read_props()
    old_display = values["app.displayName"]
    old_root = values["app.rootName"]
    old_group = values["app.group"]

    technical = derive_technical(name)
    package_note = technical is None
    if technical is None:
        new_root = old_root
        new_group = old_group
    else:
        new_root, segment = technical
        new_group = new_group_for(old_group, segment)

    marker_path, marker_current = find_marker()
    title_path, title_current, _title_text = find_title()
    moves = planned_moves(old_group, new_group)

    if (
        name == old_display == marker_current == title_current
        and new_root == old_root
        and new_group == old_group
    ):
        print(f"已經是這個名稱：{name}")
        print(f"  Gradle  {old_root}")
        print(f"  套件    {old_group}")
        return

    changed = []

    if new_group != old_group:
        for path in iter_text_files():
            text = path.read_text(encoding="utf-8")
            updated = replace_group(text, old_group, new_group)
            if updated != text:
                path.write_text(updated, encoding="utf-8")
                remember(changed, path)
        props_text = (ROOT / PROPS_FILE).read_text(encoding="utf-8")

    props_updated = apply_props(
        props_text,
        {
            "app.displayName": name,
            "app.rootName": new_root,
            "app.group": new_group,
        },
    )
    write_if_changed(ROOT / PROPS_FILE, props_updated, changed)

    marker_text = marker_path.read_text(encoding="utf-8")
    write_if_changed(marker_path, apply_marker(marker_text, name), changed)

    title_text = title_path.read_text(encoding="utf-8")
    write_if_changed(title_path, apply_title(title_text, name), changed)

    if old_display != name:
        for path in iter_text_files():
            if path.suffix != ".md":
                continue
            text = path.read_text(encoding="utf-8")
            updated = text.replace(old_display, name)
            if updated != text:
                path.write_text(updated, encoding="utf-8")
                remember(changed, path)

    moved = []
    for source, dest in moves:
        dest.parent.mkdir(parents=True, exist_ok=True)
        source.rename(dest)
        moved.append(f"{rel(source)} -> {rel(dest)}")

    print("已更新專案名稱。")
    print(f"  顯示名稱  {name}")
    print(f"  Gradle    {new_root}")
    print(f"  套件      {new_group}")
    if package_note:
        print("這個名稱沒有英數內容，無法產生 Gradle 專案名與套件名，因此這兩項維持不變。")
        print("若要一併改套件，請使用含英文的名稱，例如「Stock Viewer」。")
    if changed:
        print("已修改：")
        for path in changed:
            print(f"  {display_path(path, moves)}")
    if moved:
        print("已移動：")
        for item in moved:
            print(f"  {item}")


def version_marker_values(text):
    found = []
    for line in text.splitlines():
        if VERSION_MARKER not in line:
            continue
        if not VERSION_MARKER_RE.match(line):
            die("找到版號標記，但該行格式無法更新：\n" + line)
        value = re.match(
            r'\s*const val NAME:\s*String\s*=\s*"((?:\\.|[^"\\])*)"',
            line,
        )
        if not value:
            die("找不到 NAME 字串：" + line)
        found.append(value.group(1))
    return found


def find_version_marker():
    found = []
    for path in iter_text_files():
        if path.suffix != ".kt":
            continue
        text = path.read_text(encoding="utf-8")
        for value in version_marker_values(text):
            found.append((path, value))
    if len(found) != 1:
        die(f"版號的 configure 標記必須正好一處，目前有 {len(found)} 處。")
    return found[0]


def apply_version_marker(text, version):
    lines = text.splitlines(keepends=True)
    count = 0
    out = []
    for line in lines:
        ending = ""
        body = line
        for suffix in ("\r\n", "\n", "\r"):
            if body.endswith(suffix):
                body, ending = body[: -len(suffix)], suffix
                break
        if VERSION_MARKER in body:
            match = VERSION_MARKER_RE.match(body)
            if not match:
                die("找到版號標記，但該行格式無法更新：\n" + body)
            body = f"{match.group(1)}{version}{match.group(2)}"
            count += 1
        out.append(body + ending)
    if count != 1:
        die(f"版號標記更新次數是 {count}，預期 1。")
    return "".join(out)


def cmd_version(args):
    if len(args) != 1:
        print("version 需要一個版號。", file=sys.stderr)
        print("例如：./configure.sh version 0.2", file=sys.stderr)
        print(file=sys.stderr)
        print_help()
        raise SystemExit(1)

    version = args[0].strip()
    if not VERSION_RE.fullmatch(version):
        die("版號須為 MAJOR、MAJOR.MINOR 或 MAJOR.MINOR.PATCH，例如 0.2 或 0.2.0。")

    props_text, values = read_props()
    old_version = values["app.version"]
    marker_path, marker_current = find_version_marker()

    if version == old_version == marker_current:
        print(f"已經是這個版本：{version}")
        return

    changed = []
    props_updated = apply_props(props_text, {"app.version": version})
    write_if_changed(ROOT / PROPS_FILE, props_updated, changed)

    marker_text = marker_path.read_text(encoding="utf-8")
    write_if_changed(marker_path, apply_version_marker(marker_text, version), changed)

    print("已更新版號。")
    print(f"  版本  {version}")
    if changed:
        print("已修改：")
        for path in changed:
            print(f"  {path}")


def main():
    args = sys.argv[1:]
    command = args[0] if args else ""
    if command in HELP_COMMANDS:
        print_help()
        return
    if command == "proj_name":
        cmd_proj_name(args[1:])
        return
    if command == "version":
        cmd_version(args[1:])
        return
    print(f"未知的參數：{command}", file=sys.stderr)
    print(file=sys.stderr)
    print_help()
    raise SystemExit(1)


if __name__ == "__main__":
    main()
PY

"""APK editor for Launcher Studio: rename an app, replace its icon(s) and any image inside it.

Uses apktool (decode/rebuild), then zipalign + apksigner with the studio's debug key, so the result installs
over the previous build only when it was signed with the same key. Works on *any* APK, not just our launcher —
but the safe, useful scope is a rename plus image swaps; layout edits are out of scope.
"""
from __future__ import annotations

import json
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

import builder

STUDIO = builder.STUDIO
TOOLS = STUDIO / "tools"
APKTOOL = TOOLS / "apktool.jar"
EDITED = STUDIO / "edited"


class EditorError(RuntimeError):
    pass


# ---------------------------------------------------------------- tools

def _sdk_build_tools(settings: dict) -> Path:
    root = Path(settings.get("android_sdk", "")) / "build-tools"
    versions = sorted([p for p in root.iterdir() if p.is_dir()], key=lambda p: p.name) if root.is_dir() else []
    if not versions:
        raise EditorError("Не найден build-tools в Android SDK: " + str(root))
    return versions[-1]


def _java(settings: dict) -> str:
    jdk = Path(settings.get("jdk", ""))
    for name in ("java.exe", "java"):
        candidate = jdk / "bin" / name
        if candidate.exists():
            return str(candidate)
    raise EditorError("Не найден java в JDK: " + str(jdk))


def ensure_apktool(log=print) -> Path:
    if APKTOOL.exists() and APKTOOL.stat().st_size > 1_000_000:
        return APKTOOL
    TOOLS.mkdir(parents=True, exist_ok=True)
    log("Скачиваю apktool (один раз, ~15 МБ)…")
    request = urllib.request.Request(
        "https://api.github.com/repos/iBotPeaches/Apktool/releases/latest",
        headers={"User-Agent": "launcher-studio", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.load(response)
    jar = next((a for a in data.get("assets", []) if a["name"].endswith(".jar")), None)
    if not jar:
        raise EditorError("У apktool нет .jar в последнем релизе")
    log("Качаю " + jar["name"])
    with urllib.request.urlopen(jar["browser_download_url"], timeout=600) as response, \
            open(APKTOOL, "wb") as out:
        shutil.copyfileobj(response, out)
    return APKTOOL


def _run(cmd: list[str], log, cwd=None, timeout=900, env=None) -> None:
    log("$ " + " ".join(str(c) for c in cmd))
    process = subprocess.Popen([str(c) for c in cmd], cwd=str(cwd) if cwd else None, env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace")
    assert process.stdout is not None
    for line in process.stdout:
        line = line.rstrip()
        if line:
            log(line)
    code = process.wait(timeout=timeout)
    if code != 0:
        raise EditorError("Команда завершилась с ошибкой (код %d)" % code)


# ---------------------------------------------------------------- aapt info

_ICON_RE = re.compile(r"application-icon(?:-(\d+))?:'([^']+)'")
_ROLE_RE = re.compile(r"application:(?: label='([^']*)')?")


def ensure_tools(settings: dict, log=print) -> dict:
    """Returns paths to aapt/apksigner/zipalign and makes sure they exist."""
    if not (APKTOOL.exists() and APKTOOL.stat().st_size > 1_000_000):
        ensure_apktool(log)
    build_tools = _sdk_build_tools(settings)
    aapt = build_tools / "aapt.exe"
    if not aapt.exists():
        aapt = build_tools / "aapt"
    zipalign = build_tools / "zipalign.exe"
    if not zipalign.exists():
        zipalign = build_tools / "zipalign"
    apksigner = build_tools / "lib" / "apksigner.jar"
    for name, path in (("aapt", aapt), ("zipalign", zipalign), ("apksigner.jar", apksigner)):
        if not path.exists():
            raise EditorError(f"Не найден {name}: {path}")
    return {"aapt": aapt, "zipalign": zipalign, "apksigner": apksigner,
            "java": _java(settings), "build_tools": build_tools}


def read_info(apk: Path, settings: dict) -> dict:
    tools = ensure_tools(settings)
    result = subprocess.run([str(tools["aapt"]), "dump", "badging", str(apk)],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise EditorError("aapt не смог прочитать APK:\n" + (result.stdout + result.stderr)[:2000])
    text = result.stdout
    package = re.search(r"package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text)
    label = re.search(r"application-label:'([^']*)'", text)
    icons = [match.group(2) for match in _ICON_RE.finditer(text)]
    if not package:
        raise EditorError("Не удалось разобрать строку package у APK")
    return {
        "package": package.group(1),
        "version_code": package.group(2),
        "version_name": package.group(3),
        "label": label.group(1) if label else package.group(1),
        "icons": list(dict.fromkeys(icons)),
        "apk": str(apk),
    }


def list_images(workdir: Path, limit: int = 40) -> list[dict]:
    """Large images inside the decoded APK: wallpapers, banners, backgrounds."""
    from PIL import Image
    found = []
    res = workdir / "res"
    if not res.is_dir():
        return found
    for path in res.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in (".png", ".jpg", ".jpeg", ".webp"):
            continue
        try:
            with Image.open(path) as image:
                width, height = image.size
        except Exception:
            continue
        if width * height < 640 * 360:
            continue
        rel = path.relative_to(workdir).as_posix()
        found.append({"path": rel, "width": width, "height": height,
                      "pixels": width * height, "bytes": path.stat().st_size})
    found.sort(key=lambda item: item["pixels"], reverse=True)
    return found[:limit]


# ---------------------------------------------------------------- editing

def decode(apk: Path, settings: dict, log=print) -> tuple[Path, dict]:
    """Decodes the APK into edited/<name>/decoded and returns (workdir, info)."""
    apk = Path(apk)
    if not apk.exists():
        raise EditorError("APK не найден: " + str(apk))
    workdir = EDITED / builder.slugify(apk.stem) / "decoded"
    if workdir.exists():
        shutil.rmtree(workdir)
    workdir.parent.mkdir(parents=True, exist_ok=True)
    log(f"=== Разбираю {apk.name} ({apk.stat().st_size / 1048576:.1f} МБ)")
    _run([_java(settings), "-jar", APKTOOL, "d", "-f", "-o", workdir, apk], log)
    set_extract_native_libs(workdir, log)
    info = read_info(apk, settings)
    info["images"] = list_images(workdir)
    info["workdir"] = str(workdir)
    log(f"Готово: {info['package']} «{info['label']}», картинок: {len(info['images'])}")
    return workdir, info


def set_extract_native_libs(workdir: Path, log=print) -> None:
    """apktool re-packs native libraries compressed; a manifest with extractNativeLibs=false then fails to
    install ("Failed to extract native libraries"). The rebuilt APK keeps the libs inside, so asking the
    installer to extract them is the portable fix."""
    manifest = workdir / "AndroidManifest.xml"
    if not manifest.exists():
        return
    text = manifest.read_text(encoding="utf-8", errors="replace")
    if "android:extractNativeLibs" in text:
        new_text = re.sub(r'android:extractNativeLibs="[^"]*"', 'android:extractNativeLibs="true"', text)
    elif "<application" in text:
        new_text = text.replace("<application",
                                '<application android:extractNativeLibs="true"', 1)
    else:
        return
    if new_text != text:
        manifest.write_text(new_text, encoding="utf-8")
        log("Манифест: включено извлечение нативных библиотек (нужно после пересборки)")


def set_label(workdir: Path, old_label: str, new_label: str, log=print) -> int:
    """Sets the app name in every strings.xml (and as a literal manifest label if there is one)."""
    changed = 0
    for strings in (workdir / "res").glob("values*/strings.xml"):
        text = strings.read_text(encoding="utf-8", errors="replace")
        original = text
        text = re.sub(r'(<string name="app_name"[^>]*>)[^<]*(</string>)',
                      lambda m: m.group(1) + _escape(new_label) + m.group(2), text)
        if old_label:
            text = re.sub(r'(<string name="[^"]+"[^>]*>)' + re.escape(_escape(old_label)) + r'(</string>)',
                          lambda m: m.group(1) + _escape(new_label) + m.group(2), text)
        if text != original:
            strings.write_text(text, encoding="utf-8")
            changed += 1
    manifest = workdir / "AndroidManifest.xml"
    if manifest.exists() and old_label:
        text = manifest.read_text(encoding="utf-8", errors="replace")
        new_text = text.replace('android:label="' + _escape(old_label) + '"',
                                'android:label="' + _escape(new_label) + '"')
        if new_text != text:
            manifest.write_text(new_text, encoding="utf-8")
            changed += 1
    log(f"Название: «{old_label}» → «{new_label}» (файлов: {changed})")
    return changed


def _escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "\\'"))


def replace_image(workdir: Path, rel_path: str, source: Path, log=print) -> None:
    """Replaces one decoded image, preserving its size and format (PNG/WEBP/JPEG)."""
    from PIL import Image
    target = workdir / rel_path
    if not target.exists():
        raise EditorError("В APK нет файла " + rel_path)
    if target.name.endswith(".9.png"):
        raise EditorError("Девятипатчевые картинки (.9.png) не поддерживаются")
    with Image.open(target) as image:
        size = image.size
        fmt = image.format or "PNG"
    src = Image.open(source).convert("RGB" if fmt == "JPEG" else "RGBA")
    # cover-crop to the original aspect, then resize to the original pixels
    target_w, target_h = size
    ratio = target_w / target_h
    w, h = src.size
    if w / h > ratio:
        new_w = int(h * ratio)
        src = src.crop(((w - new_w) // 2, 0, (w + new_w) // 2, h))
    else:
        new_h = int(w / ratio)
        src = src.crop((0, (h - new_h) // 2, w, (h + new_h) // 2))
    src = src.resize(size, Image.LANCZOS)
    save_format = {"JPEG": "JPEG", "WEBP": "WEBP"}.get(fmt, "PNG")
    src.convert("RGB").save(target, save_format, quality=92) if save_format == "JPEG" else \
        src.save(target, save_format, quality=92) if save_format == "WEBP" else \
        src.save(target, save_format)
    log(f"Картинка заменена: {rel_path} ({size[0]}×{size[1]})")


def replace_icons(workdir: Path, icon_source: Path, icons: list[str], log=print) -> int:
    done = 0
    for rel in icons:
        target = workdir / rel
        if not target.exists():
            continue
        if target.suffix.lower() == ".xml":
            log("Пропускаю адаптивную иконку " + rel)
            continue
        try:
            replace_image(workdir, rel, icon_source, log=lambda _m: None)
            done += 1
        except Exception as exc:
            log(f"Иконка {rel}: {exc}")
    if done:
        log(f"Иконок заменено: {done}")
    return done


def build(workdir: Path, out_apk: Path, settings: dict, log=print) -> Path:
    tools = ensure_tools(settings)
    unsigned = workdir.parent / "unsigned.apk"
    aligned = workdir.parent / "aligned.apk"
    out_apk = Path(out_apk)
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    log("=== Пересборка APK (apktool)")
    _run([tools["java"], "-jar", APKTOOL, "b", "-f", "-o", unsigned, workdir], log)
    if aligned.exists():
        aligned.unlink()
    log("=== Выравнивание (zipalign)")
    _run([tools["zipalign"], "-f", "4", unsigned, aligned], log)
    if out_apk.exists():
        out_apk.unlink()
    log("=== Подпись (apksigner, ключ студии)")
    _run([tools["java"], "-jar", tools["apksigner"], "sign",
          "--ks", builder.SIGNING / ".android" / "debug.keystore",
          "--ks-pass", "pass:android", "--key-pass", "pass:android",
          "--ks-key-alias", "androiddebugkey", "--out", out_apk, aligned], log)
    log("=== Проверка подписи")
    _run([tools["java"], "-jar", tools["apksigner"], "verify", "--print-certs", out_apk], log)
    log(f"Готово: {out_apk} ({out_apk.stat().st_size / 1048576:.2f} МБ)")
    return out_apk


def install(apk: Path, settings: dict, log=print) -> None:
    log("=== Установка на ТВ")
    result = builder._adb(settings, ["install", "-r", str(apk)], timeout=300)
    text = (result.stdout + result.stderr).strip()
    log(text)
    if result.returncode != 0 or "Success" not in text:
        raise EditorError("adb install не удался:\n" + text)


# ---------------------------------------------------------------- CLI (for tests)

if __name__ == "__main__":
    import argparse
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass
    parser = argparse.ArgumentParser(description="Launcher Studio — APK editor")
    parser.add_argument("--apk", required=True)
    parser.add_argument("--label", default="")
    parser.add_argument("--icon", default="")
    parser.add_argument("--replace", action="append", nargs=2, metavar=("PATH_IN_APK", "IMAGE"), default=[])
    parser.add_argument("--out", required=True)
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    settings = builder.load_settings()
    try:
        workdir, info = decode(Path(args.apk), settings, log=print)
        if args.label:
            set_label(workdir, info["label"], args.label)
        if args.icon:
            replace_icons(workdir, Path(args.icon), info["icons"])
        for rel, image in args.replace:
            replace_image(workdir, rel, Path(image))
        out = build(workdir, Path(args.out), settings)
        if args.install:
            install(out, settings)
    except EditorError as exc:
        print("ОШИБКА:", exc, file=sys.stderr)
        sys.exit(1)

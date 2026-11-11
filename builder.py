"""Launcher Studio engine: validates a project, patches the launcher template, builds an APK and talks to the TV.

The GUI (studio.py) is a thin layer on top of this module, so the same steps work from a command line:

    python builder.py --project projects/demo/project.json --build
"""
from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

def _studio_dir() -> Path:
    """Folders live next to the EXE when frozen by PyInstaller, next to the source otherwise."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


STUDIO = _studio_dir()
TEMPLATE = STUDIO / "template" / "launcher"
SIGNING = STUDIO / "signing"
PROJECTS = STUDIO / "projects"
BUILDS = STUDIO / "build"
SETTINGS_FILE = STUDIO / "settings.json"

APP_ID_RE = re.compile(r"^[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+$")
PKG_RE = re.compile(r"^[A-Za-z0-9_][A-Za-z0-9_.]*$")

# Packages that are handy to start a "Favorites" list with (names are only hints for the UI).
KNOWN_APPS = {
    "com.google.android.youtube.tv": "YouTube",
    "ru.kinopoisk.tv": "Кинопоиск",
    "com.vk.tv": "VK Видео",
    "ru.rutube.app": "RuTube",
    "com.gathin.karaoke": "КАРАОКЕ!!!",
    "com.gathin.karaoke.dvd": "DVD Karaoke",
    "ru.iptvremote.android.iptv": "IPTV",
    "com.gathin.vpngate": "ВПН",
    "com.v2raytun.android": "v2RayTun",
    "st.uboo.android.client": "ЮБуст",
    "ru.poyitochka.tv": "Пой и Точка",
    "com.cxinventor.file.explorer": "Cx Проводник",
    "com.ionitech.airscreen": "AirScreen",
}

DEFAULT_PROJECT = {
    "name": "Мой лаунчер",
    "app_id": "com.gathin.tvhome",
    "version_name": "1.0.0",
    "version_code": 1,
    "default_screen": "universal",
    "wallpaper": "",
    "icon": "",
    "banner": "",
    "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv", "ru.rutube.app"],
    "vpn_confs": [],
    "vpn_source": "builtin",
    "vpn_default_app": "",
    "deepseek_key": "",
    "embed_key": False,
    "sound_theme": 0,
    "volume": 2,
    "screensaver": 5,
    "chip_style": 0,
    "push_vpn": True,
    "device_ip": "192.168.0.39:5555",
}


class StudioError(RuntimeError):
    pass


# ---------------------------------------------------------------- settings and paths

def default_settings() -> dict:
    return {
        "jdk": "D:/Projects/tv/.cytv/jdk/jdk-21.0.13+9",
        "android_sdk": "D:/Projects/tv/.cytv/android-sdk",
        "gradle_home": "D:/Projects/tv/.cytv/gradle-home",
        "adb": "D:/Projects/tv/.cytv/tools/android/sdk/platform-tools/adb.exe",
        "device_ip": "192.168.0.39:5555",
    }


def load_settings() -> dict:
    data = default_settings()
    if SETTINGS_FILE.exists():
        try:
            data.update(json.loads(SETTINGS_FILE.read_text(encoding="utf-8")))
        except Exception:
            pass
    return data


def save_settings(settings: dict) -> None:
    SETTINGS_FILE.write_text(json.dumps(settings, ensure_ascii=False, indent=2), encoding="utf-8")


_TRANSLIT = str.maketrans({
    "а": "a", "б": "b", "в": "v", "г": "g", "д": "d", "е": "e", "ё": "e", "ж": "zh",
    "з": "z", "и": "i", "й": "y", "к": "k", "л": "l", "м": "m", "н": "n", "о": "o",
    "п": "p", "р": "r", "с": "s", "т": "t", "у": "u", "ф": "f", "х": "h", "ц": "ts",
    "ч": "ch", "ш": "sh", "щ": "sch", "ъ": "", "ы": "y", "ь": "", "э": "e", "ю": "yu",
    "я": "ya",
})


def slugify(name: str) -> str:
    text = (name or "").lower().translate(_TRANSLIT)
    slug = re.sub(r"[^a-z0-9]+", "-", text).strip("-")
    return slug[:48].strip("-") or "launcher"


def project_dir(project: dict) -> Path:
    return PROJECTS / slugify(project.get("name") or "launcher")


def new_project() -> dict:
    return json.loads(json.dumps(DEFAULT_PROJECT, ensure_ascii=False))


def save_project(project: dict) -> Path:
    folder = project_dir(project)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "project.json").write_text(json.dumps(project, ensure_ascii=False, indent=2), encoding="utf-8")
    return folder


def load_project_file(path: Path) -> dict:
    data = new_project()
    data.update(json.loads(Path(path).read_text(encoding="utf-8")))
    return data


def list_projects() -> list[Path]:
    if not PROJECTS.exists():
        return []
    return sorted(PROJECTS.glob("*/project.json"))


# ---------------------------------------------------------------- validation

def validate(project: dict) -> list[str]:
    errors = []
    name = (project.get("name") or "").strip()
    if not name:
        errors.append("Название лаунчера пустое")
    if len(name) > 40:
        errors.append("Название длиннее 40 символов")
    if not APP_ID_RE.match(project.get("app_id", "")):
        errors.append("applicationId должен быть вида com.example.launcher")
    if not str(project.get("version_code", "")).isdigit():
        errors.append("versionCode должен быть числом")
    if not (project.get("version_name") or "").strip():
        errors.append("versionName пустой")
    if project.get("default_screen") not in ("cinema", "classic", "universal", "polly", "wintv"):
        errors.append("Неизвестный экран по умолчанию")
    wallpaper = project.get("wallpaper") or ""
    if wallpaper:
        path = (project_dir(project) / wallpaper) if not os.path.isabs(wallpaper) else Path(wallpaper)
        if not path.exists():
            errors.append("Файл обоев не найден: " + str(path))
    for key in ("icon", "banner"):
        value = project.get(key) or ""
        if value:
            path = (project_dir(project) / value) if not os.path.isabs(value) else Path(value)
            if not path.exists():
                errors.append(f"Файл {key} не найден: {path}")
    for pkg in project.get("favorites", []):
        if not PKG_RE.match(pkg):
            errors.append("Некорректный пакет в избранном: " + pkg)
    for conf in project.get("vpn_confs", []):
        path = (project_dir(project) / conf) if not os.path.isabs(conf) else Path(conf)
        if not path.exists():
            errors.append("VPN-профиль не найден: " + str(path))
    if project.get("embed_key") and not (project.get("deepseek_key") or "").strip():
        errors.append("Включено «вшить ключ DeepSeek», но ключ пустой")
    return errors


def ensure_signing(settings: dict, log=print) -> Path:
    """The debug keystore is created on first use, so a fresh copy of the studio can sign APKs itself."""
    keystore = SIGNING / ".android" / "debug.keystore"
    if keystore.exists():
        return keystore
    jdk = Path(settings.get("jdk", ""))
    keytool = jdk / "bin" / ("keytool.exe" if (jdk / "bin" / "keytool.exe").exists() else "keytool")
    if not keytool.exists():
        raise StudioError("Не найден keytool в JDK: " + str(jdk))
    keystore.parent.mkdir(parents=True, exist_ok=True)
    log("Создаю ключ подписи (один раз): " + str(keystore))
    subprocess.run([str(keytool), "-genkeypair", "-keystore", str(keystore),
                    "-alias", "androiddebugkey", "-storepass", "android", "-keypass", "android",
                    "-dname", "CN=Android Debug,O=Android,C=US", "-validity", "10000",
                    "-keyalg", "RSA", "-keysize", "2048"],
                   check=True, capture_output=True)
    return keystore


def check_tools(settings: dict) -> list[str]:
    problems = []
    jdk = Path(settings.get("jdk", ""))
    if not (jdk / "bin" / "java.exe").exists() and not (jdk / "bin" / "java").exists():
        problems.append(f"JDK не найден: {jdk}")
    sdk = Path(settings.get("android_sdk", ""))
    if not (sdk / "platforms" / "android-34" / "android.jar").exists():
        problems.append(f"Android SDK 34 не найден: {sdk}")
    if not (TEMPLATE / "gradle" / "wrapper" / "gradle-wrapper.jar").exists():
        problems.append(f"Шаблон лаунчера не найден: {TEMPLATE}")
    try:
        ensure_signing(settings, log=lambda _m: None)
    except Exception as exc:
        problems.append("Не удалось подготовить ключ подписи: " + str(exc))
    return problems


# ---------------------------------------------------------------- image helpers

def _pil():
    try:
        from PIL import Image
        return Image
    except Exception as exc:  # pragma: no cover - depends on the Python used to launch the studio
        raise StudioError(
            "Pillow не установлен. Запустите студию через run-studio.bat (он берёт Python со встроенными "
            "библиотеками) или выполните: pip install pillow"
        ) from exc


def fit_image(src: Path, dst: Path, width: int, height: int) -> None:
    Image = _pil()
    image = Image.open(src).convert("RGB")
    target = width / height
    w, h = image.size
    if w / h > target:
        new_w = int(h * target)
        left = (w - new_w) // 2
        image = image.crop((left, 0, left + new_w, h))
    else:
        new_h = int(w / target)
        top = (h - new_h) // 2
        image = image.crop((0, top, w, top + new_h))
    image = image.resize((width, height), Image.LANCZOS)
    dst.parent.mkdir(parents=True, exist_ok=True)
    image.save(dst, "JPEG", quality=90)


# ---------------------------------------------------------------- template patching and build

def _xml_escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "\\'"))


def _resolve(project: dict, value: str) -> Path:
    return (project_dir(project) / value) if not os.path.isabs(value) else Path(value)


def prepare_build(project: dict, build_dir: Path) -> dict:
    # Keep the previous build dir so Gradle's incremental caches survive: the first build takes a few
    # minutes, later ones usually finish in under a minute.
    if build_dir.exists():
        shutil.copytree(TEMPLATE, build_dir, dirs_exist_ok=True)
    else:
        build_dir.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(TEMPLATE, build_dir)
    app = build_dir / "app"

    # app label (used by the TV's app list and by the launcher window)
    strings = app / "src" / "main" / "res" / "values" / "strings.xml"
    s = strings.read_text(encoding="utf-8")
    label = _xml_escape(project["name"].strip())
    s = re.sub(r'<string name="app_name">[^<]*</string>',
               f'<string name="app_name">{label}</string>', s, count=1)
    s = re.sub(r'<string name="uni_app_name">[^<]*</string>',
               f'<string name="uni_app_name">{label}</string>', s, count=1)
    strings.write_text(s, encoding="utf-8")

    # version
    gradle = app / "build.gradle"
    s = gradle.read_text(encoding="utf-8")
    s = re.sub(r"versionCode \d+", f"versionCode {int(project['version_code'])}", s, count=1)
    s = re.sub(r"versionName '[^']*'", f"versionName '{project['version_name'].strip()}'", s, count=1)
    gradle.write_text(s, encoding="utf-8")

    # wallpaper (the universal screen's default) and TV icon/banner
    res = app / "src" / "main" / "res"
    if project.get("wallpaper"):
        fit_image(_resolve(project, project["wallpaper"]), res / "drawable-nodpi" / "wp_lake.jpg", 1920, 1080)
    if project.get("icon"):
        fit_image(_resolve(project, project["icon"]), res / "mipmap-xhdpi" / "ic_launcher.jpg", 192, 192)
    if project.get("banner"):
        fit_image(_resolve(project, project["banner"]), res / "drawable-nodpi" / "banner.jpg", 320, 180)

    # build-time defaults read by StudioDefaults on first run
    defaults = {
        "favorites": [p for p in project.get("favorites", []) if p],
        "defaultVpn": project.get("vpn_default_app") or "",
        "vpnSource": project.get("vpn_source") or "builtin",
        "deepseekKey": project.get("deepseek_key", "").strip() if project.get("embed_key") else "",
        "defaultScreen": project.get("default_screen") or "universal",
        "wallpaper": 0,
        "soundTheme": int(project.get("sound_theme", 0)),
        "volume": int(project.get("volume", 2)),
        "screensaverMinutes": int(project.get("screensaver", 5)),
        "chipStyle": int(project.get("chip_style", 0)),
    }
    assets = app / "src" / "main" / "assets"
    assets.mkdir(parents=True, exist_ok=True)
    (assets / "studio_defaults.json").write_text(json.dumps(defaults, ensure_ascii=False, indent=2), encoding="utf-8")

    (build_dir / "local.properties").write_text(
        "sdk.dir=" + str(Path(project["_sdk"]).as_posix()) + "\n", encoding="utf-8")
    return defaults


def build(project: dict, settings: dict, log=print) -> dict:
    ensure_signing(settings, log)
    problems = validate(project) + check_tools(settings)
    if problems:
        raise StudioError("\n".join(problems))

    print_project = project.get("name", "?")
    log(f"=== Сборка «{print_project}» ({project['app_id']}, v{project['version_name']})")
    build_dir = BUILDS / slugify(project.get("name", "launcher"))
    work = dict(project)
    work["_sdk"] = settings["android_sdk"]
    defaults = prepare_build(work, build_dir)
    log("Шаблон подготовлен: название, версия, обои, иконка, настройки по умолчанию")

    jdk = Path(settings["jdk"])
    env = dict(os.environ)
    env["JAVA_HOME"] = str(jdk)
    env["ANDROID_HOME"] = settings["android_sdk"]
    env["ANDROID_SDK_ROOT"] = settings["android_sdk"]
    env["GRADLE_USER_HOME"] = settings["gradle_home"]
    (build_dir / "tmp").mkdir(exist_ok=True)
    env["JAVA_TOOL_OPTIONS"] = (
        f"-Duser.home={SIGNING.as_posix()} -Djava.io.tmpdir={(build_dir / 'tmp').as_posix()}")
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")

    java = jdk / "bin" / "java.exe"
    if not java.exists():
        java = jdk / "bin" / "java"
    cmd = [
        str(java), "-cp", str(build_dir / "gradle" / "wrapper" / "gradle-wrapper.jar"),
        "org.gradle.wrapper.GradleWrapperMain",
        "assembleRelease", "--no-daemon", "--console=plain", "--offline",
        f"-PlauncherApplicationId={project['app_id']}",
    ]
    log("Gradle: assembleRelease (offline, ~1–3 минуты)")
    started = time.time()
    tail: list[str] = []
    proc = subprocess.Popen(cmd, cwd=str(build_dir), env=env, stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace")
    assert proc.stdout is not None
    for line in proc.stdout:
        line = line.rstrip()
        if line:
            tail.append(line)
            if len(tail) > 120:
                tail.pop(0)
            log(line)
    code = proc.wait(timeout=1800)
    if code != 0:
        raise StudioError("Gradle завершился с ошибкой. Хвост лога:\n" + "\n".join(tail[-40:]))
    apk = build_dir / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
    if not apk.exists():
        raise StudioError("APK не появился: " + str(apk))

    out_dir = project_dir(project) / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"{slugify(project['name'])}-v{project['version_name']}.apk"
    shutil.copy2(apk, out)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    size = out.stat().st_size
    log(f"Готово за {time.time() - started:.0f} с: {out} ({size / 1048576:.2f} МБ)")
    log(f"SHA-256: {digest}")
    return {"apk": str(out), "size": size, "sha256": digest, "defaults": defaults,
            "app_id": project["app_id"], "version": project["version_name"]}


# ---------------------------------------------------------------- ADB

def _adb(settings: dict, args: list[str], timeout: int = 180) -> subprocess.CompletedProcess:
    adb = settings.get("adb")
    if not adb or not Path(adb).exists():
        raise StudioError("adb не найден: " + str(adb))
    return subprocess.run([adb] + args, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)


def adb_devices(settings: dict) -> list[str]:
    result = _adb(settings, ["devices"])
    devices = []
    for line in result.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])
    return devices


def adb_connect(settings: dict, ip: str) -> str:
    if not ip:
        raise StudioError("Не указан IP телевизора")
    result = _adb(settings, ["connect", ip], timeout=40)
    return (result.stdout + result.stderr).strip()


def adb_packages(settings: dict) -> list[str]:
    result = _adb(settings, ["shell", "pm", "list", "packages", "-3"], timeout=60)
    if result.returncode != 0:
        raise StudioError("Не удалось прочитать приложения с ТВ: " + (result.stderr or result.stdout))
    return sorted(line.split("package:", 1)[1].strip()
                  for line in result.stdout.splitlines() if line.startswith("package:"))


def friendly(pkg: str) -> str:
    return KNOWN_APPS.get(pkg, pkg.rsplit(".", 1)[-1].replace("_", " ").capitalize())


def adb_install(settings: dict, project: dict, apk: str, log=print, push_vpn=True) -> None:
    log("=== Установка APK на ТВ")
    result = _adb(settings, ["install", "-r", apk], timeout=300)
    text = (result.stdout + result.stderr).strip()
    log(text)
    if result.returncode != 0 or "Success" not in text:
        raise StudioError("adb install не удался:\n" + text)
    app_id = project["app_id"]
    try:
        _adb(settings, ["shell", "appops", "set", app_id, "ACTIVATE_VPN", "allow"], timeout=40)
        log("Разрешение ACTIVATE_VPN выдано (VPN сможет включаться без диалога)")
    except Exception as exc:
        log("Не удалось выдать ACTIVATE_VPN: " + str(exc))
    if push_vpn and project.get("push_vpn") and project.get("vpn_confs"):
        target = f"/sdcard/Android/data/{app_id}/files/vpn"
        _adb(settings, ["shell", "mkdir", "-p", target], timeout=40)
        for conf in project["vpn_confs"]:
            path = _resolve(project, conf)
            result = _adb(settings, ["push", str(path), target + "/" + path.name], timeout=120)
            log(f"VPN-профиль {path.name}: " + (result.stdout + result.stderr).strip())
    log("Готово. Запускаю лаунчер…")
    _adb(settings, ["shell", "am", "start", "-n", f"{app_id}/com.gathin.tvhome.MainActivity"], timeout=40)
    try:
        _adb(settings, ["shell", "input", "keyevent", "KEYCODE_HOME"], timeout=20)
    except Exception:
        pass


def run_cli() -> int:
    import argparse
    parser = argparse.ArgumentParser(description="Launcher Studio builder")
    parser.add_argument("--project", required=True)
    parser.add_argument("--build", action="store_true")
    parser.add_argument("--install", action="store_true")
    parser.add_argument("--settings", default="")
    args = parser.parse_args()
    settings = load_settings()
    if args.settings:
        settings.update(json.loads(Path(args.settings).read_text(encoding="utf-8")))
    project = load_project_file(Path(args.project))
    try:
        result = None
        if args.build:
            result = build(project, settings, log=print)
        if args.install:
            if result is None:
                newest = sorted(project_dir(project).glob("out/*.apk"), key=lambda p: p.stat().st_mtime)
                if not newest:
                    raise StudioError("Нет собранного APK")
                apk = str(newest[-1])
            else:
                apk = result["apk"]
            adb_install(settings, project, apk, log=print)
        return 0
    except StudioError as exc:
        print("ОШИБКА:", exc, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(run_cli())

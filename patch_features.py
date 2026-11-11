"""Patches Launcher Studio for presets, the APK editor tab and self-contained EXE paths."""
import re

# ---------------------------------------------------------------- builder.py
p = "builder.py"
s = open(p, encoding="utf-8").read()

s = s.replace("STUDIO = Path(__file__).resolve().parent", '''def _studio_dir() -> Path:
    """Folders live next to the EXE when frozen by PyInstaller, next to the source otherwise."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent
    return Path(__file__).resolve().parent


STUDIO = _studio_dir()''')

if "def ensure_signing(" not in s:
    s = s.replace('''def check_tools(settings: dict) -> list[str]:''', '''def ensure_signing(settings: dict, log=print) -> Path:
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


def check_tools(settings: dict) -> list[str]:''')

s = s.replace('''    if not (SIGNING / ".android" / "debug.keystore").exists():
        problems.append("Нет ключа подписи: signing/.android/debug.keystore")''',
'''    try:
        ensure_signing(settings, log=lambda _m: None)
    except Exception as exc:
        problems.append("Не удалось подготовить ключ подписи: " + str(exc))''')

s = s.replace('''def build(project: dict, settings: dict, log=print) -> dict:
    problems = validate(project) + check_tools(settings)''',
'''def build(project: dict, settings: dict, log=print) -> dict:
    ensure_signing(settings, log)
    problems = validate(project) + check_tools(settings)''')

open(p, "w", encoding="utf-8").write(s)
print("builder patched")

# ---------------------------------------------------------------- editor.py: simplify java/settings plumbing
p = "editor.py"
s = open(p, encoding="utf-8").read()
s = s.replace('''    log(f"=== Разбираю {apk.name} ({apk.stat().st_size / 1048576:.1f} МБ)")
    _run([_java_path(), "-jar", APKTOOL, "d", "-f", "-o", workdir, apk], log)
    info = _read_info_after_decode(workdir, apk, log)''',
'''    log(f"=== Разбираю {apk.name} ({apk.stat().st_size / 1048576:.1f} МБ)")
    _run([_java(settings), "-jar", APKTOOL, "d", "-f", "-o", workdir, apk], log)
    info = read_info(apk, settings)''')
s = s.replace('''def decode(apk: Path, log=print) -> tuple[Path, dict]:''',
              '''def decode(apk: Path, settings: dict, log=print) -> tuple[Path, dict]:''')
# remove old java cache helpers and the duplicated info parser
start = s.find("_JAVA_CACHE: list[str] = []")
end = s.find("def set_label(", start)
if start != -1 and end != -1:
    s = s[:start] + s[end:]
open(p, "w", encoding="utf-8").write(s)
print("editor simplified")

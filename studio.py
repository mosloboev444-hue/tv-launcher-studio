"""Launcher Studio — a Windows desktop builder for TV launchers based on CleanYourTV (three screens + VPN).

Run with run-studio.bat. The heavy lifting lives in builder.py; this file is the window.
"""
from __future__ import annotations

import json
import os
import queue
import threading
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, simpledialog, ttk

import builder
import editor
import presets

SCREENS = [("Универсальный", "universal"), ("Кино", "cinema"), ("Классика", "classic"),
           ("PollyTV", "polly"), ("WinTV", "wintv")]
SOUNDS = ["Колокольчики", "Мягкие", "Кристалл"]
VOLUMES = ["Выкл", "Тихо", "Средне", "Громко"]
SAVERS = [(0, "Выкл"), (2, "2 минуты"), (5, "5 минут"), (10, "10 минут")]
CHIPS = [("Стекло (плашка)", 0), ("Без фона (тень)", 1)]


def _theme(root: tk.Tk) -> None:
    style = ttk.Style(root)
    try:
        style.theme_use("clam")
    except tk.TclError:
        pass
    style.configure(".", font=("Segoe UI", 10))
    style.configure("TButton", padding=(10, 5))
    style.configure("Accent.TButton", font=("Segoe UI Semibold", 10), padding=(12, 6))
    style.configure("TNotebook.Tab", padding=(14, 7))
    style.configure("Hint.TLabel", foreground="#666666")


class Studio(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.title("Launcher Studio — конструктор лаунчеров для Android TV")
        self.geometry("1360x900")
        self.minsize(1180, 760)

        self.settings = builder.load_settings()
        self.project: dict = builder.new_project()
        self.queue: queue.Queue = queue.Queue()
        self.busy = False
        self._preview_photo = None
        self._preview_cache = None

        _theme(self)
        self.vars: dict[str, tk.Variable] = {}
        self._build_layout()
        self._create_vars()
        try:
            self.state("zoomed")
        except tk.TclError:
            pass
        # First run: create a ready-to-edit project so the window is not empty.
        if not builder.list_projects():
            folder = builder.save_project(self.project)
            self._current_path = folder / "project.json"
        else:
            first = builder.list_projects()[0]
            self._current_path = first
            self.project = builder.load_project_file(first)
        self._load_project(self.project)
        self._refresh_project_list()
        self.after(120, self._poll_queue)
        self.protocol("WM_DELETE_WINDOW", self._on_close)

    # ------------------------------------------------------------ layout

    def _build_layout(self) -> None:
        self.columnconfigure(1, weight=1)
        self.rowconfigure(0, weight=1)

        left = ttk.Frame(self, padding=(12, 12, 6, 8))
        left.grid(row=0, column=0, sticky="nsw")
        ttk.Label(left, text="Проекты", font=("Segoe UI Semibold", 12)).pack(anchor="w")
        ttk.Label(left, text="Каждый проект — свой APK", style="Hint.TLabel").pack(anchor="w", pady=(0, 8))
        self.project_list = tk.Listbox(left, width=24, height=18, activestyle="dotbox",
                                       exportselection=False, font=("Segoe UI", 10))
        self.project_list.pack(fill="both", expand=True)
        self.project_list.bind("<<ListboxSelect>>", lambda _e: self._on_project_selected())
        btns = ttk.Frame(left)
        btns.pack(fill="x", pady=(8, 0))
        ttk.Button(btns, text="Новый", command=self._new_project).grid(row=0, column=0, sticky="ew", padx=2)
        ttk.Button(btns, text="Сохранить", command=self._save_project).grid(row=0, column=1, sticky="ew", padx=2)
        btns.columnconfigure(0, weight=1)
        btns.columnconfigure(1, weight=1)
        ttk.Button(left, text="Открыть папку проекта", command=self._open_folder).pack(fill="x", pady=(6, 0))

        right = ttk.Frame(self, padding=(6, 12, 12, 8))
        right.grid(row=0, column=1, sticky="nsew")
        right.columnconfigure(0, weight=1)
        right.rowconfigure(0, weight=1)
        self.notebook = ttk.Notebook(right)
        self.notebook.grid(row=0, column=0, sticky="nsew")
        self.notebook.bind("<<NotebookTabChanged>>", self._on_tab_changed)

        self.tab_main = self._tab("01 Основное", self._make_main_tab)
        self.tab_fav = self._tab("02 Избранное", self._make_favorites_tab)
        self.tab_vpn = self._tab("03 VPN", self._make_vpn_tab)
        self.tab_assistant = self._tab("04 Помощник", self._make_assistant_tab)
        self.tab_look = self._tab("05 Звук и вид", self._make_look_tab)
        self.tab_build = self._tab("06 Сборка и ТВ", self._make_build_tab)
        self.tab_editor = self._tab("07 Редактор APK", self._make_editor_tab)

        preview = ttk.LabelFrame(right, text="Предпросмотр", padding=8)
        preview.grid(row=1, column=0, sticky="ew", pady=(10, 0))
        self.preview_box = preview
        self.preview = tk.Canvas(preview, height=292, highlightthickness=0, background="#0b1620")
        self.preview.pack(fill="x", expand=True)
        self.preview.bind("<Configure>", lambda _e: self._refresh_preview())

        self.status = tk.StringVar(value="Готово")
        ttk.Label(self, textvariable=self.status, padding=(12, 4), style="Hint.TLabel").grid(
            row=1, column=0, columnspan=2, sticky="ew")

    def _tab(self, title: str, factory) -> ttk.Frame:
        frame = ttk.Frame(self.notebook, padding=14)
        self.notebook.add(frame, text=title)
        factory(frame)
        return frame

    def _row(self, parent, r: int, text: str, widget) -> None:
        ttk.Label(parent, text=text).grid(row=r, column=0, sticky="w", pady=6, padx=(0, 12))
        widget.grid(row=r, column=1, sticky="ew", pady=6)

    def _make_main_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(1, weight=1)
        self.var_title = tk.StringVar()
        self.var_appid = tk.StringVar()
        self.var_version = tk.StringVar()
        self.var_code = tk.StringVar()
        self.var_screen = tk.StringVar()
        self.var_wall = tk.StringVar()
        self.var_icon = tk.StringVar()
        self.var_banner = tk.StringVar()

        self._row(tab, 0, "Название лаунчера", ttk.Entry(tab, textvariable=self.var_title))
        ttk.Label(tab, text="Так подписан APK и приложение в списке передач ТВ", style="Hint.TLabel").grid(
            row=1, column=1, sticky="w")
        self._row(tab, 2, "applicationId", ttk.Entry(tab, textvariable=self.var_appid))
        ttk.Label(tab, text="Оставьте com.gathin.tvhome, чтобы заменять текущий лаунчер. "
                            "Другое значение — отдельное приложение рядом.",
                  style="Hint.TLabel").grid(row=3, column=1, sticky="w")
        vers = ttk.Frame(tab)
        vers.columnconfigure(1, weight=1)
        vers.columnconfigure(3, weight=1)
        ttk.Label(vers, text="Версия").grid(row=0, column=0, padx=(0, 8))
        ttk.Entry(vers, textvariable=self.var_version, width=14).grid(row=0, column=1, sticky="w")
        ttk.Label(vers, text="versionCode").grid(row=0, column=2, padx=(16, 8))
        ttk.Entry(vers, textvariable=self.var_code, width=10).grid(row=0, column=3, sticky="w")
        self._row(tab, 4, "Версия", vers)
        self._row(tab, 5, "Экран по умолчанию",
                  ttk.Combobox(tab, textvariable=self.var_screen, values=[s[0] for s in SCREENS], state="readonly"))

        preset_box = ttk.LabelFrame(tab, text="Пресет оформления", padding=10)
        preset_box.grid(row=6, column=0, columnspan=2, sticky="ew", pady=(14, 0))
        preset_box.columnconfigure(1, weight=1)
        self.var_preset = tk.StringVar(value=presets.names()[0])
        ttk.Combobox(preset_box, textvariable=self.var_preset, values=presets.names(),
                     state="readonly").grid(row=0, column=0, sticky="ew")
        ttk.Button(preset_box, text="Применить пресет", command=self._apply_preset).grid(
            row=0, column=1, sticky="w", padx=8)
        self.preset_hint = tk.StringVar(value="")
        ttk.Label(preset_box, textvariable=self.preset_hint, style="Hint.TLabel",
                  wraplength=760, justify="left").grid(row=1, column=0, columnspan=2, sticky="w", pady=(6, 0))

        files = ttk.LabelFrame(tab, text="Обои и оформление", padding=10)
        files.grid(row=7, column=0, columnspan=2, sticky="ew", pady=(14, 0))
        files.columnconfigure(1, weight=1)
        for row, (label, var, action) in enumerate((
                ("Обои (16:9)", self.var_wall, self._choose_wallpaper),
                ("Иконка (квадрат)", self.var_icon, self._choose_icon),
                ("Баннер для ТВ (16:9)", self.var_banner, self._choose_banner))):
            ttk.Label(files, text=label).grid(row=row, column=0, sticky="w", pady=6)
            ttk.Label(files, textvariable=var, foreground="#555555").grid(row=row, column=1, sticky="w", padx=10)
            ttk.Button(files, text="Выбрать…", command=action).grid(row=row, column=2, sticky="e")
        ttk.Label(files, text="Если не выбрать — останутся стандартные горы и иконка CleanYourTV.",
                  style="Hint.TLabel").grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 0))
        self.preset_hint.set(presets.PRESETS[0]["hint"])

    def _apply_preset(self) -> None:
        preset = presets.resolve(self.var_preset.get())
        if not preset:
            return
        self.var_screen.set(next((name for name, value in SCREENS if value == preset["screen"]), "Универсальный"))
        self.var_sound.set(SOUNDS[preset["sound"] % len(SOUNDS)])
        self.var_volume.set(VOLUMES[preset["volume"] % len(VOLUMES)])
        saver = next(name for minutes, name in SAVERS if minutes == preset["screensaver"])
        self.var_saver.set(saver)
        self.var_chip.set(CHIPS[0][0] if preset["chip"] == 0 else CHIPS[1][0])
        self._fill_list(self.fav_list, preset["favorites"])
        wallpaper = Path(preset["wallpaper"])
        if wallpaper.exists():
            try:
                rel = self._copy_into_project(wallpaper, "assets")
                self.project["wallpaper"] = rel
                self.var_wall.set(self._display_path(rel))
            except Exception as exc:
                self.status.set("Пресет применён без обоев: " + str(exc))
        self._refresh_preview()
        self.preset_hint.set(preset["hint"])
        self.status.set("Применён пресет «" + preset["name"] + "» — не забудьте сохранить проект")

    def _make_favorites_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(1, weight=1)
        ttk.Label(tab, text="Приложения на первом ряду («Избранное») — порядок сверху вниз.").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))
        self.fav_list = tk.Listbox(tab, height=16, exportselection=False, font=("Segoe UI", 10))
        self.fav_list.grid(row=1, column=0, sticky="nsew")
        side = ttk.Frame(tab)
        side.grid(row=1, column=1, sticky="ns", padx=(10, 0))
        ttk.Button(side, text="↑ Выше", command=lambda: self._move_fav(-1)).pack(fill="x", pady=2)
        ttk.Button(side, text="↓ Ниже", command=lambda: self._move_fav(1)).pack(fill="x", pady=2)
        ttk.Button(side, text="Удалить", command=self._remove_fav).pack(fill="x", pady=2)
        ttk.Separator(side, orient="horizontal").pack(fill="x", pady=8)
        ttk.Button(side, text="Взять список с ТВ", command=self._packages_for_favorites).pack(fill="x", pady=2)

        add = ttk.Frame(tab)
        add.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(10, 0))
        add.columnconfigure(0, weight=1)
        self.fav_entry = ttk.Entry(add)
        self.fav_entry.grid(row=0, column=0, sticky="ew")
        self.fav_entry.bind("<Return>", lambda _e: self._add_fav())
        ttk.Button(add, text="Добавить пакет", command=self._add_fav).grid(row=0, column=1, padx=6)
        ttk.Label(tab, text="Пакеты незнакомых приложений можно подсмотреть кнопкой «Взять список с ТВ».",
                  style="Hint.TLabel").grid(row=3, column=0, columnspan=2, sticky="w", pady=(6, 0))

    def _make_vpn_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(0, weight=1)
        tab.rowconfigure(1, weight=1)
        ttk.Label(tab, text="Профили AmneziaWG /.conf, которые попадут в лаунчер и на ТВ.").grid(
            row=0, column=0, columnspan=2, sticky="w", pady=(0, 8))
        self.conf_list = tk.Listbox(tab, height=10, exportselection=False, font=("Segoe UI", 10))
        self.conf_list.grid(row=1, column=0, sticky="nsew")
        side = ttk.Frame(tab)
        side.grid(row=1, column=1, sticky="ns", padx=(10, 0))
        ttk.Button(side, text="Добавить .conf…", command=self._add_confs).pack(fill="x", pady=2)
        ttk.Button(side, text="Убрать", command=self._remove_conf).pack(fill="x", pady=2)

        box = ttk.LabelFrame(tab, text="Источник VPN (что делает кнопка на ТВ)", padding=10)
        box.grid(row=2, column=0, columnspan=2, sticky="ew", pady=(12, 0))
        box.columnconfigure(1, weight=1)
        self.var_vpn_builtin = tk.BooleanVar(value=True)
        self.var_vpn_app = tk.StringVar()
        ttk.Radiobutton(box, text="Мой VPN (AmneziaWG) — встроенный туннель", value=True,
                        variable=self.var_vpn_builtin, command=self._sync_vpn_controls).grid(
            row=0, column=0, columnspan=3, sticky="w", pady=2)
        ttk.Radiobutton(box, text="Другое приложение", value=False,
                        variable=self.var_vpn_builtin, command=self._sync_vpn_controls).grid(
            row=1, column=0, sticky="w", pady=2)
        self.vpn_app_entry = ttk.Entry(box, textvariable=self.var_vpn_app, state="disabled")
        self.vpn_app_entry.grid(row=1, column=1, sticky="ew", padx=8)
        self.vpn_pick_btn = ttk.Button(box, text="Выбрать с ТВ…", command=self._pick_vpn_app, state="disabled")
        self.vpn_pick_btn.grid(row=1, column=2, sticky="e")

        self.var_push_vpn = tk.BooleanVar(value=True)
        ttk.Checkbutton(tab, text="При установке сразу закинуть профили на ТВ (рекомендуется)",
                        variable=self.var_push_vpn).grid(row=3, column=0, columnspan=2, sticky="w", pady=(10, 0))
        ttk.Label(tab, text="Ключи .conf не вшиваются в APK: они копируются в папку приложения на телевизоре.",
                  style="Hint.TLabel").grid(row=4, column=0, columnspan=2, sticky="w", pady=(6, 0))

    def _make_assistant_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(1, weight=1)
        self.var_key = tk.StringVar()
        self.var_embed_key = tk.BooleanVar(value=False)
        ttk.Label(tab, text="Ключ DeepSeek для голосового помощника").grid(row=0, column=0, sticky="w")
        ttk.Entry(tab, textvariable=self.var_key, show="•").grid(row=1, column=0, columnspan=2, sticky="ew", pady=(4, 10))
        ttk.Checkbutton(tab, text="Вшить ключ в APK (небезопасно: ключ окажется внутри файла)",
                        variable=self.var_embed_key).grid(row=2, column=0, columnspan=2, sticky="w")
        ttk.Label(tab, text="Если не вшивать: после установки положите ключ на ТВ командой\n"
                            "  adb shell echo \"sk-…\" > /sdcard/Android/data/<applicationId>/files/deepseek_key.txt\n"
                            "или задайте его в лаунчере: палитра → «DeepSeek: ключ».",
                  style="Hint.TLabel", justify="left").grid(row=3, column=0, columnspan=2, sticky="w", pady=(10, 0))

    def _make_look_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(1, weight=1)
        self.var_sound = tk.StringVar()
        self.var_volume = tk.StringVar()
        self.var_saver = tk.StringVar()
        self.var_chip = tk.StringVar()
        self._row(tab, 0, "Тема звуков", ttk.Combobox(tab, textvariable=self.var_sound, values=SOUNDS, state="readonly"))
        self._row(tab, 1, "Громкость", ttk.Combobox(tab, textvariable=self.var_volume, values=VOLUMES, state="readonly"))
        self._row(tab, 2, "Заставка с часами",
                  ttk.Combobox(tab, textvariable=self.var_saver, values=[s[1] for s in SAVERS], state="readonly"))
        self._row(tab, 3, "Плашка названия (экран «Кино»)",
                  ttk.Combobox(tab, textvariable=self.var_chip, values=[c[0] for c in CHIPS], state="readonly"))
        ttk.Label(tab, text="Значения по умолчанию можно поменять на телевизоре — выбор на ТВ всегда важнее.",
                  style="Hint.TLabel").grid(row=4, column=0, columnspan=2, sticky="w", pady=(12, 0))

    def _make_build_tab(self, tab: ttk.Frame) -> None:
        tab.columnconfigure(1, weight=1)
        tab.rowconfigure(9, weight=1)
        self.path_vars = {}
        rows = [("JDK 21", "jdk", "dir"), ("Android SDK", "android_sdk", "dir"),
                ("Gradle-кэш", "gradle_home", "dir"), ("adb", "adb", "file")]
        for row, (label, key, kind) in enumerate(rows):
            var = tk.StringVar(value=self.settings.get(key, ""))
            self.path_vars[key] = var
            ttk.Label(tab, text=label).grid(row=row, column=0, sticky="w", pady=3, padx=(0, 10))
            ttk.Entry(tab, textvariable=var).grid(row=row, column=1, sticky="ew", pady=3)
            ttk.Button(tab, text="…", width=3,
                       command=lambda k=key, kind=kind: self._browse_path(k, kind)).grid(row=row, column=2, padx=(6, 0))

        ttk.Label(tab, text="IP телевизора").grid(row=4, column=0, sticky="w", pady=(10, 3))
        self.var_device_ip = tk.StringVar(value=self.project.get("device_ip") or self.settings.get("device_ip", ""))
        ttk.Entry(tab, textvariable=self.var_device_ip).grid(row=4, column=1, sticky="ew", pady=(10, 3))

        actions = ttk.Frame(tab)
        actions.grid(row=5, column=0, columnspan=3, sticky="w", pady=(12, 6))
        self.btn_check = ttk.Button(actions, text="Проверить инструменты", command=self._check_tools)
        self.btn_check.pack(side="left", padx=(0, 8))
        self.btn_build = ttk.Button(actions, text="Собрать APK", style="Accent.TButton", command=self._start_build)
        self.btn_build.pack(side="left", padx=(0, 8))
        self.btn_install = ttk.Button(actions, text="Установить на ТВ", command=self._start_install)
        self.btn_install.pack(side="left", padx=(0, 8))
        self.btn_both = ttk.Button(actions, text="Собрать и установить", command=lambda: self._start_build(install=True))
        self.btn_both.pack(side="left")

        self.progress = ttk.Progressbar(tab, mode="indeterminate")
        self.progress.grid(row=6, column=0, columnspan=3, sticky="ew", pady=(4, 8))
        ttk.Label(tab, text="Журнал").grid(row=7, column=0, sticky="w")
        self.log_widget = tk.Text(tab, height=16, wrap="none", font=("Consolas", 9), background="#0f1720",
                                  foreground="#d7e3ee", insertbackground="#d7e3ee")
        self.log_widget.grid(row=8, column=0, columnspan=3, sticky="nsew", pady=(4, 0))
        log_scroll = ttk.Scrollbar(tab, orient="vertical", command=self.log_widget.yview)
        log_scroll.grid(row=8, column=3, sticky="ns")
        self.log_widget.configure(yscrollcommand=log_scroll.set)
        ttk.Label(tab, text="Сборка использует локальные JDK 21 и Android SDK; интернет не нужен.",
                  style="Hint.TLabel").grid(row=9, column=0, columnspan=3, sticky="w", pady=(6, 0))

    # ------------------------------------------------------------ project handling

    def _create_vars(self) -> None:
        pass

    def _load_project(self, project: dict) -> None:
        self.project = project
        self.var_title.set(project.get("name", ""))
        self.var_title.trace_add("write", lambda *_a: self._refresh_preview())
        self.var_appid.set(project.get("app_id", "com.gathin.tvhome"))
        self.var_version.set(str(project.get("version_name", "1.0.0")))
        self.var_code.set(str(project.get("version_code", 1)))
        screen = project.get("default_screen", "universal")
        index = next((i for i, (_, value) in enumerate(SCREENS) if value == screen), 0)
        self.var_screen.set(SCREENS[index][0])
        self.var_wall.set(self._display_path(project.get("wallpaper", "")))
        self.var_icon.set(self._display_path(project.get("icon", "")))
        self.var_banner.set(self._display_path(project.get("banner", "")))
        self._fill_list(self.fav_list, project.get("favorites", []))
        self._fill_list(self.conf_list, [Path(p).name for p in project.get("vpn_confs", [])])
        builtin = (project.get("vpn_source") or "builtin") == "builtin"
        self.var_vpn_builtin.set(builtin)
        self.var_vpn_app.set(project.get("vpn_default_app", ""))
        self._sync_vpn_controls()
        self.var_key.set(project.get("deepseek_key", ""))
        self.var_embed_key.set(bool(project.get("embed_key")))
        self.var_sound.set(SOUNDS[int(project.get("sound_theme", 0)) % len(SOUNDS)])
        self.var_volume.set(VOLUMES[int(project.get("volume", 2)) % len(VOLUMES)])
        saver = int(project.get("screensaver", 5))
        saver_index = next((i for i, (m, _n) in enumerate(SAVERS) if m == saver), 2)
        self.var_saver.set(SAVERS[saver_index][1])
        chip = int(project.get("chip_style", 0))
        self.var_chip.set(CHIPS[0][0] if chip == 0 else CHIPS[1][0])
        self.var_device_ip.set(project.get("device_ip") or self.settings.get("device_ip", ""))
        self.var_push_vpn.set(bool(project.get("push_vpn", True)))
        self._refresh_preview()
        self.title(f"Launcher Studio — {project.get('name') or 'новый проект'}")

    def _display_path(self, value: str) -> str:
        if not value:
            return ""
        path = builder.project_dir(self.project) / value if not os.path.isabs(value) else Path(value)
        return str(path)

    def _collect(self) -> dict:
        project = dict(self.project)
        project["name"] = self.var_title.get().strip() or "Мой лаунчер"
        project["app_id"] = self.var_appid.get().strip()
        project["version_name"] = self.var_version.get().strip()
        try:
            project["version_code"] = int(self.var_code.get().strip())
        except ValueError:
            project["version_code"] = project.get("version_code", 1)
        project["default_screen"] = dict(SCREENS).get(self.var_screen.get(), "universal")
        project["wallpaper"] = self.project.get("wallpaper", "")
        project["icon"] = self.project.get("icon", "")
        project["banner"] = self.project.get("banner", "")
        project["favorites"] = list(self.fav_list.get(0, "end"))
        project["vpn_confs"] = list(self.project.get("vpn_confs", []))
        project["vpn_source"] = "builtin" if self.var_vpn_builtin.get() else (self.var_vpn_app.get().strip() or "builtin")
        project["vpn_default_app"] = "" if self.var_vpn_builtin.get() else self.var_vpn_app.get().strip()
        project["deepseek_key"] = self.var_key.get().strip()
        project["embed_key"] = bool(self.var_embed_key.get())
        project["sound_theme"] = SOUNDS.index(self.var_sound.get()) if self.var_sound.get() in SOUNDS else 0
        project["volume"] = VOLUMES.index(self.var_volume.get()) if self.var_volume.get() in VOLUMES else 2
        saver = next((m for m, name in SAVERS if name == self.var_saver.get()), 5)
        project["screensaver"] = saver
        project["chip_style"] = 0 if self.var_chip.get() == CHIPS[0][0] else 1
        project["push_vpn"] = bool(self.var_push_vpn.get())
        project["device_ip"] = self.var_device_ip.get().strip()
        return project

    def _save_project(self, silent: bool = False) -> bool:
        project = self._collect()
        errors = builder.validate(project)
        if errors:
            messagebox.showerror("Проверьте настройки", "\n".join(errors))
            return False
        old_path = getattr(self, "_current_path", None)
        folder = builder.project_dir(project)
        if old_path is not None and old_path.parent != folder and not folder.exists():
            # A renamed launcher moves to its own project folder instead of leaving a stale copy behind.
            try:
                import shutil
                shutil.move(str(old_path.parent), str(folder))
            except Exception:
                pass
        folder = builder.save_project(project)
        self.project = project
        self._current_path = folder / "project.json"
        self._refresh_project_list(folder.name)
        self._save_settings()
        self.status.set("Сохранено: " + str(folder))
        if not silent:
            self._refresh_preview()
        return True

    def _save_settings(self) -> None:
        for key, var in self.path_vars.items():
            self.settings[key] = var.get().strip()
        self.settings["device_ip"] = self.var_device_ip.get().strip()
        builder.save_settings(self.settings)

    def _refresh_project_list(self, select: str = "") -> None:
        self.project_list.delete(0, "end")
        self._projects = builder.list_projects()
        for path in self._projects:
            try:
                display = json.loads(path.read_text(encoding="utf-8")).get("name") or path.parent.name
            except Exception:
                display = path.parent.name
            self.project_list.insert("end", display)
        if select:
            for index, path in enumerate(self._projects):
                if path.parent.name == select:
                    self.project_list.selection_clear(0, "end")
                    self.project_list.selection_set(index)
                    self.project_list.see(index)
        elif self._projects:
            self.project_list.selection_set(0)

    def _on_project_selected(self) -> None:
        selection = self.project_list.curselection()
        if not selection:
            return
        path = self._projects[selection[0]]
        if getattr(self, "_current_path", None) == path:
            return
        try:
            project = builder.load_project_file(path)
        except Exception as exc:
            messagebox.showerror("Не удалось открыть проект", str(exc))
            return
        self._current_path = path
        self._load_project(project)

    def _new_project(self) -> None:
        name = simpledialog.askstring("Новый проект", "Название лаунчера:", parent=self)
        if not name:
            return
        project = builder.new_project()
        project["name"] = name.strip()
        self.project = project
        folder = builder.save_project(project)
        self._current_path = folder / "project.json"
        self._load_project(project)
        self._refresh_project_list(builder.slugify(project["name"]))
        self.status.set("Создан проект «" + project["name"] + "»")

    def _open_folder(self) -> None:
        folder = builder.project_dir(self._collect())
        folder.mkdir(parents=True, exist_ok=True)
        try:
            os.startfile(str(folder))
        except Exception as exc:
            messagebox.showerror("Не удалось открыть папку", str(exc))

    # ------------------------------------------------------------ file pickers

    def _copy_into_project(self, source: Path, subdir: str) -> str:
        folder = builder.project_dir(self._collect())
        target_dir = folder / subdir
        target_dir.mkdir(parents=True, exist_ok=True)
        target = target_dir / source.name
        if source.resolve() != target.resolve():
            import shutil
            shutil.copy2(source, target)
        return target.relative_to(folder).as_posix()

    def _choose_wallpaper(self) -> None:
        path = filedialog.askopenfilename(title="Обои (16:9)",
                                          filetypes=[("Картинки", "*.jpg *.jpeg *.png *.webp *.bmp")])
        if path:
            try:
                rel = self._copy_into_project(Path(path), "assets")
            except Exception as exc:
                messagebox.showerror("Ошибка", str(exc))
                return
            self.project["wallpaper"] = rel
            self.var_wall.set(self._display_path(rel))
            self._refresh_preview()

    def _choose_icon(self) -> None:
        path = filedialog.askopenfilename(title="Иконка",
                                          filetypes=[("Картинки", "*.jpg *.jpeg *.png *.webp *.bmp")])
        if path:
            rel = self._copy_into_project(Path(path), "assets")
            self.project["icon"] = rel
            self.var_icon.set(self._display_path(rel))

    def _choose_banner(self) -> None:
        path = filedialog.askopenfilename(title="Баннер для ТВ",
                                          filetypes=[("Картинки", "*.jpg *.jpeg *.png *.webp *.bmp")])
        if path:
            rel = self._copy_into_project(Path(path), "assets")
            self.project["banner"] = rel
            self.var_banner.set(self._display_path(rel))

    # ------------------------------------------------------------ favorites

    def _fill_list(self, widget: tk.Listbox, values) -> None:
        widget.delete(0, "end")
        for value in values:
            widget.insert("end", value)

    def _add_fav(self) -> None:
        pkg = self.fav_entry.get().strip()
        if not pkg:
            return
        if not builder.PKG_RE.match(pkg):
            messagebox.showwarning("Пакет", "Похоже, это не package name: " + pkg)
            return
        if pkg not in self.fav_list.get(0, "end"):
            self.fav_list.insert("end", pkg)
        self.fav_entry.delete(0, "end")
        self._refresh_preview()

    def _remove_fav(self) -> None:
        for index in reversed(self.fav_list.curselection()):
            self.fav_list.delete(index)
        self._refresh_preview()

    def _move_fav(self, delta: int) -> None:
        selection = self.fav_list.curselection()
        if not selection:
            return
        index = selection[0]
        target = index + delta
        if not 0 <= target < self.fav_list.size():
            return
        value = self.fav_list.get(index)
        self.fav_list.delete(index)
        self.fav_list.insert(target, value)
        self.fav_list.selection_clear(0, "end")
        self.fav_list.selection_set(target)
        self._refresh_preview()

    def _packages_for_favorites(self) -> None:
        packages = self._ask_for_packages()
        if not packages:
            return
        existing = set(self.fav_list.get(0, "end"))
        for pkg in packages:
            if pkg not in existing:
                self.fav_list.insert("end", pkg)
        self._refresh_preview()

    def _pick_vpn_app(self) -> None:
        packages = self._ask_for_packages()
        if not packages:
            return
        self.var_vpn_app.set(packages[0])

    def _ask_for_packages(self) -> list[str]:
        self._save_settings()
        try:
            builder.adb_connect(self.settings, self.var_device_ip.get().strip())
            packages = builder.adb_packages(self.settings)
        except Exception as exc:
            messagebox.showerror("Телевизор", str(exc))
            return []
        dialog = tk.Toplevel(self)
        dialog.title("Приложения на телевизоре")
        dialog.geometry("520x620")
        ttk.Label(dialog, text="Выберите приложения (можно несколько):", padding=8).pack(anchor="w")
        listbox = tk.Listbox(dialog, selectmode="extended", font=("Segoe UI", 10))
        listbox.pack(fill="both", expand=True, padx=8)
        for pkg in packages:
            listbox.insert("end", f"{builder.friendly(pkg)}  ·  {pkg}")
        result: list[str] = []

        def confirm() -> None:
            for index in listbox.curselection():
                result.append(packages[index])
            dialog.destroy()

        bar = ttk.Frame(dialog, padding=8)
        bar.pack(fill="x")
        ttk.Button(bar, text="Готово", command=confirm).pack(side="right")
        ttk.Button(bar, text="Отмена", command=dialog.destroy).pack(side="right", padx=8)
        dialog.transient(self)
        dialog.grab_set()
        self.wait_window(dialog)
        return result

    # ------------------------------------------------------------ VPN

    def _add_confs(self) -> None:
        paths = filedialog.askopenfilenames(title="Профили AmneziaWG",
                                            filetypes=[("Конфиги", "*.conf *.config"), ("Все файлы", "*.*")])
        if not paths:
            return
        confs = list(self.project.get("vpn_confs", []))
        for path in paths:
            rel = self._copy_into_project(Path(path), "vpn")
            if rel not in confs:
                confs.append(rel)
                self.conf_list.insert("end", Path(rel).name)
        self.project["vpn_confs"] = confs

    def _remove_conf(self) -> None:
        confs = list(self.project.get("vpn_confs", []))
        for index in reversed(self.conf_list.curselection()):
            name = self.conf_list.get(index)
            self.conf_list.delete(index)
            confs = [c for c in confs if Path(c).name != name]
        self.project["vpn_confs"] = confs

    def _sync_vpn_controls(self) -> None:
        enabled = not self.var_vpn_builtin.get()
        self.vpn_app_entry.configure(state="normal" if enabled else "disabled")
        self.vpn_pick_btn.configure(state="normal" if enabled else "disabled")

    # ------------------------------------------------------------ preview

    def _refresh_preview(self) -> None:
        if not hasattr(self, "preview"):
            return
        canvas = self.preview
        canvas.delete("all")
        width = max(canvas.winfo_width(), 540)
        height = max(canvas.winfo_height(), 292)
        project = self._collect() if hasattr(self, "fav_list") else self.project
        photo = None
        wallpaper = project.get("wallpaper", "")
        path = Path(self._display_path(wallpaper)) if wallpaper else None
        if path and path.exists() and path.stat().st_mtime != self._preview_cache:
            try:
                from PIL import Image, ImageTk
                image = Image.open(path).convert("RGB")
                target = width / height
                w, h = image.size
                if w / h > target:
                    new_w = int(h * target)
                    image = image.crop(((w - new_w) // 2, 0, (w + new_w) // 2, h))
                else:
                    new_h = int(w / target)
                    image = image.crop((0, (h - new_h) // 2, w, (h + new_h) // 2))
                photo = ImageTk.PhotoImage(image.resize((width, height), Image.LANCZOS))
                self._preview_cache = path.stat().st_mtime
                self._preview_photo = photo
            except Exception:
                photo = None
        if photo is None:
            photo = self._preview_photo
        if photo is not None:
            canvas.create_image(0, 0, image=photo, anchor="nw")
        else:
            canvas.create_rectangle(0, 0, width, height, fill="#20344a", outline="")
            canvas.create_text(width // 2, height // 2, text="Обои не выбраны\n(стандартные горы CleanYourTV)",
                               fill="#9db4c8", justify="center", font=("Segoe UI", 11))
        # top bar mock
        canvas.create_rectangle(0, 0, width, 44, fill="#000000", stipple="gray50", outline="")
        canvas.create_text(16, 22, anchor="w", text=project.get("name", "Мой лаунчер"), fill="white",
                           font=("Segoe UI Semibold", 13))
        builtin_source = (project.get("vpn_source") or "builtin") == "builtin"
        vpn_label = "Мой VPN" if builtin_source else builder.friendly(project.get("vpn_default_app") or "")
        canvas.create_rectangle(width - 300, 10, width - 96, 34, fill="#2b2b2b", outline="")
        canvas.create_text(width - 198, 22, text="VPN · " + (vpn_label or ""), fill="white",
                           font=("Segoe UI", 9))
        canvas.create_rectangle(width - 84, 12, width - 12, 32, fill="#2b2b2b", outline="")
        canvas.create_text(width - 48, 22, text="12:34", fill="white", font=("Segoe UI", 9))
        # favorites row mock
        favorites = project.get("favorites", [])[:5]
        total = max(len(favorites), 1)
        cell = min(150, (width - 40) // total - 10)
        x = 20
        canvas.create_text(20, height - 150, anchor="w", text="Избранное", fill="#e8eef4",
                           font=("Segoe UI Semibold", 11))
        for pkg in favorites:
            name = builder.friendly(pkg)
            canvas.create_rectangle(x, height - 120, x + cell, height - 40, fill="#f4f4f4", outline="")
            canvas.create_text(x + cell / 2, height - 80, text=name[:14], fill="#20242a",
                               font=("Segoe UI", 9))
            x += cell + 12
        # bottom hint
        source = "AmneziaWG" if builtin_source else (builder.friendly(project.get("vpn_default_app") or "приложение"))
        canvas.create_text(20, height - 18, anchor="w",
                           text=f"кнопка VPN: {source} · звук: {self.var_sound.get() if hasattr(self, 'var_sound') else ''} · "
                                f"экран: {project.get('default_screen')}",
                           fill="#cfe0ee", font=("Segoe UI", 9))
        self._preview_photo = photo

    def _on_tab_changed(self, _event=None) -> None:
        try:
            editor_open = self.notebook.index(self.notebook.select()) == self.notebook.index(self.tab_editor)
        except Exception:
            return
        if hasattr(self, "preview_box"):
            if editor_open:
                self.preview_box.grid_remove()
            else:
                self.preview_box.grid()

    # ------------------------------------------------------------ APK editor

    def _make_editor_tab(self, tab: ttk.Frame) -> None:
        self.editor_info = None
        self.editor_workdir = None
        tab.columnconfigure(1, weight=1)
        tab.rowconfigure(7, weight=1)
        bar = ttk.Frame(tab)
        bar.grid(row=0, column=0, columnspan=3, sticky="ew", pady=(0, 8))
        ttk.Button(bar, text="Открыть APK…", command=self._editor_open).pack(side="left")
        ttk.Button(bar, text="Собрать и подписать", command=self._editor_build).pack(side="left", padx=8)
        ttk.Button(bar, text="Установить на ТВ", command=self._editor_install).pack(side="left")

        self.editor_status = tk.StringVar(value="Выберите APK, чтобы поменять название, иконку или картинку.")
        ttk.Label(tab, textvariable=self.editor_status, style="Hint.TLabel").grid(
            row=1, column=0, columnspan=3, sticky="w", pady=(0, 8))

        ttk.Label(tab, text="Новое название").grid(row=2, column=0, sticky="w", pady=3)
        self.var_edit_label = tk.StringVar()
        ttk.Entry(tab, textvariable=self.var_edit_label).grid(row=2, column=1, columnspan=2, sticky="ew", pady=3)

        ttk.Label(tab, text="Новая иконка").grid(row=3, column=0, sticky="w", pady=3)
        self.var_edit_icon = tk.StringVar()
        ttk.Entry(tab, textvariable=self.var_edit_icon).grid(row=3, column=1, sticky="ew", pady=3)
        ttk.Button(tab, text="Выбрать…", command=self._editor_choose_icon).grid(row=3, column=2, padx=(6, 0))

        ttk.Label(tab, text="Картинки в APK (обои, баннеры, фоны)").grid(
            row=4, column=0, columnspan=3, sticky="w", pady=(10, 3))
        self.image_list = tk.Listbox(tab, height=9, exportselection=False, font=("Consolas", 9))
        self.image_list.grid(row=5, column=0, columnspan=2, sticky="nsew")
        scroll = ttk.Scrollbar(tab, orient="vertical", command=self.image_list.yview)
        scroll.grid(row=5, column=2, sticky="ns")
        self.image_list.configure(yscrollcommand=scroll.set)
        ttk.Button(tab, text="Заменить выбранную картинку…", command=self._editor_replace_image).grid(
            row=6, column=0, columnspan=3, sticky="w", pady=(6, 8))

        ttk.Label(tab, text="Журнал редактора").grid(row=7, column=0, sticky="nw")
        self.editor_log_widget = tk.Text(tab, height=10, wrap="none", font=("Consolas", 9),
                                         background="#0f1720", foreground="#d7e3ee")
        self.editor_log_widget.grid(row=8, column=0, columnspan=3, sticky="nsew", pady=(2, 0))
        ttk.Label(tab, text="Редактор меняет название и картинки в готовом APK и подписывает его тем же "
                            "debug-ключом. Если ключ не совпадёт с установленным — сначала удалите приложение.",
                  style="Hint.TLabel", wraplength=780).grid(row=9, column=0, columnspan=3, sticky="w", pady=(6, 0))

    def _elog(self, text: str) -> None:
        self.editor_log_widget.insert("end", text + "\n")
        self.editor_log_widget.see("end")

    def _editor_open(self) -> None:
        path = filedialog.askopenfilename(title="Открыть APK",
                                          filetypes=[("Android APK", "*.apk"), ("Все файлы", "*.*")])
        if not path:
            return
        self._save_settings()
        self.editor_status.set("Разбираю APK…")
        self._elog("=== " + path)

        def worker() -> None:
            try:
                workdir, info = editor.decode(Path(path), self.settings, log=lambda m: self.queue.put(("elog", m)))
                self.queue.put(("einfo", (str(workdir), info)))
            except Exception as exc:
                self.queue.put(("eerror", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _editor_choose_icon(self) -> None:
        path = filedialog.askopenfilename(title="Новая иконка",
                                          filetypes=[("Картинки", "*.png *.jpg *.jpeg *.webp")])
        if path:
            self.var_edit_icon.set(path)

    def _editor_replace_image(self) -> None:
        if not self.editor_info:
            messagebox.showinfo("Редактор APK", "Сначала откройте APK.")
            return
        selection = self.image_list.curselection()
        if not selection:
            messagebox.showinfo("Редактор APK", "Выберите картинку в списке.")
            return
        item = self.editor_info["images"][selection[0]]
        source = filedialog.askopenfilename(title="Картинка на замену",
                                            filetypes=[("Картинки", "*.png *.jpg *.jpeg *.webp")])
        if not source:
            return
        self._elog(f"Заменяю {item['path']} на {source}")
        try:
            editor.replace_image(Path(self.editor_workdir), item["path"], Path(source), log=self._elog)
        except Exception as exc:
            messagebox.showerror("Редактор APK", str(exc))

    def _editor_build(self) -> None:
        if not self.editor_info:
            messagebox.showinfo("Редактор APK", "Сначала откройте APK.")
            return
        self._save_settings()
        label = self.var_edit_label.get().strip() or self.editor_info["label"]
        icon = self.var_edit_icon.get().strip()
        info = self.editor_info
        workdir = Path(self.editor_workdir)
        out = builder.STUDIO / "edited" / builder.slugify(Path(info["apk"]).stem) / (
            builder.slugify(label) + "-edited.apk")
        self.editor_status.set("Собираю и подписываю…")

        def worker() -> None:
            try:
                editor.set_label(workdir, info["label"], label, log=lambda m: self.queue.put(("elog", m)))
                if icon:
                    editor.replace_icons(workdir, Path(icon), info.get("icons", []),
                                         log=lambda m: self.queue.put(("elog", m)))
                result = editor.build(workdir, out, self.settings,
                                      log=lambda m: self.queue.put(("elog", m)))
                self.queue.put(("ebuilt", str(result)))
            except Exception as exc:
                self.queue.put(("eerror", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _editor_install(self) -> None:
        candidates = sorted((builder.STUDIO / "edited").glob("*/**/*-edited.apk"), key=lambda p: p.stat().st_mtime)
        path = None
        if candidates:
            path = candidates[-1]
        if not path or not path.exists():
            messagebox.showinfo("Редактор APK", "Сначала соберите APK в редакторе.")
            return
        self._save_settings()
        adb = dict(self.settings)
        self.editor_status.set("Устанавливаю…")

        def worker() -> None:
            try:
                editor.install(path, adb, log=lambda m: self.queue.put(("elog", m)))
                self.queue.put(("einstalled", str(path)))
            except Exception as exc:
                self.queue.put(("eerror", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    # ------------------------------------------------------------ build / install

    def _log(self, text: str) -> None:
        self.log_widget.insert("end", text + "\n")
        self.log_widget.see("end")

    def _set_busy(self, busy: bool) -> None:
        self.busy = busy
        state = "disabled" if busy else "normal"
        for button in (self.btn_build, self.btn_install, self.btn_both, self.btn_check):
            button.configure(state=state)
        if busy:
            self.progress.start(12)
        else:
            self.progress.stop()

    def _check_tools(self) -> None:
        self._save_settings()
        problems = builder.check_tools(self.settings)
        if problems:
            messagebox.showerror("Не хватает инструментов", "\n".join(problems))
        else:
            messagebox.showinfo("Проверка", "Всё на месте: JDK, Android SDK, шаблон и ключ подписи.")

    def _start_build(self, install: bool = False) -> None:
        if self.busy:
            return
        if not self._save_project(silent=True):
            return
        project = dict(self.project)
        settings = dict(self.settings)
        self._set_busy(True)
        self.notebook.select(self.tab_build)
        self.log_widget.delete("1.0", "end")

        def worker() -> None:
            try:
                result = builder.build(project, settings, log=lambda line: self.queue.put(("log", line)))
                self.queue.put(("built", result))
                if install:
                    builder.adb_install(settings, project, result["apk"],
                                        log=lambda line: self.queue.put(("log", line)))
                    self.queue.put(("installed", result))
            except Exception as exc:
                self.queue.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _start_install(self) -> None:
        if self.busy:
            return
        if not self._save_project(silent=True):
            return
        project = dict(self.project)
        settings = dict(self.settings)
        candidates = sorted(builder.project_dir(project).glob("out/*.apk"), key=lambda p: p.stat().st_mtime)
        if not candidates:
            messagebox.showinfo("Установка", "Сначала соберите APK кнопкой «Собрать APK».")
            return
        apk = str(candidates[-1])
        self._set_busy(True)
        self.notebook.select(self.tab_build)
        self.log_widget.delete("1.0", "end")

        def worker() -> None:
            try:
                builder.adb_install(settings, project, apk, log=lambda line: self.queue.put(("log", line)))
                self.queue.put(("installed", {"apk": apk}))
            except Exception as exc:
                self.queue.put(("error", str(exc)))

        threading.Thread(target=worker, daemon=True).start()

    def _poll_queue(self) -> None:
        try:
            while True:
                kind, payload = self.queue.get_nowait()
                if kind == "log":
                    self._log(payload)
                elif kind == "built":
                    self._log("APK: " + payload["apk"] + f"  ({payload['size'] / 1048576:.2f} МБ)")
                    self.status.set("Сборка готова: " + Path(payload["apk"]).name)
                elif kind == "installed":
                    self._log("Установлено на телевизор.")
                    self.status.set("Установлено: " + Path(payload["apk"]).name)
                    self._set_busy(False)
                elif kind == "elog":
                    self._elog(payload)
                elif kind == "einfo":
                    self.editor_workdir, info = payload
                    self.editor_info = info
                    self.var_edit_label.set(info["label"])
                    self.image_list.delete(0, "end")
                    for item in info.get("images", []):
                        self.image_list.insert("end", f"{item['path']}  ·  {item['width']}×{item['height']}")
                    self.editor_status.set(f"{info['package']}  ·  v{info['version_name']}  ·  «{info['label']}»")
                    self._elog(f"Пакет: {info['package']}, версия {info['version_name']}, название «{info['label']}»")
                    self.notebook.select(self.tab_editor)
                elif kind == "ebuilt":
                    self.editor_status.set("Готово: " + Path(payload).name)
                    self._elog("APK: " + payload)
                elif kind == "einstalled":
                    self.editor_status.set("Установлено на ТВ")
                elif kind == "eerror":
                    self.editor_status.set("Ошибка — см. журнал")
                    self._elog("ОШИБКА: " + payload)
                    messagebox.showerror("Редактор APK", payload[:3000])
                elif kind == "error":
                    self._set_busy(False)
                    self._log("ОШИБКА: " + payload)
                    self.status.set("Ошибка — подробности в журнале")
                    messagebox.showerror("Launcher Studio", payload[:4000])
        except queue.Empty:
            pass
        self.after(120, self._poll_queue)

    def _browse_path(self, key: str, kind: str) -> None:
        if kind == "dir":
            path = filedialog.askdirectory(title=key)
        else:
            path = filedialog.askopenfilename(title=key)
        if path:
            self.path_vars[key].set(path)

    def _on_close(self) -> None:
        try:
            self._save_settings()
            if self._collect().get("name"):
                builder.save_project(self._collect())
        except Exception:
            pass
        self.destroy()


def main() -> None:
    app = Studio()
    if os.environ.get("STUDIO_SMOKE") == "1":
        app.after(1500, app.destroy)
    app.mainloop()


if __name__ == "__main__":
    main()

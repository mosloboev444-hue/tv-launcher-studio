"""Adds presets and the APK-editor tab to studio.py."""
p = "studio.py"
s = open(p, encoding="utf-8").read()

# ---------------------------------------------------------------- imports
s = s.replace("import builder\n", "import builder\nimport editor\nimport presets\n", 1)

# ---------------------------------------------------------------- new editor tab
s = s.replace('''        self.tab_build = self._tab("06 Сборка и ТВ", self._make_build_tab)''',
'''        self.tab_build = self._tab("06 Сборка и ТВ", self._make_build_tab)
        self.tab_editor = self._tab("07 Редактор APK", self._make_editor_tab)''')

# ---------------------------------------------------------------- preset row in the main tab
s = s.replace('''        files = ttk.LabelFrame(tab, text="Обои и оформление", padding=10)
        files.grid(row=6, column=0, columnspan=2, sticky="ew", pady=(14, 0))''',
'''        preset_box = ttk.LabelFrame(tab, text="Пресет оформления", padding=10)
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
        files.grid(row=7, column=0, columnspan=2, sticky="ew", pady=(14, 0))''')
s = s.replace('''        ttk.Label(files, text="Если не выбрать — останутся стандартные горы и иконка CleanYourTV.",
                  style="Hint.TLabel").grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 0))''',
'''        ttk.Label(files, text="Если не выбрать — останутся стандартные горы и иконка CleanYourTV.",
                  style="Hint.TLabel").grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 0))
        self.preset_hint.set(presets.PRESETS[0]["hint"])''')

# ---------------------------------------------------------------- preset application method (before favorites tab)
s = s.replace('''    def _make_favorites_tab(self, tab: ttk.Frame) -> None:''',
'''    def _apply_preset(self) -> None:
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

    def _make_favorites_tab(self, tab: ttk.Frame) -> None:''')

# ---------------------------------------------------------------- editor tab + methods
s = s.replace('''    # ------------------------------------------------------------ build / install''',
'''    # ------------------------------------------------------------ APK editor

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
        self.image_list = tk.Listbox(tab, height=7, exportselection=False, font=("Consolas", 9))
        self.image_list.grid(row=5, column=0, columnspan=2, sticky="nsew")
        scroll = ttk.Scrollbar(tab, orient="vertical", command=self.image_list.yview)
        scroll.grid(row=5, column=2, sticky="ns")
        self.image_list.configure(yscrollcommand=scroll.set)
        ttk.Button(tab, text="Заменить выбранную картинку…", command=self._editor_replace_image).grid(
            row=6, column=0, columnspan=3, sticky="w", pady=(6, 8))

        ttk.Label(tab, text="Журнал редактора").grid(row=7, column=0, sticky="nw")
        self.editor_log_widget = tk.Text(tab, height=9, wrap="none", font=("Consolas", 9),
                                         background="#0f1720", foreground="#d7e3ee")
        self.editor_log_widget.grid(row=8, column=0, columnspan=3, sticky="nsew", pady=(2, 0))
        ttk.Label(tab, text="Редактор меняет название и картинки в готовом APK и подписывает его тем же "
                            "debug-ключом. Если ключ не совпадёт с установленным — сначала удалите приложение.",
                  style="Hint.TLabel", wraplength=780).grid(row=9, column=0, columnspan=3, sticky="w", pady=(6, 0))

    def _elog(self, text: str) -> None:
        self.editor_log_widget.insert("end", text + "\\n")
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

    # ------------------------------------------------------------ build / install''')

# ---------------------------------------------------------------- queue handling for the editor
s = s.replace('''                elif kind == "error":''',
'''                elif kind == "elog":
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
                elif kind == "error":''')

open(p, "w", encoding="utf-8").write(s)
print("studio patched: presets + editor tab")

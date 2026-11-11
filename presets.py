"""Ready-made looks for Launcher Studio.

A preset only fills in defaults (screen, sounds, wallpaper, seed favorites). Every field stays editable
afterwards; applying a preset never touches files the user already picked except the wallpaper.
"""
from __future__ import annotations

from pathlib import Path

# Wallpaper sources live in the template's resources, so a preset works even before the user picks an image.
_TEMPLATE_RES = Path(__file__).resolve().parent / "template" / "launcher" / "app" / "src" / "main" / "res"

PRESETS = [
    {
        "id": "google-tv",
        "name": "Google TV",
        "hint": "Светлый минимализм с горами: универсальный экран, стеклянная плашка, спокойные звуки.",
        "screen": "universal",
        "sound": 0,
        "volume": 2,
        "screensaver": 5,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_lake.jpg",
        "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv", "ru.rutube.app"],
    },
    {
        "id": "cinema",
        "name": "Кино",
        "hint": "Карусель из видео: часы, погода, круглые кнопки и док с плёнкой.",
        "screen": "cinema",
        "sound": 1,
        "volume": 3,
        "screensaver": 10,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_lake.jpg",
        "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv", "ru.rutube.app",
                      "com.gathin.vpngate"],
    },
    {
        "id": "minimal",
        "name": "Минимализм",
        "hint": "Ночные обои, название без плашки, тихие звуки и заставка выключена.",
        "screen": "universal",
        "sound": 1,
        "volume": 1,
        "screensaver": 0,
        "chip": 1,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_night.jpg",
        "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv"],
    },
    {
        "id": "night",
        "name": "Ночь",
        "hint": "Тёмные обои, хрустальные звуки, заставка с часами через 10 минут.",
        "screen": "universal",
        "sound": 2,
        "volume": 1,
        "screensaver": 10,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_night.jpg",
        "favorites": ["com.google.android.youtube.tv", "com.vk.tv", "ru.rutube.app"],
    },
    {
        "id": "kids",
        "name": "Детский",
        "hint": "Закат, крупные звуки, быстрая заставка; в избранном — детские приложения (замените на свои).",
        "screen": "universal",
        "sound": 0,
        "volume": 3,
        "screensaver": 2,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_sunset.jpg",
        "favorites": ["com.google.android.youtube.tv", "com.gathin.karaoke"],
    },
    {
        "id": "classic",
        "name": "Классика CleanYourTV",
        "hint": "Привычный домашний экран 3.2: приветствие, док-карточки и строки приложений.",
        "screen": "classic",
        "sound": 1,
        "volume": 2,
        "screensaver": 5,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_lake.jpg",
        "favorites": ["com.google.android.youtube.tv", "com.gathin.vpngate", "com.gathin.karaoke"],
    },    {
        "id": "pollytv",
        "name": "PollyTV",
        "hint": "Тёмная атмосфера, часы и дата, док с увеличением, темы и настройки PollyTV.",
        "screen": "polly",
        "sound": 1,
        "volume": 2,
        "screensaver": 5,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_night.jpg",
        "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv", "ru.rutube.app"],
    },
    {
        "id": "wintv",
        "name": "WinTV",
        "hint": "Windows-стиль: строка приложений, панель «Пуск», часы и меню WinTV.",
        "screen": "wintv",
        "sound": 0,
        "volume": 2,
        "screensaver": 5,
        "chip": 0,
        "wallpaper": _TEMPLATE_RES / "drawable-nodpi" / "wp_lake.jpg",
        "favorites": ["com.google.android.youtube.tv", "ru.kinopoisk.tv", "com.vk.tv"],
    },
]


def by_id(preset_id: str) -> dict | None:
    for preset in PRESETS:
        if preset["id"] == preset_id:
            return preset
    return None


def names() -> list[str]:
    return [p["name"] for p in PRESETS]


def resolve(name: str) -> dict | None:
    for preset in PRESETS:
        if preset["name"] == name:
            return preset
    return None

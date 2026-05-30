# RimDroid

Лаунчер для запуска **RimWorld** (нативный Linux x86_64-билд, Unity) на Android-телефоне через эмуляцию x86_64→ARM64 — с реальным GPU-рендером.

> Статус: **в разработке.** RimWorld 1.5 запускается, инициализирует Mono и GPU-конвейер, рисует главное меню — но упирается в одну стену (см. ниже).

---

## Что это и зачем

RimWorld официально собран только под x86_64 (Windows/Linux/macOS). RimDroid берёт **нативный Linux-билд игры** и запускает его прямо на ARM64-телефоне:

- **box64** эмулирует x86_64-код (движок Unity + Mono) на ARM64;
- запуск **in-process, без fork** — это возможно для 1.5, т.к. `UnityPlayer.so` релокатируемый (PIE) и ложится ниже ART-heap (в отличие от монолитной 1.2, которой нужен был fork, ломавший GPU);
- графика идёт через настоящий GPU телефона, а не софтрендер.

## Стек / на чём работает

| Слой | Что используется |
|------|------------------|
| Эмуляция | **box64** (x86_64 → ARM64), in-process |
| GPU | **Zink** (OpenGL→Vulkan, Mesa) поверх **Turnip** (`libvulkan_freedreno`, Adreno) → реальный **OpenGL 4.3 Core** через `libzfa.so` |
| Окно/ввод | **SDL2** с `SDL_VIDEODRIVER=dummy` (без X11/Wayland); рендер в `ANativeWindow` Android-Activity |
| SDL dynapi | **remap** jump_table под порядок процедур SDL, вшитой в `UnityPlayer.so` (отличается от box64) |
| Рантайм игры | родной **Mono / Boehm GC** из поставки RimWorld |

Устройство разработки: **Snapdragon 8 Elite, Adreno 830** (рендер сейчас выставлен 1024×768, масштабируется на экран).

## Что уже работает

- ✅ Запуск RimWorld 1.5 in-process (без fork);
- ✅ box64 + SDL dynapi seeding/remap, GL-бриджи (CreateContext/MakeCurrent/SwapWindow и т.д.);
- ✅ Полный GPU-конвейер: Zink/Vulkan/Turnip отдаёт GL 4.3 Core, default-framebuffer корректный, текстуры/шейдеры грузятся без ошибок;
- ✅ Mono стартует, грузятся сборки, печатается баннер `RimWorld 1.5.x`;
- ✅ Рисуется **главное меню** (фон + UI), масштабируется на весь экран.

## Где остановились (текущая стена)

На старте Unity 1.5 переключается **windowed → fullscreen**, и его `GfxDevice` уходит в **бесконечный teardown** (рекурсивный обход с `SDL_GL_DeleteContext` без конца) — до первого `SwapWindow` дело не доходит, картинка «застывает» на первом кадре.

Проверено и **исключено** как причина:
- размер окна / рассинхрон FBO (всё свели в единый 1024×768 — петля та же);
- частота 0 Hz dummy-драйвера (форсили 60 Hz — петля та же);
- битый контекст/окно/формат, падение шейдера, GC, advapi32 — всё опровергнуто данными.

**Вывод:** триггер — сам fullscreen-переход в Unity (сброс/пересоздание GfxDevice), который в связке dummy-SDL + фиксированный ZFA-контекст не завершается.

## Следующие шаги

1. **RimWorld 1.6** (новее Unity, 2025) — возможно, там этого teardown нет вовсе (потребует заново выверить dynapi-remap);
2. на 1.5 — выяснить, почему `-screen-fullscreen 0` игнорируется, и заставить остаться в окне; либо заглушить пересоздание контекста в teardown;
3. ввод (тач→SDL-события), звук (FMOD) — после того, как меню оживёт.

## Сборка

- Android Studio, **JDK 21** (JDK 25 ломает компиляцию Kotlin DSL — см. `gradle.properties`);
- нативная часть: `gradlew :app:externalNativeBuildDebug`;
- `box64/` — submodule (форк `udarmolota/rimdroid-box64`);
- в инстанс кладётся распакованный Linux-билд RimWorld (`RimWorldLinux` + `RimWorldLinux_Data`).

---

*Проект экспериментальный. Основная логика — в `app/src/main/cpp/rimdroid.c` (запуск, ZFA/GPU) и `box64/src/wrapped/wrappedsdl2.c` (перехваты SDL/GL).*

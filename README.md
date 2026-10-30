# NeonSync

<img src="src/main/resources/neonsync.svg" width="64" align="right" alt="">

Bias lighting for KDE Plasma on Wayland. NeonSync drives your Zigbee lights (e.g. Philips Hue Play
bars) from the screen colours and/or the music that is playing, through Home Assistant / ZHA. You
don't need a Hue Bridge.

```
KDE screen ──► xdg-desktop-portal (ScreenCast) ──► PipeWire ──► GStreamer (downscale to 64×36) ──┐
Sound output ──► PipeWire monitor ──► GStreamer ──► loudness / bands ────────────────────────────┤
                                                                                                 ▼
           per-light colour (by position in the room) ──► smoothing / rate limiting ──► Home Assistant WebSocket ──► ZHA ──► Zigbee
```

## Features

- **Window** (Compose) with three tabs:
  - **Lights**: all Home Assistant lights, grouped by area. You can collapse areas. Tick single lights
    and/or whole areas. A ticked area also includes lights you add to it later. Lights without
    colour support are shown but can't be selected.
  - **Room**: an abstract 3D room with your screen in the middle, where you place every selected light.
    - Drag a light to move it on the floor plane, Shift+drag to change its height, drag empty space
      to rotate the view, and scroll to zoom. Sliders and presets ("Behind left", …) are in the side panel.
    - The position decides what the light does: a light next to the left screen edge shows the left
      edge of the screen, and a light across the room shows the whole screen. The side panel previews
      the screen area. Left/right position also picks the stereo channel for audio.
    - Per light you choose which frequency bands it reacts to (**Bass**, **Mids**, **Highs**, any
      combination; default all). A bass-only light pumps with the kick drum while a highs-only light
      flickers with the hi-hats.
  - **Settings**: Home Assistant URL and token, the audio-only colours, and screen selection.
  - Both tabs show the real state of every light (on/off, colour, brightness), live via Home
    Assistant's `state_changed` events, whether or not sync is running. Lights that are off are drawn unlit.
  - Everything is saved in `~/.config/neonsync/config.json`.
- **Three modes**:
  - **Screen**: classic bias lighting.
  - **Screen + Audio**: screen colours, with the brightness pulsing to the music (bass weighted).
  - **Audio only**: no screen capture. The brightness follows the music, and silence turns the lights off.
    The colour either comes from the sound, where you pick a colour for bass, mids and highs and each band
    adds its colour by how loud it is, or is a fixed colour. Colour changes apply immediately.
- **Wayland screen capture** through the ScreenCast portal (the only supported way on Plasma Wayland).
  KDE asks which screen to share the first time. After that a restore token is used, so later
  starts don't prompt again. The screen is only requested once Home Assistant is connected and at
  least one light is selected.
- **Audio capture** from the monitor of the default PipeWire output in stereo; no microphone is used.
  Each channel is split into bass (< 150 Hz), mids and highs (> 2 kHz). Every band is normalized on
  its own, and the gain adjusts automatically, so it works at any volume.
- **Colour analysis in linear light**, with extra weight for saturated pixels, a saturation boost, and
  clamping to the Hue gamut C.
- **Zigbee-friendly updates**:
  - A fixed update rate per light (default 5/s).
  - Bulb-side transitions glide between updates.
  - Commands are only sent when something changed.
  - A new command is only sent after ZHA has acknowledged the previous one. If the mesh is slow,
    the update rate drops instead of a backlog of stale colours building up.
- **Logo**: `src/main/resources/neonsync.svg`, a neon stage spotlight. It is used for the tray icon,
  the window icon and the installed app icon.
- **Tray icon** (native StatusNotifierItem):
  - Left click: open the window.
  - Mouse wheel: adjust brightness.
  - Menu: on/off, mode, brightness, select screen, quit.
  - Closing the window keeps the app running in the tray.
- **Restores the lights' previous state** when you stop the app or deselect a light. Alternatively they
  can be turned off or left as they are.
- **Screen lock**: turns the lights off (or restores them) while the screen is locked.
- **Single instance**: starting the app again focuses the window of the running instance. With
  `--tray` (autostart) it exits quietly instead.

## Why no Hue Bridge?

A bridge would allow the Hue Entertainment API, which streams at about 25 updates/s. It also
addresses the segments of gradient light strips individually. Through ZHA, every update is a regular
Zigbee unicast command, which in practice allows about 5–10 updates per light per second. Combined
with bulb-side transitions, that is plenty for bias lighting, where you want calm ambient light
rather than flicker-fast reactions. If you ever want faster, film-synchronous effects, the bridge is
the upgrade path.

## Requirements

- Java 21 or newer (to build and run)
- GStreamer 1.18+ with the PipeWire plugin. On Arch: `gstreamer gst-plugins-base gst-plugin-pipewire`
- `xdg-desktop-portal` and `xdg-desktop-portal-kde` (installed by default with Plasma)
- Home Assistant with the lights in ZHA, and a long-lived access token
  (HA → your profile → Security → Long-lived access tokens)

The Arch package pulls all of these in.

## Install (Arch Linux)

```bash
cd packaging/arch
makepkg -si                                    # builds from this checkout, runs the tests, installs the package
systemctl --user enable --now neonsync        # optional: start in the tray on every login
```

Then start **NeonSync** from the application menu, or run `neonsync`. Only one instance runs: starting
it again (e.g. from the menu while it sits in the tray) focuses the running window.

The package installs:

- `/usr/bin/neonsync`, with the app in `/usr/share/neonsync/`
- the desktop entry and the icon (`hicolor/scalable/apps/neonsync.svg`)
- the systemd user unit `neonsync.service`

On the first start the window opens on the Settings tab. Enter the Home Assistant URL and token,
then pick your lights in the Lights tab and place them in the Room tab.

For areas to show up, the token must belong to an **admin** user: Home Assistant only exposes the
area registry to admins. Otherwise all lights are listed under "No area".

Command-line helpers: `neonsync list-lights`, `neonsync test-lights` (flashes the selected
lights red/green/blue one after another).

Logs: `journalctl --user -u neonsync -f` (or the terminal when started manually).

For development, `./gradlew run` or running `neonsync.MainKt` from IntelliJ works as well. Any JDK 21 or newer can build it.

## Configuration

Everything lives in `~/.config/neonsync/config.json` (comments and trailing commas allowed).
The `setup` section (lights, positions, mode, colours, brightness) is managed by the UI. The rest
are tuning values for editing by hand; restart the app after editing. The app only rewrites the
`setup` and `homeAssistant` sections and keeps your other values, although comments are lost when
it saves. The only other file is the screen-share restore token in `~/.local/state/neonsync/`.

```jsonc
{
  "homeAssistant": { "url": "http://homeassistant.local:8123", "token": "eyJ..." },
  "setup": { /* written by the UI */ },
  "capture": { "maxFps": 30, "analysisWidth": 64, "analysisHeight": 36 },
  "color": {
    "updatesPerSecond": 5.0,      // per light; Zigbee bandwidth is limited, try 3..10
    "transitionMs": -1,           // -1 = match update interval
    "smoothingMs": 150,           // extra software smoothing
    "snapThreshold": 0.15,        // big colour jumps (scene cuts) skip smoothing
    "saturation": 1.25,
    "saturatedPixelWeight": 3.0,
    "minBrightness": 3,
    "maxBrightness": 255,
    "brightnessGamma": 1.0,
    "minColorDelta": 0.004,
    "minBrightnessDelta": 3
  },
  "audio": {
    "depth": 0.6,                 // Screen + Audio: 0 = no pulsing, 1 = quiet parts go fully dark
    "bassWeight": 0.7,            // 1 = react to bass (< 150 Hz) only
    "attackMs": 10,
    "releaseMs": 250,
    "transitionMs": 100,          // bulb transition in audio modes (shorter = punchier)
    "offThreshold": 0.05          // Audio only: lights switch off below this loudness
  },
  "onStop": "RESTORE",            // RESTORE | OFF | KEEP
  "onScreenLock": "OFF",          // RESTORE | OFF | KEEP
  "autoStart": true               // start driving the lights immediately when launched
}
```

Tips:

- If all bars should always show the same colour, create a **ZHA group** in HA and select the
  group entity instead of the single bars. ZHA then sends one multicast command instead of one per
  bar, which halves the Zigbee traffic.
- The tray tooltip shows the updates per second each light actually gets, and the command latency.
  If the latency is high (> 200 ms), lower `updatesPerSecond`.
- Audio reaction is limited by Zigbee: with 5 updates/s, the lights follow the energy of the music
  (drops, loud passages) rather than every single beat. Raising `color.updatesPerSecond` to 8–10
  makes it snappier if your mesh handles it (watch the latency in the tooltip).
- To pick a different screen, use "Select screen…" in the tray menu or in Settings.

## Project layout

| File | Purpose |
|---|---|
| `Main.kt` | CLI (`run [--tray]`, `list-lights`, `test-lights`) |
| `NeonSyncApp.kt` | Orchestration: one control thread, `reconcile()` of capture/audio/lights, restore, UI state |
| `ui/` | Compose UI: `AppUi` (window, top bar), `LightsScreen`, `RoomScreen` + `Camera` (3D view), `SettingsScreen` |
| `Placement.kt` | 3D position → screen zone, stereo pan, default spots |
| `HomeAssistantDirectory.kt` | Lights grouped by area from the HA registries, light state parsing |
| `SingleInstance.kt` | D-Bus name so a second start focuses the running window |
| `Logo.kt` | Renders the SVG logo (Skia) for the tray and the window |
| `ScreenCastPortal.kt` | `org.freedesktop.portal.ScreenCast` over D-Bus |
| `ScreenCapture.kt` | GStreamer pipeline `pipewiresrc → videoscale → appsink` |
| `ColorMath.kt` | sRGB → CIE xy, gamut clamping, zone analysis |
| `AudioReactor.kt` | Stereo audio capture (PipeWire sink monitor), loudness and per-channel band analysis |
| `LightController.kt` | Per-light colour per mode, smoothing, rate limiting, back-pressure, off on silence |
| `HomeAssistantClient.kt` | HA WebSocket API client with reconnect and event subscriptions |
| `Tray.kt` | StatusNotifierItem + DBusMenu tray icon |
| `ScreenLockMonitor.kt` | `org.freedesktop.ScreenSaver.ActiveChanged` |
| `packaging/` | Desktop entry, systemd user unit, `arch/PKGBUILD` |

# Assist

**Assist** is a Google Assistant / Siri style, voice-controlled AI home-assistant app for
**rooted Android devices**, built against any **OpenAI-compatible** chat completions API
(self-hosted LLMs, tunneled endpoints, OpenAI itself, etc.). It talks to the model with full
function/tool-calling and executes those tools with **root (`su`) access**, so the model can
genuinely control the phone — Wi-Fi/Bluetooth, volume, apps, files, the shell itself, screen
power, reboot/shutdown — and run the device **headless**, like a dedicated smart speaker.

> ⚠️ This app deliberately grants an AI model the ability to run arbitrary root shell commands,
> install/uninstall apps, read/write any file, and reboot or wipe the device. Only point it at an
> endpoint and model you trust, keep "confirm dangerous actions" enabled, and understand the
> security implications of root + an LLM with tool access before using it on a daily-driver phone.

---

## What's in the box

```
app/
  src/main/java/com/netbypass/assist/
    MainActivity.kt            Settings UI (base URL / API key / model, wake word, toggles, logs)
    MyApplication.kt           App entry point, global crash logging
    SettingsStore.kt           Encrypted settings storage
    service/AssistantService.kt  Foreground "always listening" service (the core loop)
    service/BootReceiver.kt    Auto-start on boot
    voice/SpeechToText.kt      Coroutine wrapper around Android SpeechRecognizer
    voice/TextToSpeechManager.kt Coroutine wrapper around Android TextToSpeech
    voice/WakeWordListener.kt  Loops STT until it hears your wake word
    ai/ChatModels.kt           OpenAI chat message / tool-call data model + JSON (de)serialization
    ai/OpenAiClient.kt         Minimal OkHttp client for /v1/chat/completions and /v1/models
    ai/ToolSchema.kt           JSON-schema "tools" definitions advertised to the model
    ai/ConversationManager.kt  The ask-model -> run-tools -> ask-model loop + dangerous-action confirmation
    tools/RootShell.kt         Persistent `su` shell wrapper
    tools/ToolRegistry.kt      Implements every tool (shell, device, filesystem, apps, media, ...)
    tools/DangerousActions.kt  Which tools require spoken "yes" confirmation first
    util/Logger.kt             In-app ring-buffer logger shown live in Settings
scripts/root-setup.sh          One-shot root script: grants perms, whitelists from battery/doze
.github/workflows/android-ci.yml  CI that builds the debug APK on every push
```

## Default endpoint

The app ships pre-configured (editable any time in Settings) with:

- **Base URL:** `https://rt2m89p.abc-tunnel.us/v1`
- **API key:** `sk-f29f537b315b2217-nlljxo-3d9f632e`
- **Model:** `gpt-4o-mini` (placeholder — tap **"Fetch available models"** in Settings to list the
  models your endpoint actually serves via `GET /v1/models`, and it will pick the first one for
  you)

If your endpoint doesn't require a key, just clear the API key field and save — the app sends no
`Authorization` header when the key is blank.

---

## Building the APK

### Option A — CI (recommended, zero local setup)

Every push to this branch triggers `.github/workflows/android-ci.yml`, which builds
`app:assembleDebug` on a full GitHub-hosted Android toolchain and uploads the resulting APK as a
workflow artifact named **assist-debug-apk**. Grab it from the **Actions** tab of this repo.

The first successful run also commits the generated Gradle wrapper (`gradlew`, `gradlew.bat`,
`gradle/wrapper/`) back to the branch, so after that you can build locally too.

### Option B — Android Studio

1. Open this repository's root folder in Android Studio (Koala/Ladybug or newer).
2. Let it sync Gradle (it will generate the wrapper / download Gradle automatically if it's not
   present yet).
3. Run the `app` configuration on a device/emulator, or **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

### Option C — Command line (after the wrapper exists)

```bash
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

---

## Installing on a rooted device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then, on the device (or via `adb shell`):

1. Run `scripts/root-setup.sh` as root once (see the script header for how) — it grants runtime
   permissions, whitelists the app from Doze/battery-optimization killing, and relaxes the screen
   timeout so headless mode behaves well.
2. Open **Assist**.
3. Tap **"Request / test root (su)"** and accept the Magisk/SuperSU prompt — this is what lets the
   app run privileged commands for every device-control tool.
4. Check/edit **Base URL**, **API key**, **Model**, **Wake word**, tap **Save**.
5. Tap **"Test microphone + speaker"** to confirm STT/TTS work on this device/ROM.
6. Tap **"Disable battery optimization for Assist"** and allow it when Android asks.
7. Toggle **"Start automatically on boot"** and, for a dedicated always-on speaker, **"Headless
   mode"**.
8. Tap **Start**. Say your wake word (default **"hey assist"**) followed by a command, e.g.
   *"Hey Assist, what's my battery level?"* or *"Hey Assist, turn on the flashlight."*

### Making the device truly headless

"Headless" here means: the phone keeps running the assistant with **no screen on and no user
interaction**, like a smart speaker or appliance.

- Enable the **Headless mode** switch (or ask the assistant: *"turn on headless mode"*) — this
  whitelists the app from Doze, stops the screen from being forced on while charging, and turns
  the display off.
- Leave the device plugged into power (voice recognition + a foreground service running 24/7
  will drain a battery otherwise).
- Disable the lock screen (**Settings > Security > Screen lock > None**) so `BOOT_COMPLETED` can
  bring the service up without anyone unlocking the phone first.
- For a true kiosk (no visible UI at all, status bar/launcher hidden), the model/you can call the
  `disable_system_ui` tool — this disables `com.android.systemui`. **This is advanced and can make
  the screen unusable until you re-enable it** (`pm enable com.android.systemui`, via `adb shell`
  or the Assist app's "Request / test root" shell), only use it if you understand the tradeoff.

---

## How tool-calling works

On every turn, the app sends the running conversation plus a list of ~30 tool definitions (see
`ai/ToolSchema.kt`) to `POST {base_url}/chat/completions` using the standard OpenAI `tools` /
`tool_choice: "auto"` fields. When the model responds with `tool_calls`, `ToolRegistry` executes
them (mostly via a persistent root shell, some via plain Android APIs like `AudioManager` or
`CameraManager`) and the JSON result is fed back as a `role: "tool"` message so the model can
summarize what happened in natural, spoken language. This repeats (bounded to 6 rounds per
utterance) until the model returns plain text, which is spoken via TTS.

Dangerous tools (reboot, shutdown, uninstalling apps, risky shell commands, writing into
`/system`, disabling System UI, ...) are intercepted by `DangerousActions` + `ConversationManager`:
instead of running immediately, Assist speaks a confirmation question and only proceeds if your
reply contains a "yes" (configurable via the **"Ask for spoken confirmation..."** switch).

### Tool list (all in `ToolSchema.kt` / `ToolRegistry.kt`)

`run_shell`, `set_volume`, `wifi_power`, `bluetooth_power`, `mobile_data_power`, `airplane_mode`,
`screen_power`, `set_brightness`, `launch_app`, `kill_app`, `list_apps`, `send_notification`,
`get_battery_status`, `get_device_info`, `get_network_info`, `get_location`, `take_screenshot`,
`set_headless_mode`, `read_file`, `write_file`, `list_dir`, `reboot_device`, `shutdown_device`,
`media_control`, `get_clipboard`, `set_clipboard`, `vibrate`, `set_flashlight`, `open_url`,
`install_apk`, `uninstall_app`, `set_system_setting`, `disable_system_ui`.

`run_shell` is the catch-all "full access" tool — it runs any command you/the model give it as
root, for anything not covered by a dedicated tool above.

---

## Voice notes & limitations

- STT/TTS use Android's built-in `SpeechRecognizer` / `TextToSpeech` rather than a bundled
  offline model, so there is no multi-hundred-MB model to ship and it works on any device with a
  working recognition service (Google app / Android System Intelligence on GMS devices; AOSP ROMs
  need a `RecognitionService` installed, e.g. an open-source Vosk-based one, for this to work
  fully offline).
- The "wake word" is implemented by continuously re-running the recognizer and checking the
  transcript for your phrase — simple and dependency-free, but not as power-efficient or
  instantaneous as a dedicated wake-word engine (e.g. Picovoice Porcupine). Swapping in such an
  engine would be a drop-in replacement for `voice/WakeWordListener.kt`.
- Treat the sample endpoint/key in this repo as a placeholder for your own tunnel — rotate the
  key if this repository is public.

## Security checklist before leaving this running unattended

- [ ] Keep **"Ask for spoken confirmation before dangerous actions"** ON.
- [ ] Use an endpoint/model you trust — it can run arbitrary root shell commands via `run_shell`.
- [ ] Don't expose the device's microphone/speaker to untrusted people within earshot if dangerous
      actions are enabled without confirmation.
- [ ] Rotate the API key if you fork/publish this repo with real credentials in it.

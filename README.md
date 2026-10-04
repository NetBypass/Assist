# Assist

Assist is a sideloadable Android voice assistant for a dedicated rooted device. It talks to any **OpenAI Chat Completions-compatible** server, supports endpoints with or without a bearer key, exposes opt-in root tools to the model, runs a foreground voice listener, and can register as Android's default assistant.

> This app intentionally has powerful capabilities. Root tools are **off by default**. Only enable them for an API endpoint and model operator you trust.

## Features

- Configurable OpenAI-compatible base URL, model, and optional API key
- Calls `POST …/v1/chat/completions`, including standard `tools` / `tool_calls`
- Discovers models using `GET …/v1/models`
- API key encrypted at rest using an AES-GCM key held by Android Keystore
- Text chat, Android speech recognition, and Android Text-to-Speech
- Opt-in continuous foreground listener with a **“Hey Assist”** wake phrase
- Boot receiver, partial wake lock, Doze whitelist setup, and persistent notification
- Android `VoiceInteractionService`, so root setup can make Assist the default system assistant
- Root tools for shell, files, apps, URLs, touch/swipe/text/key input, volume, screenshots, reboot, recovery, bootloader, and shutdown
- HTTPS, LAN HTTP, and user-installed CA support (intended for self-hosted APIs)

## Build

Requirements:

- JDK 17
- Android SDK Platform 35 and Build Tools 35.x

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The project never embeds an endpoint credential. Enter one in the app after installation, or leave the API-key field empty when the server is open.

## First-time setup

1. Open **Assist**.
2. Enter a base URL such as `https://host.example/v1` (a full `/v1/chat/completions` URL also works).
3. Enter the optional key and model, then tap **Test & load models**.
4. Grant Magisk/KernelSU root when requested.
5. Tap **Configure rooted headless mode**. This grants supported runtime/app-op permissions, exempts Assist from Doze, and registers its voice-interaction service as the default assistant.
6. Turn on **Always listen** and tap **Start voice**.
7. Say: “Hey Assist, what is my battery level?”
8. Enable **Allow the AI to execute root tools** only after reviewing the warning.

If the ROM does not accept the default-assistant role automatically, select Assist manually in **Settings → Apps → Default apps → Digital assistant app**.

## Root setup performed by the app

The setup button executes a fixed `su` script that attempts to:

- grant microphone, notification, and secure-settings permissions;
- allow background app operations;
- add the package to the Doze whitelist;
- allow overlay app-op access;
- add Assist as the `android.app.role.ASSISTANT` role holder; and
- set the secure assistant/voice-interaction component as a compatibility fallback.

ROM commands differ, so individual best-effort steps can be ignored by Android even when the final script exits successfully.

## Tool-call safety

Root tool execution has three layers:

1. It is disabled by default and requires an explicit warning confirmation in the UI.
2. The agent system prompt requires confirmation for destructive, privacy-sensitive, account, purchase, messaging, power, and security actions unless the latest request explicitly names the exact action.
3. Every root command returns a real exit code/output to the model; the prompt forbids claiming success without a successful result.

Once root tools are enabled, `run_shell` is deliberately unrestricted. This matches the dedicated-device use case, but it is not a security sandbox. A malicious or compromised endpoint can generate dangerous tool calls. Disable the switch before using an untrusted service.

## Android limitations

- Assist uses Android's installed `SpeechRecognizer`; recognition may be cloud-backed and ROM vendors may stop it despite foreground/Doze configuration.
- A true low-power DSP hotword detector is privileged OEM functionality. This app approximates it with repeated speech-recognition sessions and therefore uses more battery.
- Android 14+ can block microphone foreground-service promotion when started directly from boot. Assist keeps the service available and reports **“Tap to activate the microphone after boot”**; tapping the notification and pressing **Start voice** satisfies the foreground requirement. Some rooted ROMs/default-assistant configurations exempt it.
- The persistent listener is suitable for a powered, dedicated home device—not normal phone battery use.
- Speech and tool requests are sent to the endpoint you configure. File contents and device data are only sent when included in conversation/tool results.

## Supported API shape

Assist uses the OpenAI Chat Completions protocol:

```json
{
  "model": "your-model",
  "messages": [{ "role": "user", "content": "…" }],
  "tools": [{ "type": "function", "function": { "name": "…" } }],
  "tool_choice": "auto"
}
```

The selected model/server must support Chat Completions tool calling. An endpoint that only implements the newer Responses API is not compatible unless it also provides the Chat Completions compatibility route.

## Tests

```bash
./gradlew testDebugUnitTest
```

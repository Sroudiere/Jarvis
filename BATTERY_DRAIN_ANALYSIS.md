# Battery Drain Analysis — Jarvis Wake Word Listening

## Overview

Jarvis runs a **foreground service with always-on microphone** to listen for the "Jarvis" wake word
via Picovoice Porcupine. This document breaks down estimated battery consumption for that
continuous-listening phase, as well as during active use (STT → LLM → TTS).

---

## Architecture Summary (relevant to power)

| Component | Details |
|-----------|---------|
| Wake word engine | Picovoice Porcupine v3.0.2 (on-device CNN) |
| Audio source | `AudioSource.VOICE_RECOGNITION`, 16 kHz PCM mono |
| Frame size | ~512 samples → frame every **~32 ms** |
| Processing thread | Kotlin IO dispatcher, blocking `AudioRecord.read()` loop |
| Service type | Android Foreground Service (`foregroundServiceType = microphone`) |
| WakeLock | Declared in manifest but **never acquired in code** |
| Screen-off behaviour | CPU can partial-sleep between AudioRecord frames |

---

## Power Budget — Always-On Listening Phase

> Reference device: mid-range Android, 4 000 mAh battery, ~3.7 V nominal.

| Subsystem | Current draw | Notes |
|-----------|-------------|-------|
| Microphone / ADC | ~2–3 mA | `VOICE_RECOGNITION` source, 16 kHz |
| Audio DSP / codec path | ~3–6 mA | Main CPU must handle it — no hardware DSP offload |
| CPU (Porcupine inference) | ~3–8 mA | ~31 frames/s, lightweight CNN; runs on efficiency cores |
| Foreground service overhead | ~1–2 mA | Persistent notification, binder calls |
| Android baseline (screen off) | ~5–8 mA | Background OS, radio idle, sensor hub |
| **Total — listening only** | **~14–27 mA** | Wide range due to device variation |

### Battery life estimate

| Scenario | Drain rate | 4 000 mAh runtime |
|----------|------------|-------------------|
| Optimistic (efficiency cores, good DSP path) | ~14 mA | **~285 h (~12 days)** |
| Realistic mid-range device | ~20 mA | **~200 h (~8 days)** |
| Pessimistic (keeps big cores active) | ~27 mA | **~148 h (~6 days)** |

**Percentage per hour:** ~0.35 %/h (best) to ~0.68 %/h (worst).

---

## Power Budget — Active Interaction Phase

Triggered on wake word detection, typically lasting **10–30 seconds** per interaction.

| Step | Additional draw | Duration |
|------|----------------|---------|
| TTS "Oui ?" playback | +10–20 mA (speaker) | ~1 s |
| Cloud STT (SpeechRecognizer) | +30–60 mA (WiFi/4G radio + CPU) | ~2–8 s |
| OpenAI API call (LLM) | +30–60 mA (network, up to 8 tool rounds) | ~5–30 s |
| TTS response playback | +10–20 mA | ~2–10 s |

These spikes are brief and negligible in daily total unless the assistant is invoked dozens of times
per hour.

---

## Key Risks & Issues Found in the Code

### 1. No WakeLock acquired (medium risk)
`WAKE_LOCK` is declared in `AndroidManifest.xml` but never acquired in code. Without it, the CPU
may drop to a deeper sleep state between `AudioRecord.read()` calls on some devices. This can cause
**missed wake words** rather than extra drain. The fix is to hold a partial WakeLock while the
detection loop is running.

```kotlin
// WakeWordDetector.kt — recommended addition
private var wakeLock: PowerManager.WakeLock? = null

fun start() {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:WakeWordDetector")
    wakeLock?.acquire()
    // ... existing start logic
}

fun stop() {
    wakeLock?.release()
    wakeLock = null
    // ... existing stop logic
}
```

### 2. Continuous logging in the hot path (low risk)
Every 62 frames (~2 s) an amplitude value is logged:
```kotlin
// WakeWordDetector.kt — runs forever on IO thread
if (frameCount % 62 == 0) {
    Log.d(TAG, "Audio level (max amp): $maxAmp")
}
```
Log calls involve string allocation and I/O. In release builds this is negligible, but it should be
guarded with `BuildConfig.DEBUG` to avoid any overhead in production.

### 3. No battery-aware behaviour (low–medium risk)
There is no adaptation to battery level (e.g. `BatteryManager.BATTERY_PROPERTY_CAPACITY`). On a
low-battery device the app keeps draining at the same rate. Consider reducing sensitivity or pausing
the service below a threshold (e.g. 10 %).

### 4. `START_NOT_STICKY` — service won't restart after kill
```kotlin
return START_NOT_STICKY
```
If the OS kills the service under memory pressure, wake word listening silently stops. Users won't
notice until they manually reopen the app. Consider `START_STICKY` or a JobScheduler restart if
the service is killed.

---

## Comparison with Known Always-On Wake Word Systems

| System | Mechanism | Estimated drain |
|--------|-----------|----------------|
| Google Assistant (Pixel) | Dedicated Tensor DSP core | ~0.1–0.2 %/h |
| Amazon Alexa (Fire tablet) | Dedicated wake word DSP | ~0.15–0.3 %/h |
| **Jarvis (this app)** | Main CPU + Porcupine (no offload DSP) | **~0.35–0.68 %/h** |
| Porcupine on a generic Android | Main CPU | ~0.3–0.7 %/h (aligns with Picovoice benchmarks) |

Jarvis is **2–4× more draining than hardware-offloaded solutions** because it relies on the main
CPU. This is the fundamental limitation of not having a DSP co-processor — it is a hardware
constraint, not a code bug.

---

## Recommendations (prioritised)

1. **Acquire a WakeLock** in `WakeWordDetector.start()` to ensure reliable detection on all
   devices. (Also prevents the battery-vs-reliability trade-off going the wrong direction.)

2. **Guard debug logs** with `if (BuildConfig.DEBUG)` to remove string allocations from the
   release hot path.

3. **Add battery-level monitoring** and pause the service or lower sensitivity when battery < 10 %.

4. **Change to `START_STICKY`** so the service restarts after OS kills.

5. **Long term:** Investigate Android's `SoundTrigger` API / hardware recognition session, which
   allows wake word detection to be offloaded to the audio DSP on supported devices, reducing drain
   by ~60–80 %.

---

## How to Measure on a Real Device

```bash
# ADB battery stats (reset and measure over N minutes of idle listening)
adb shell dumpsys batterystats --reset
# ... let the app run for 30+ minutes ...
adb shell dumpsys batterystats --charged com.jarvis.app

# Or use Android Studio's Energy Profiler (CPU + Network + Location tracks)
```

For a quick sanity check, note the battery percentage before and after 1 hour of idle listening and
compare against the estimate table above.

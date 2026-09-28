#!/usr/bin/env python3
"""Original deterministic AstraEngine cinematic sound synthesis, no sampled recordings.

Developer-only dependencies: Python 3, NumPy and ffmpeg with libvorbis.
Run from any directory: python3 tools/audio/synthesize_solar.py
The shipped engine reads only generated Ogg files; it never invokes this script.

All oscillators, periodic filtered noise and envelopes are authored here. Fixed
seeds reproduce the PCM source; encoded Ogg metadata/serials may vary by ffmpeg.
Peak normalization targets 0.76 (-2.38 dBFS) before Vorbis to leave codec headroom.
The effects are cinematic sonification, not a model of vacuum sound propagation.
"""
from pathlib import Path
import argparse
import json
import subprocess
import tempfile
import wave

import numpy as np

RATE = 44_100
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "src/main/resources/assets/astraengine/sounds"


def noise(duration, low, high, seed):
    """Periodic band-limited noise; loop endpoints approach the same continuous waveform."""
    count = int(RATE * duration)
    rng = np.random.default_rng(seed)
    frequencies = np.fft.rfftfreq(count, 1 / RATE)
    phases = rng.uniform(0, np.pi * 2, len(frequencies))
    spectrum = np.exp(1j * phases)
    band = (1 - np.exp(-np.square(frequencies / low))) * np.exp(-np.square(frequencies / high))
    spectrum *= band / np.sqrt(np.maximum(frequencies, 1))
    result = np.fft.irfft(spectrum, n=count)
    return result / max(float(np.std(result)), 1e-8)


def tone(t, frequency, phase=0):
    return np.sin(2 * np.pi * frequency * t + phase)


def smooth(t, duration):
    x = np.clip(t / duration, 0, 1)
    return x * x * (3 - 2 * x)


def synthesize(name):
    duration = 4 if name == "solar_supernova" else 8
    t = np.arange(int(RATE * duration), dtype=np.float64) / RATE
    low = noise(duration, 24, 260, 0xA57A)
    air = noise(duration, 200, 1600, 0x50A1)
    if name == "solar_tension":
        # Integer cycles over eight seconds keep the quiet tension bed seamless.
        pulse = 0.65 + 0.35 * np.cos(2 * np.pi * t / 8)
        bed = (0.4 * tone(t, 55) + 0.17 * tone(t, 82.5)
               + 0.10 * tone(t, 110.125) + low * 0.085)
        left = bed * pulse + air * 0.012
        right = bed * pulse + 0.045 * tone(t, 110.25, 0.3) + air * 0.009
    elif name == "solar_collapse":
        pulse = 0.72 + 0.28 * np.cos(2 * np.pi * t * 0.5)
        bed = (0.34 * tone(t, 40) + 0.18 * tone(t, 60.125)
               + 0.10 * tone(t, 80) + low * 0.14) * pulse
        left = bed + 0.05 * tone(t, 161.125, 0.4) + air * 0.025
        right = bed + 0.05 * tone(t, 160.875, -0.4) + air * 0.02
    elif name == "solar_supernova":
        # A soft attack, falling bass transient, warm noisy impact, then a long tail.
        attack = smooth(t, 0.028)
        bass_phase = 2 * np.pi * (35 * t + 60 * 0.35 * (1 - np.exp(-t / 0.35)))
        bass = np.sin(bass_phase) * np.exp(-t / 0.95)
        # A restrained 195-to-105 Hz body and short 400-to-2800 Hz crack retain
        # the impact on laptop speakers that cannot reproduce the sub-bass.
        body_phase = 2 * np.pi * (105 * t + 90 * 0.18 * (1 - np.exp(-t / 0.18)))
        body = 0.28 * np.sin(body_phase) * np.exp(-t / 0.48)
        body += 0.055 * np.sin(body_phase * 2) * np.exp(-t / 0.22)
        crack = noise(duration, 400, 2800, 0xB10A) * 0.075 * np.exp(-t / 0.16)
        impact = (low * 0.16 + air * 0.035) * np.exp(-t / 0.6)
        tail = low * 0.11 * np.exp(-t / 1.6)
        end = 1 - smooth(np.maximum(t - 3.35, 0), 0.65)
        bed = attack * (bass * 0.65 + body + crack + impact + tail) * end
        left = bed
        right = bed * 0.97 + attack * 0.018 * air * np.exp(-t / 1.2) * end
    else:
        # Decay is driven by server phase age in SolarAudioEnvelope, including pause/rejoin.
        bed = low * 0.21 + 0.16 * tone(t, 38) + 0.07 * tone(t, 57.125)
        left = bed + air * 0.017
        right = bed + 0.03 * tone(t, 76.125, 0.8) + air * 0.012
    signal = np.column_stack((left, right))
    signal -= np.mean(signal, axis=0)
    peak = float(np.max(np.abs(signal)))
    signal *= 0.76 / max(peak, 1e-8)
    return signal


def main():
    names = ("solar_tension", "solar_collapse", "solar_supernova", "solar_rumble")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="*", help="Optional cue names; omit to regenerate all four")
    requested = parser.parse_args().names or names
    if any(name not in names for name in requested):
        parser.error("Unknown cue; expected one of " + ", ".join(names))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    for name in requested:
        signal = synthesize(name)
        pcm = np.rint(np.clip(signal, -1, 1) * 32767).astype("<i2")
        with tempfile.TemporaryDirectory(prefix="astra-solar-audio-") as temporary:
            source = Path(temporary) / (name + ".wav")
            with wave.open(str(source), "wb") as output:
                output.setnchannels(2)
                output.setsampwidth(2)
                output.setframerate(RATE)
                output.writeframes(pcm.tobytes())
            subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source),
                            "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "5",
                            "-metadata", "artist=AstraEngine",
                            "-metadata", "comment=Original deterministic procedural synthesis; tools/audio/synthesize_solar.py",
                            str(OUTPUT / (name + ".ogg"))], check=True)
        decoded = subprocess.run(["ffmpeg", "-v", "error", "-i", str(OUTPUT / (name + ".ogg")),
                                  "-f", "f32le", "-acodec", "pcm_f32le", "-"],
                                 check=True, capture_output=True).stdout
        samples = np.frombuffer(decoded, dtype="<f4")
        decoded_peak = float(np.max(np.abs(samples)))
        if not np.all(np.isfinite(samples)) or decoded_peak >= 0.98:
            raise RuntimeError(f"Decoded {name} exceeded the reserved peak headroom: {decoded_peak}")
        print(json.dumps({"name": name, "seconds": len(signal) / RATE, "sample_rate": RATE,
                          "channels": 2, "decoded_peak": decoded_peak,
                          "decoded_rms": float(np.sqrt(np.mean(samples * samples))),
                          "bytes": (OUTPUT / (name + ".ogg")).stat().st_size}))


if __name__ == "__main__":
    main()

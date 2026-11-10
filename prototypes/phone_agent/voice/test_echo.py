"""Offline native AEC and playback lifecycle checks; no calls or model requests."""
import array
import asyncio
from dataclasses import replace
import math
from pathlib import Path
import unittest
from unittest.mock import patch
import wave

from livekit.agents.voice import io

from .config import ConfigurationError, VoiceConfig
from .diagnostics import AudioDiagnostics
from .echo import EchoInput, EchoOutput, EchoProcessor, attach_echo, pcm_frame
from .resilience import room_options


class Sink(io.AudioOutput):
    def __init__(self):
        super().__init__(label="test", sample_rate=16000,
                         capabilities=io.AudioOutputCapabilities(pause=True))
        self.data = bytearray()
        self.paused = False

    async def capture_frame(self, frame):
        await super().capture_frame(frame)
        self.data.extend(bytes(frame.data))

    def flush(self):
        super().flush()
        if self._pending_playback_count:
            self.on_playback_finished(playback_position=len(self.data) / 32000, interrupted=False)

    def clear_buffer(self):
        self.on_playback_finished(playback_position=len(self.data) / 32000, interrupted=True)

    def pause(self):
        self.paused = True

    def resume(self):
        self.paused = False


class EchoTest(unittest.IsolatedAsyncioTestCase):
    def test_native_delayed_speech_echo_is_reduced_and_double_talk_retains_energy(self):
        folder = Path(__file__).with_name("audio")
        def samples(name):
            with wave.open(str(folder / name)) as audio:
                return list(array.array("h", audio.readframes(audio.getnframes())))
        far, near = samples("please_repeat.wav"), samples("call_end.wav")
        now = [0.0]
        processor = EchoProcessor(200, clock=lambda: now[0])
        history = []
        echo_energy = residual_energy = near_energy = mixed_output_energy = 0
        for k in range(2200):
            now[0] = k * 0.01
            reference = [int(0.6 * far[(k * 160 + j) % len(far)]) for j in range(160)]
            history.extend(reference)
            processor.reference(pcm_frame(array.array("h", reference).tobytes()))
            local = [int(0.6 * near[((k - 1300) * 160 + j) % len(near)])
                     if k >= 1300 else 0 for j in range(160)]
            echo = [int(0.45 * history[(k - 22) * 160 + j]) if k >= 22 else 0
                    for j in range(160)]
            mixed = [a + b for a, b in zip(local, echo)]
            output = processor.process(pcm_frame(array.array("h", mixed).tobytes()))
            if 700 <= k < 1200:
                echo_energy += sum(x * x for x in echo)
                residual_energy += sum(x * x for x in output.data)
            if 1400 <= k < 2100:
                near_energy += sum(x * x for x in local)
                mixed_output_energy += sum(x * x for x in output.data)
        attenuation_db = 10 * math.log10(echo_energy / max(1, residual_energy))
        near_rms_ratio = math.sqrt(mixed_output_energy / near_energy)
        self.assertGreater(attenuation_db, 10)
        self.assertGreater(near_rms_ratio, 0.4)
        self.assertLess(near_rms_ratio, 1.5)
        self.assertEqual(0, processor.errors)
        # Energy retention is a smoke check, not intelligibility or a phone quality score.

    def test_processing_failure_passes_original_caller_audio_without_retry(self):
        processor = EchoProcessor()
        original = pcm_frame(array.array("h", [1200] * 160).tobytes())
        with patch.object(processor.apm, "process_stream", side_effect=RuntimeError("test")):
            self.assertIs(original, processor.process(original))
            self.assertIs(original, processor.process(original))
        self.assertTrue(processor.failed)
        self.assertEqual(1, processor.errors)

    def test_reference_is_bounded_and_clear_discards_pcm(self):
        processor = EchoProcessor()
        for _ in range(250):
            processor.reference(pcm_frame(bytes(320)))
        self.assertEqual(200, len(processor.references))
        self.assertEqual(50, processor.reference_overflows)
        processor.clear()
        self.assertFalse(processor.references)
        self.assertIsNone(processor.last_reference)

    def test_configuration_is_opt_in_and_rejects_invalid_delay(self):
        self.assertFalse(VoiceConfig.from_env({}).echo_cancellation)
        for value in ("-1", "501", "invalid"):
            with self.assertRaises(ConfigurationError):
                VoiceConfig.from_env({"PHONE_ECHO_DELAY_MS": value})
        with self.assertRaises(ConfigurationError):
            VoiceConfig.from_env({"PHONE_ECHO_CANCELLATION": "maybe"})
        config = replace(VoiceConfig(), echo_cancellation=True)
        options = room_options(config)
        self.assertEqual(16000, options.audio_input.sample_rate)
        self.assertEqual(16000, options.audio_output.sample_rate)

    async def test_pacing_preserves_output_pcm_and_playout_event(self):
        now = [50.0]
        async def sleep(seconds):
            now[0] += seconds
        processor = EchoProcessor(clock=lambda: now[0])
        sink = Sink()
        output = EchoOutput(sink, processor, clock=lambda: now[0], sleep=sleep)
        data = array.array("h", [1000] * 800).tobytes()
        await output.capture_frame(pcm_frame(data))
        output.flush()
        event = await asyncio.wait_for(output.wait_for_playout(), 1)
        self.assertEqual(data, bytes(sink.data))
        self.assertGreaterEqual(now[0], 50.039)
        self.assertFalse(event.interrupted)
        self.assertEqual(5, len(processor.references))

    async def test_interrupt_while_paused_unblocks_capture_without_stale_audio(self):
        sink = Sink()
        output = EchoOutput(sink, EchoProcessor())
        await output.capture_frame(pcm_frame(bytes(320)))
        output.pause()
        task = asyncio.create_task(output.capture_frame(pcm_frame(bytes(320))))
        await asyncio.sleep(0)
        self.assertFalse(task.done())
        output.clear_buffer()
        await asyncio.wait_for(task, 1)
        output.flush()
        self.assertEqual(320, len(sink.data))
        self.assertTrue((await output.wait_for_playout()).interrupted)

    async def test_input_processing_precedes_all_session_consumers(self):
        class Input(io.AudioInput):
            async def __anext__(self):
                return pcm_frame(bytes(1600))
        from types import SimpleNamespace
        session = SimpleNamespace(input=SimpleNamespace(audio=Input(label="test")),
                                  output=SimpleNamespace(audio=Sink()))
        diagnostics = AudioDiagnostics()
        processor = attach_echo(session, replace(VoiceConfig(), echo_cancellation=True), diagnostics)
        self.assertIsInstance(session.input.audio, EchoInput)
        frame = await session.input.audio.__anext__()
        self.assertEqual(800, frame.samples_per_channel)
        self.assertEqual(5, processor.input_frames)
        self.assertTrue(diagnostics.snapshot()["echoCancellation"]["active"])


if __name__ == "__main__":
    unittest.main()

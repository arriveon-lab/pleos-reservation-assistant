"""Optional decoded-PCM level counters before and after the local NC filter.

Only aggregates are retained. The two streams are not sample-aligned recordings.
https://docs.livekit.io/reference/python/livekit/rtc/audio_stream.html
"""
import asyncio
import math

from livekit import rtc
from livekit.agents.voice import io


class AudioLevel:
    def __init__(self):
        self.frames = self.samples = self.energy = self.peak = self.above_threshold = 0

    def observe(self, frame):
        self.frames += 1
        self.samples += len(frame.data)
        peak = max((abs(value) for value in frame.data), default=0)
        self.peak = max(self.peak, peak)
        self.energy += sum(value * value for value in frame.data)
        self.above_threshold += peak > 64  # A level indicator, not speech detection.

    def snapshot(self):
        return {"frames": self.frames, "samples": self.samples,
                "peakAmplitude": round(self.peak / 32768, 6) if self.samples else None,
                "rmsAmplitude": round(math.sqrt(self.energy / self.samples) / 32768, 6)
                if self.samples else None, "framesAbove64Pcm": self.above_threshold}


class MeteredInput(io.AudioInput):
    def __init__(self, source, level):
        super().__init__(label="Post-NC level probe", source=source)
        self.level = level

    async def __anext__(self):
        frame = await self.source.__anext__()
        self.level.observe(frame)
        return frame


class InputProbe:
    def __init__(self, session, participant, stream_factory=rtc.AudioStream.from_participant):
        self.raw, self.post_noise = AudioLevel(), AudioLevel()
        self.errors = 0
        self.stream = self.task = None
        self.closed = False
        if session.input.audio is not None:
            session.input.audio = MeteredInput(session.input.audio, self.post_noise)
        try:
            self.stream = stream_factory(participant=participant,
                track_source=rtc.TrackSource.SOURCE_MICROPHONE, sample_rate=16000,
                num_channels=1, frame_size_ms=50, capacity=4, noise_cancellation=None)
            self.task = asyncio.create_task(self._observe())
        except Exception:
            self.errors += 1  # A diagnostic failure must not close the call.

    async def _observe(self):
        try:
            async for event in self.stream:
                self.raw.observe(event.frame)
        except asyncio.CancelledError:
            raise
        except Exception:
            self.errors += 1

    async def close(self):
        if self.closed:
            return
        self.closed = True
        if self.task is not None:
            self.task.cancel()
            await asyncio.gather(self.task, return_exceptions=True)
        if self.stream is not None:
            try:
                await asyncio.wait_for(self.stream.aclose(), 2)
            except Exception:
                self.errors += 1

    def snapshot(self):
        return {"enabled": True, "errors": self.errors,
                "decodedBeforeLocalNC": self.raw.snapshot(),
                "afterLocalNCBeforeAEC": self.post_noise.snapshot()}

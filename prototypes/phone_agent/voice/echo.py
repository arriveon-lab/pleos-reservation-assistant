"""Experimental SIP echo processing with a paced, call-local playback reference.

The reference is the PCM submitted to RoomIO, not a recording from the far handset.
The configured delay is an initial estimate, not a measured telephone round trip.
See https://docs.livekit.io/reference/python/livekit/rtc/apm.html (10 ms frames).
"""
import asyncio
from collections import deque
import time

from livekit import rtc
from livekit.agents.voice import io


RATE = 16000
SAMPLES = RATE // 100
BYTES = SAMPLES * 2


def pcm_frame(data):
    return rtc.AudioFrame(data, RATE, 1, len(data) // 2)


class EchoProcessor:
    def __init__(self, delay_ms=200, clock=time.monotonic):
        self.clock = clock
        self.apm = rtc.AudioProcessingModule(echo_cancellation=True)
        self.apm.set_stream_delay_ms(delay_ms)
        self.delay_ms = delay_ms
        self.references = deque(maxlen=200)  # At most two seconds; never persisted.
        self.last_reference = None
        self.input_end = None
        self.failed = False
        self.input_frames = self.reference_frames = self.errors = 0
        self.reference_overflows = self.input_gaps = self.output_gaps = 0
        self.max_processing_ms = 0.0
        self.input_energy = self.output_energy = 0
        self.last_output = None

    def reference(self, frame):
        if self.failed:
            return
        now = self.clock()
        if self.last_output is not None and now - self.last_output > 0.1:
            self.output_gaps += 1
        self.last_output = now
        if len(self.references) == self.references.maxlen:
            self.reference_overflows += 1
        self.references.append((now, bytes(frame.data)))

    def _reference_at(self, timestamp):
        while self.references and self.references[0][0] <= timestamp + 0.005:
            self.last_reference = self.references.popleft()
        if self.last_reference and abs(timestamp - self.last_reference[0]) < 0.015:
            self.reference_frames += 1
            return self.last_reference[1]
        return bytes(BYTES)

    def process(self, frame):
        if self.failed:
            return frame  # A processing failure must not mute the caller.
        started = self.clock()
        try:
            if (frame.sample_rate != RATE or frame.num_channels != 1
                    or frame.samples_per_channel % SAMPLES):
                raise ValueError("AEC requires mono 16 kHz PCM in multiples of 10 ms")
            duration = frame.samples_per_channel / RATE
            start = started - duration
            if self.input_end is not None:
                if abs(start - self.input_end) < 0.1:
                    start = self.input_end
                else:
                    self.input_gaps += 1
            self.input_end = start + duration
            original = bytes(frame.data)
            result = bytearray()
            for offset in range(0, len(original), BYTES):
                timestamp = start + offset / (RATE * 2)
                self.apm.process_reverse_stream(pcm_frame(self._reference_at(timestamp)))
                chunk = pcm_frame(original[offset:offset + BYTES])
                self.apm.process_stream(chunk)
                self.input_frames += 1
                self.output_energy += sum(value * value for value in chunk.data)
                result.extend(bytes(chunk.data))
            self.input_energy += sum(value * value for value in frame.data)
            return pcm_frame(result)
        except Exception:
            self.errors += 1
            self.failed = True
            self.clear()
            return frame
        finally:
            self.max_processing_ms = max(self.max_processing_ms, (self.clock() - started) * 1000)

    def clear(self):
        self.references.clear()
        self.last_reference = None

    def snapshot(self):
        return {"enabled": True, "active": not self.failed, "initialDelayMs": self.delay_ms,
                "processed10msFrames": self.input_frames, "reference10msFrames": self.reference_frames,
                "processingErrors": self.errors, "referenceOverflows": self.reference_overflows,
                "inputTimelineGaps": self.input_gaps, "outputSubmissionGapsOver100ms": self.output_gaps,
                "maxProcessingMs": round(self.max_processing_ms, 3),
                "outputToInputEnergyRatio": round(self.output_energy / self.input_energy, 4)
                if self.input_energy else None}


class EchoInput(io.AudioInput):
    def __init__(self, source, processor):
        super().__init__(label="SIP AEC input", source=source)
        self.processor = processor

    async def __anext__(self):
        return self.processor.process(await self.source.__anext__())


class EchoOutput(io.AudioOutput):
    """Keep the reference near actual submission, rather than ahead at TTS speed.

    RoomIO adds its own small playback buffer. AEC delay estimation must still cover
    that buffer and the telephone path. PCM is never changed on the output path.
    """
    def __init__(self, sink, processor, clock=time.monotonic, sleep=asyncio.sleep):
        super().__init__(label="SIP AEC reference", next_in_chain=sink, sample_rate=RATE,
                         capabilities=io.AudioOutputCapabilities(pause=True))
        self.processor, self.clock, self.sleep = processor, clock, sleep
        self.ready = asyncio.Event()
        self.ready.set()
        self.next_at = 0.0
        self.generation = 0

    async def capture_frame(self, frame):
        if frame.sample_rate != RATE or frame.num_channels != 1:
            raise ValueError("AEC output requires mono 16 kHz PCM")
        await super().capture_frame(frame)
        generation = self.generation
        data = bytes(frame.data)
        for offset in range(0, len(data), BYTES):
            await self.ready.wait()
            if generation != self.generation:
                return
            if self.next_at < self.clock() - 0.02:
                self.next_at = self.clock()
            await self.sleep(max(0, self.next_at - self.clock()))
            if generation != self.generation:
                return
            chunk = pcm_frame(data[offset:offset + BYTES])
            await self.next_in_chain.capture_frame(chunk)
            # A short tail is passed through unchanged but padded only in the reference.
            self.processor.reference(pcm_frame(bytes(chunk.data).ljust(BYTES, b"\0")))
            self.next_at += chunk.duration

    def flush(self):
        super().flush()
        self.next_in_chain.flush()
        self.processor.last_output = None

    def clear_buffer(self):
        self.generation += 1
        self.next_at = 0.0
        self.ready.set()
        self.next_in_chain.clear_buffer()
        self.processor.last_output = None
        # Already submitted frames may still return as echo; retain their reference.

    def pause(self):
        self.ready.clear()
        super().pause()

    def resume(self):
        self.next_at = self.clock()
        self.ready.set()
        super().resume()


def attach_echo(session, config, diagnostics):
    if not config.echo_cancellation:
        return None
    if session.input.audio is None or session.output.audio is None:
        raise ValueError("AEC requires both room audio directions")
    processor = EchoProcessor(config.echo_delay_ms)
    session.input.audio = EchoInput(session.input.audio, processor)
    session.output.audio = EchoOutput(session.output.audio, processor)
    diagnostics.echo = processor
    return processor

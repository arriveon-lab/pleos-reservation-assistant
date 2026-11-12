"""Soniox v6 agent text adapter for livekit-plugins-soniox 1.8.11.

The pinned plugin has no agent-text option. Keep its audio/token handling and
extend only the start request and serialized send queue. Text must enter through
RoomIO's *playback-synchronized* downstream output, never directly from the LLM.
https://docs.soniox.com/stt/rt/agent-text-input
"""
import asyncio
from dataclasses import asdict
from html.parser import HTMLParser
import json
import re

from livekit.agents import APIConnectOptions, APIConnectionError
from livekit.agents.voice import io
from livekit.plugins import soniox
from livekit.plugins.soniox.stt import SpeechStream


class AgentTextSTT(soniox.STT):
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        if self.model != "stt-rt-v6":
            raise ValueError("Agent text requires the v6 trial model")
        self.active_stream = None
        self.connected = asyncio.Event()
        self.counts = dict(connections=0, sentChunks=0, sentCharacters=0,
                           endedSegments=0, droppedChunks=0, sendErrors=0)

    def stream(self, *, language=None, conn_options=None):
        return AgentTextStream(self, conn_options=conn_options or APIConnectOptions())

    def send_agent_text(self, text, *, end=False):
        stream = self.active_stream
        if stream is None or stream._ws is None or stream._ws.closed:
            self.counts["droppedChunks"] += 1
            return
        stream.audio_queue.put_nowait(json.dumps(
            {"type": "agent_text", "text": text, "end_of_segment": end}, ensure_ascii=False))

    def snapshot(self):
        return {"enabled": True, "timing": "livekit_playback_synchronized", **self.counts}


class AgentTextStream(SpeechStream):
    async def _connect_ws(self):
        # Do not carry unsent text into a reconnect or another call.
        self._stt.active_stream = None
        self._stt.connected.clear()
        config = {key: value for key, value in asdict(self._stt._params).items()
                  if value is not None}
        config.update(audio_format="pcm_s16le", enable_endpoint_detection=True,
                      enable_agent_text_input=True)
        ws = await asyncio.wait_for(self._ensure_session().ws_connect(
            self._stt._base_url, headers={"Authorization": f"Bearer {self._stt._api_key}"},
            autoping=False), timeout=self._conn_options.timeout)
        try:
            await ws.send_str(json.dumps(config))
        except BaseException:
            await ws.close()
            raise
        self._reported_duration_ms = self._num_output_tokens = self._reported_output_tokens = 0
        self.audio_queue = asyncio.Queue()
        self._stt.active_stream = self
        self._stt.counts["connections"] += 1
        self._stt.connected.set()
        return ws

    async def _send_audio_task(self):
        while self._ws:
            data = await self.audio_queue.get()
            try:
                if isinstance(data, bytes):
                    await self._ws.send_bytes(data)
                else:
                    await self._ws.send_str(data)
                    message = json.loads(data)
                    if message.get("type") == "agent_text":
                        self._stt.counts["sentChunks"] += bool(message["text"])
                        self._stt.counts["sentCharacters"] += len(message["text"])
                        self._stt.counts["endedSegments"] += message["end_of_segment"]
            except asyncio.CancelledError:
                raise
            except Exception as error:
                self._stt.counts["sendErrors"] += 1
                raise APIConnectionError("Soniox stream write failed") from error

    async def _run(self):
        try:
            await super()._run()
        finally:
            if self._stt.active_stream is self:
                self._stt.active_stream = None
                self._stt.connected.clear()


class SpokenText(HTMLParser):
    """Strip even tags split across chunks; never send SSML as spoken text."""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.parts = []

    def handle_data(self, data):
        self.parts.append(data)

    def take(self, chunk):
        self.feed(chunk)
        result = "".join(self.parts)
        self.parts.clear()
        return re.sub(r"[\*_`~]", "", result)


class AgentTextOutput(io.TextOutput):
    def __init__(self, stt):
        super().__init__(label="SonioxAgentText", next_in_chain=None)
        self.stt = stt
        self.cleaner = SpokenText()
        self.started = False

    async def capture_text(self, text):
        # RoomIO delivers paced words, and cuts off the unheard tail on interrupt.
        clean = self.cleaner.take(str(text))
        for offset in range(0, len(clean), 256):
            self.stt.send_agent_text(clean[offset:offset + 256])
            self.started = True

    def flush(self):
        if self.started:
            self.stt.send_agent_text("", end=True)
        self.started = False
        self.cleaner = SpokenText()

"""Observe audio delivery without saving raw audio, words, or provider errors."""
import asyncio


class AudioDiagnostics:
    def __init__(self):
        self.frames = 0
        self.seconds = 0.0
        self.peak = 0
        self.interim = 0
        self.final = 0
        self.timeouts = 0
        self.errors = 0
        self.disconnect_reason = None

    def audio(self, frame):
        self.frames += 1
        self.seconds += frame.samples_per_channel / frame.sample_rate
        self.peak = max(self.peak, max((abs(value) for value in frame.data), default=0))

    def speech_event(self, event):
        kind = event.type.name
        self.interim += kind == "INTERIM_TRANSCRIPT"
        self.final += kind == "FINAL_TRANSCRIPT"

    def snapshot(self):
        return {"audioFrames": self.frames, "audioSeconds": round(self.seconds, 3),
                "peakAmplitude": round(self.peak / 32768, 4), "sttInterim": self.interim,
                "sttFinal": self.final, "transcriptionTimeouts": self.timeouts,
                "modelErrors": self.errors, "rtcDisconnectReason": self.disconnect_reason}


async def reprompt_if_unheard(session, diagnostics, ended, sleep=asyncio.sleep):
    """One bounded reminder after the greeting; no reservation inference or redial."""
    for _ in range(12):
        await sleep(1)
        if ended() or diagnostics.final:
            return
    if not ended() and session.user_state != "speaking":
        await session.say("말씀을 아직 확인하지 못했어요. 다시 한 번 말씀해 주시겠어요?",
                          allow_interruptions=True)

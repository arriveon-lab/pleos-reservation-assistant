"""Observe audio delivery without saving raw audio, words, or provider errors."""
import asyncio
import time


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
        self.interrupted_messages = 0
        self.component_errors = {"stt": 0, "llm": 0, "tts": 0, "other": 0}
        self.recoveries = 0
        self.fallback_failures = 0
        self.holds = 0
        self.idle_prompts = 0
        self.automated_answers = 0
        self.false_interruptions = 0
        self.user_stopped_at = None
        self.response_delays = []

    def user_state(self, event):
        if event.old_state == "speaking" and event.new_state == "listening":
            self.user_stopped_at = time.monotonic()

    def agent_state(self, event):
        if event.new_state == "speaking" and self.user_stopped_at is not None:
            self.response_delays.append(round(time.monotonic() - self.user_stopped_at, 3))
            self.response_delays = self.response_delays[-30:]
            self.user_stopped_at = None

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
                "modelErrors": self.errors, "rtcDisconnectReason": self.disconnect_reason,
                "interruptedAgentMessages": self.interrupted_messages,
                "componentErrors": dict(self.component_errors), "recoveryAttempts": self.recoveries,
                "fallbackAudioFailures": self.fallback_failures, "holdRequests": self.holds,
                "idlePrompts": self.idle_prompts, "automatedAnswers": self.automated_answers,
                "falseInterruptions": self.false_interruptions,
                "responseDelaySeconds": list(self.response_delays)}


async def reprompt_if_unheard(session, diagnostics, ended, sleep=asyncio.sleep):
    """One bounded reminder after the greeting; no reservation inference or redial."""
    for _ in range(12):
        await sleep(1)
        if ended() or diagnostics.final:
            return
    if not ended() and session.user_state != "speaking":
        await session.say("말씀을 아직 확인하지 못했어요. 다시 한 번 말씀해 주시겠어요?",
                          allow_interruptions=True)

"""Bounded call supervision; no extra model providers, redial, or booking decisions."""
import asyncio
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import time
import wave

from livekit import rtc
from livekit.agents import StopResponse, room_io
from livekit.plugins import noise_cancellation

from ..service import DomainError


AUDIO_DIR = Path(__file__).with_name("audio")
END_TEXT = "이번 통화는 여기서 마치겠습니다. 예약 결과는 서비스에서 확인해 주세요."
REPEAT_TEXT = "말씀을 처리하지 못했어요. 다시 한 번 말씀해 주시겠어요?"


def opening_text(context):
    name = context.get("reservationName")
    if isinstance(name, str) and name.strip():
        return f"안녕하세요, {name} 님의 AI 비서입니다."
    return "안녕하세요, AI 예약 비서입니다."


def initial_reply_instructions(context):
    if context.get("purpose") == "CALLBACK":
        return ("소개는 이미 했다. 식당에서 걸려온 회신 전화다. 새 예약을 요청하지 않는다. "
                "예약을 식별하기 위해 예약자 성함을 묻는 짧은 질문 하나만 한다.")
    return ("소개는 이미 했다. 이번 통화 목적에 맞는 질문 하나만 한다. "
            "새 예약이면 날짜·시간·인원을 포함해 예약 가능한지 한 문장으로 묻는다.")


def room_options(config):
    return room_io.RoomOptions(audio_input=room_io.AudioInputOptions(
        sample_rate=16000 if config.echo_cancellation else 24000,
        noise_cancellation=noise_cancellation.NC() if config.noise_suppression else None),
        audio_output=room_io.AudioOutputOptions(
            sample_rate=16000 if config.echo_cancellation else 24000))


def turn_options(config):
    return {"interruption": {"mode": config.interruption_mode,
                "min_duration": 0.5, "min_words": 0,
                "discard_audio_if_uninterruptible": False,
                "resume_false_interruption": True, "false_interruption_timeout": 2.0,
                "backchannel_boundary": (1.0, 1.0)},
            "endpointing": {"min_delay": 0.5, "max_delay": 3.0},
            # Do not generate a reply before the hold/voicemail gate has run.
            "preemptive_generation": {"enabled": False}}


async def fallback_audio(name):
    """Small bundled PCM assets; play without a TTS service or media decoder."""
    with wave.open(str(AUDIO_DIR / (name + ".wav")), "rb") as audio:
        rate, channels = audio.getframerate(), audio.getnchannels()
        if audio.getsampwidth() != 2 or channels != 1 or rate != 16000:
            raise ValueError("Unsupported fallback audio format")
        while data := audio.readframes(rate // 50):
            yield rtc.AudioFrame(data, rate, channels, len(data) // 2)


class SerializedStore:
    """Keep synchronous SQLite work off the audio loop, preserving event/tool order."""
    def __init__(self):
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="phone-store")
        self.failed = False

    def enqueue(self, operation):
        future = asyncio.get_running_loop().run_in_executor(self.executor, operation)
        def observed(done):
            if not done.cancelled() and done.exception() is not None:
                self.failed = True
        future.add_done_callback(observed)
        return future

    async def run(self, operation):
        try:
            return await asyncio.get_running_loop().run_in_executor(self.executor, operation)
        except DomainError:
            # A rejected booking condition is not a storage outage.
            raise
        except Exception:
            self.failed = True
            raise

    async def close(self, operation):
        try:
            await self.run(operation)
        finally:
            self.executor.shutdown(wait=False)


def control_intent(text):
    """Only whole hold requests are gated; mixed corrections must reach the agent."""
    compact = re.sub(r"[\s,.!?~…。]+", "", text)
    if re.fullmatch(r"(?:네|예|아|어)?(?:잠시만요|잠깐만요|잠시만기다려주세요|잠깐만기다려주세요)"
                    r"(?:(?:제가)?(?:확인해볼게요|확인할게요|확인하고올게요))?", compact):
        return "hold"
    if re.fullmatch(r"(?:네|예)?(?:확인해볼게요|확인하고올게요|잠시기다려주세요)", compact):
        return "hold"
    if (not any(x in compact for x in ("아니", "말고", "않", "마세요"))
            and (("음성사서함" in compact and any(x in compact for x in ("연결", "메시지")))
                 or ("삐소리" in compact and "메시지" in compact)
                 or ("번호를누르" in compact and any(x in compact for x in ("안내", "연결", "예약"))))):
        return "automated_answer"
    if re.fullmatch(r"(?:죄송하지만|미안하지만)?(?:전화끊어주세요|그만전화하세요|통화못해요|통화어려워요|"
                    r"지금바빠요|나중에전화해주세요)", compact):
        return "callee_unavailable"
    return "conversation"


class CallSupervisor:
    def __init__(self, session, close_call, diagnostics, ended, store=None, clock=time.monotonic):
        self.session, self.close_call, self.diagnostics = session, close_call, diagnostics
        self.ended, self.store, self.clock = ended, store, clock
        self.last_activity = clock()
        self.hold_until = None
        self.idle_prompts = 0
        self.completed_turns = 0
        self.recovery_used = False
        self.recovering = False
        self.closing = False
        self.terminal = False
        self.tasks = set()

    def spawn(self, coroutine):
        task = asyncio.create_task(coroutine)
        self.tasks.add(task)
        def done(item):
            self.tasks.discard(item)
            if not item.cancelled() and item.exception() is not None and not self.ended():
                self.spawn(self.close_call("supervision_error"))
        task.add_done_callback(done)
        return task

    def activity(self):
        self.last_activity = self.clock()

    async def user_turn(self, text):
        self.activity()
        self.idle_prompts = 0
        intent = control_intent(text)
        if intent == "hold":
            if self.hold_until is None:
                self.hold_until = self.clock() + 20
                self.diagnostics.holds += 1
                await self.session.say("네, 기다리겠습니다.", allow_interruptions=True)
            raise StopResponse()
        self.hold_until = None
        if intent != "conversation":
            self.diagnostics.automated_answers += intent == "automated_answer"
            await self.finish(intent)
            raise StopResponse()
        if self.closing or self.terminal:
            raise StopResponse()

    def assistant_message(self, item):
        self.activity()
        if not item.interrupted:
            self.completed_turns += 1
        # Interrupted fragments don't spend the completed-turn allowance.
        if self.completed_turns >= 12 and not self.closing:
            self.spawn(self.finish("turn_limit"))

    def error(self, event):
        error = event.error
        kind = getattr(error, "type", "unknown")
        component = {"stt_error": "stt", "llm_error": "llm", "tts_error": "tts"}.get(kind, "other")
        self.diagnostics.errors += 1
        self.diagnostics.component_errors[component] += 1
        if self.ended() or self.terminal:
            return
        if error.recoverable:
            return  # Let the bounded SDK retry finish; never stack application retries.
        error.recoverable = True  # The supervisor now owns this failure's cleanup.
        if self.closing or component not in ("stt", "llm") or self.recovery_used or self.recovering:
            self.spawn(self.finish(component + "_error"))
            return
        self.recovery_used = self.recovering = True
        self.diagnostics.recoveries += 1
        self.spawn(self.recover(component))

    async def recover(self, component):
        try:
            await asyncio.wait_for(self.session.interrupt(force=True), 2)
            if component == "stt":
                self.session.update_agent(self.session.current_agent)
                await asyncio.wait_for(self.session.wait_for_idle(), 4)
            if self.terminal or self.ended():
                return
            # No regeneration of a partial response or previously executed tools.
            await asyncio.wait_for(self.session.say(REPEAT_TEXT, audio=fallback_audio("please_repeat"),
                                                   allow_interruptions=True), 8)
            self.activity()
        except Exception:
            await self.finish("recovery_failed")
        finally:
            self.recovering = False

    async def finish(self, reason):
        if self.terminal or self.ended():
            return
        self.terminal = True
        try:
            await asyncio.wait_for(self.session.interrupt(force=True), 2)
            await asyncio.wait_for(self.session.say(END_TEXT, audio=fallback_audio("call_end"),
                                                   allow_interruptions=False), 9)
        except Exception:
            self.diagnostics.fallback_failures += 1
        finally:
            await self.close_call(reason)

    async def tick(self):
        if self.ended() or self.terminal or self.closing or self.recovering:
            return
        if self.store is not None and self.store.failed:
            await self.finish("storage_error")
            return
        if self.session.user_state == "speaking" or self.session.agent_state in ("speaking", "thinking"):
            self.activity()
            return
        now = self.clock()
        if self.hold_until is not None:
            if now < self.hold_until:
                return
            self.hold_until = None
            self.idle_prompts = 1
            self.activity()
            self.diagnostics.idle_prompts += 1
            await self.session.say("아직 확인 중이실까요?", allow_interruptions=True)
        elif now - self.last_activity >= 12:
            if self.idle_prompts:
                await self.finish("no_response")
                return
            self.idle_prompts = 1
            self.activity()
            self.diagnostics.idle_prompts += 1
            await self.session.say("말씀을 아직 확인하지 못했어요. 다시 말씀해 주시겠어요?",
                                   allow_interruptions=True)

    async def watch(self):
        while not self.ended() and not self.terminal:
            await asyncio.sleep(1)
            await self.tick()

    async def stop(self):
        self.terminal = True
        current = asyncio.current_task()
        pending = [task for task in self.tasks if task is not current]
        for task in pending:
            task.cancel()
        await asyncio.gather(*pending, return_exceptions=True)

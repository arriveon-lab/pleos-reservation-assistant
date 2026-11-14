"""Text-only agent evaluation with fictional bookings and an in-memory database."""
import argparse
import asyncio
import json
import os
from pathlib import Path

from livekit.agents import APIConnectOptions, AgentSession, StopResponse, inference, llm
from livekit.agents.voice import SessionConnectOptions

from .test_voice import LiveWorkflowTest


QUESTION = "저녁 일곱 시에 네 명 예약 가능할까요?"
DETAILS = "네, 가능합니다. 유아의자 하나 있고 계단 없이 입장됩니다. 추가 비용은 없습니다."
HISTORY = [("assistant", QUESTION), ("user", DETAILS),
           ("assistant", "김 예 약 이름으로 예약 부탁드립니다.")]
SCENARIOS = [
    ("combined_answer", [("assistant", QUESTION)], DETAILS,
     "이미 답한 인원·시각·조건·비용을 반복 질문하지 않고 예약자 이름으로 예약을 요청한다."),
    ("availability_only", [("assistant", QUESTION)], "네, 가능합니다.",
     "미확인 필수 조건을 하나씩 질문한다. 가능 여부 답만으로 확정하지 않는다."),
    ("short_acceptance", HISTORY, "네.",
     "save_reservation_result로 원래 날짜·19:00·김예약·4명·0원 및 확인 조건을 저장한다."),
    ("time_correction", [("assistant", QUESTION)], "아니, 일곱 시 말고 여덟 시만 돼요.",
     "승인된 일곱 시 예약을 확정하거나 여덟 시 예약을 임의로 요청하지 않는다."),
    ("misheard_cost", [("assistant", "추가 비용이 있을까요?")],
     "추가 배움 없습니다. 주차는 가능합니다.", "비용만 한 번 재확인하고 즉시 종료하지 않는다."),
    ("required_condition_unavailable", [("assistant", QUESTION)],
     "일곱 시 네 명은 가능한데 유아의자는 없어요.",
     "유아의자 필수 조건 불가를 이해하고 새 예약 요청 없이 보류한다."),
]


class OfflineLLM(llm.LLM):
    """Exercise SDK sessions without a provider; not a language-quality result."""
    def chat(self, *, chat_ctx, tools=None, conn_options=APIConnectOptions(), **kwargs):
        return OfflineStream(self, chat_ctx=chat_ctx, tools=tools or [], conn_options=conn_options)


class OfflineStream(llm.LLMStream):
    async def _run(self):
        self._event_ch.send_nowait(llm.ChatChunk(
            id="offline", delta=llm.ChoiceDelta(role="assistant", content="가상 실행 확인입니다.")))


async def evaluate(model, output, offline, selected):
    # Import after selecting an absent env path; never load production phone config.
    from .worker import BookingVoiceAgent

    results = []
    for label, history, user_input, expected in SCENARIOS:
        if selected and label not in selected:
            continue
        fixture = LiveWorkflowTest()
        fixture.setUp()
        booking_id, call = fixture.reserve()
        ended = []

        async def close(reason):
            ended.append(reason)
            fixture.workflow.finish(call["id"], reason)

        agent = BookingVoiceAgent(fixture.workflow, call["id"],
                                  fixture.workflow.context(call["id"]), close)
        chat = llm.ChatContext()
        for role, text in history:
            chat.add_message(role=role, content=text)
            fixture.workflow.transcript(call["id"], "agent" if role == "assistant" else "restaurant", text)
        await agent.update_chat_ctx(chat)
        row = {"scenario": label, "expected": expected, "input": user_input,
               "history": history, "evaluation": "OFFLINE_SMOKE" if offline else "REQUIRES_REVIEW"}
        spoken = []
        try:
            async with AgentSession(
                llm=model, max_tool_steps=2, user_away_timeout=None,
                conn_options=SessionConnectOptions(
                    llm_conn_options=APIConnectOptions(max_retry=0, timeout=20)),
            ) as session:
                @session.on("conversation_item_added")
                def store(event):
                    if isinstance(event.item, llm.ChatMessage) and event.item.role == "assistant":
                        spoken.append(event.item.text_content)
                        fixture.workflow.transcript(call["id"], "agent", event.item.text_content)

                await session.start(agent)
                # Text run() calls generate_reply directly. Replay the STT hook
                # explicitly to store evidence and apply production fee checks.
                try:
                    await agent.on_user_turn_completed(chat, llm.ChatMessage(role="user", content=[user_input]))
                except StopResponse:
                    row["events"] = []
                    row["handledByInputHook"] = True
                else:
                    result = await asyncio.wait_for(session.run(user_input=user_input), timeout=30)
                    row["events"] = [{"type": event.type, "item": event.item.model_dump(mode="json")}
                                     for event in result.events]
                row["spoken"] = spoken
                row["bookingStatus"] = fixture.get_booking(booking_id)["status"]
                row["ended"] = ended
        except Exception as error:
            row["evaluation"] = "ERROR"
            row["errorType"] = type(error).__name__
        finally:
            fixture.tearDown()
        results.append(row)
        output.write_text(json.dumps(results, ensure_ascii=False, indent=2))
        print(label, row["evaluation"], row.get("bookingStatus", row.get("errorType")), flush=True)
        if row["evaluation"] == "ERROR":
            raise RuntimeError(f"Scenario {label} failed; inspect evaluation output")


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-model", action="store_true", help="Run six scenarios using paid LiveKit inference")
    parser.add_argument("--env-file", type=Path, help="Read LiveKit credentials from a private env file")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenario", action="append", choices=[row[0] for row in SCENARIOS])
    args = parser.parse_args()
    os.environ["PHONE_ENV_FILE"] = "/nonexistent/arriveon-conversation-eval.env"
    if args.run_model:
        from .config import VoiceConfig
        credentials = dict(os.environ)
        if args.env_file:
            if args.env_file.stat().st_mode & 0o077:
                raise ValueError("Credential file must have mode 600")
            for line in args.env_file.read_text().splitlines():
                key, sep, value = line.partition("=")
                if sep and key in ("LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "PHONE_LLM_MODEL"):
                    credentials[key] = value.strip().strip("\"'")
        model = inference.LLM(
            model=credentials.get("PHONE_LLM_MODEL", VoiceConfig.llm_model),
            api_key=credentials["LIVEKIT_API_KEY"], api_secret=credentials["LIVEKIT_API_SECRET"],
            extra_kwargs={"max_completion_tokens": 250},
        )
    else:
        model = OfflineLLM()
    async with model:
        await evaluate(model, args.output, offline=not args.run_model, selected=args.scenario)


if __name__ == "__main__":
    asyncio.run(main())

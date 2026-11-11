"""Secret-safe local configuration for optional real telephony."""
import json
import os
import re
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import urlsplit

SONIOX_TRIAL_MODEL = "soniox/stt-rt-v6"


class ConfigurationError(ValueError):
    pass


def load_env_file(path):
    """Read simple KEY=VALUE assignments; never evaluate shell expressions."""
    path = Path(path)
    if not path.exists():
        return
    if path.stat().st_mode & 0o077:
        raise ConfigurationError("실전화 환경 파일의 권한을 600으로 설정해 주세요.")
    for raw in path.read_text().splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ConfigurationError("환경 파일은 KEY=VALUE 형식이어야 해요.")
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ[key] = value


def normalize_number(value):
    if not isinstance(value, str):
        raise ConfigurationError("전화번호를 확인해 주세요.")
    value = re.sub(r"[\s()-]", "", value)
    if value.startswith("0"):
        value = "+82" + value[1:]
    if not re.fullmatch(r"\+82(?:10\d{8}|2\d{7,8}|(?:31|32|33|41|42|43|44|51|52|53|54|55|61|62|63|64)\d{7,8}|70\d{8})", value):
        raise ConfigurationError("허용한 국내 휴대폰·유선·070 번호만 사용할 수 있어요.")
    return value


@dataclass(frozen=True)
class VoiceConfig:
    livekit_url: str = ""
    api_key: str = field(default="", repr=False)
    api_secret: str = field(default="", repr=False)
    sip_uri: str = ""
    telkit_number: str = field(default="", repr=False)
    telkit_number_id: str = ""
    telkit_key: str = field(default="", repr=False)
    sip_username: str = field(default="", repr=False)
    sip_password: str = field(default="", repr=False)
    inbound_trunk_id: str = ""
    outbound_trunk_id: str = ""
    allowed_numbers: tuple = field(default=(), repr=False)
    restaurant_numbers: dict = field(default_factory=dict, repr=False)
    enabled: bool = False
    inbound_enabled: bool = False
    test_phone_input: bool = False
    callback_test_booking_id: str = ""
    max_seconds: int = 180
    daily_calls: int = 1
    agent_name: str = "arriveon-reservation"
    stt_model: str = "assemblyai/universal-3-6-pro"
    soniox_key: str = field(default="", repr=False)
    llm_model: str = "google/gemma-4-31b-it"
    tts_model: str = "cartesia/sonic-3-2026-01-12"
    tts_voice: str = "15628352-2ede-4f1b-89e6-ceda0c983fbc"
    noise_suppression: bool = True
    echo_cancellation: bool = False
    echo_delay_ms: int = 200
    input_level_probe: bool = False
    interruption_mode: str = "adaptive"
    database: str = "data/phone-agent-prototype.sqlite3"
    secrets_dir: str = field(default="work/telkit-private", repr=False)

    @classmethod
    def from_env(cls, env=None):
        env = os.environ if env is None else env
        folder = Path(env.get("PHONE_SECRETS_DIR", "work/telkit-private"))
        def private(name):
            path = folder / name
            if not path.exists():
                return {}
            if path.stat().st_mode & 0o077:
                raise ConfigurationError("비밀값 파일의 권한을 600으로 설정해 주세요.")
            return json.loads(path.read_text())
        key, sip, setup = private("workspace-key.json"), private("sip-credential.json"), private("livekit-setup.json")
        try:
            mapping = json.loads(env.get("PHONE_RESTAURANT_NUMBERS", "{}"))
            if not isinstance(mapping, dict):
                raise ValueError
            mapping = {str(k): normalize_number(v) for k, v in mapping.items()}
            allowed = tuple(normalize_number(v) for v in env.get("PHONE_ALLOWED_NUMBERS", "").split(",") if v.strip())
            seconds, daily = int(env.get("PHONE_MAX_SECONDS", "180")), int(env.get("PHONE_DAILY_CALLS", "1"))
            noise = env.get("PHONE_NOISE_SUPPRESSION", "true").lower()
            echo = env.get("PHONE_ECHO_CANCELLATION", "false").lower()
            echo_delay = int(env.get("PHONE_ECHO_DELAY_MS", "200"))
            probe = env.get("PHONE_INPUT_LEVEL_PROBE", "false").lower()
            inbound = env.get("PHONE_INBOUND_ENABLED", "false").lower()
            test_phone_input = env.get("PHONE_TEST_PHONE_INPUT", "false").lower()
            callback_test_booking_id = env.get("PHONE_CALLBACK_TEST_BOOKING_ID", "")
            interruption = env.get("PHONE_INTERRUPTION_MODE", "adaptive").lower()
            if (noise not in ("true", "false") or echo not in ("true", "false")
                    or any(flag not in ("true", "false") for flag in (probe, inbound, test_phone_input))
                    or not 0 <= echo_delay <= 500 or interruption not in ("adaptive", "vad")):
                raise ValueError
            if not 30 <= seconds <= 600 or not 1 <= daily <= 10:
                raise ValueError
            if callback_test_booking_id and not re.fullmatch(r"[a-f0-9]{32}", callback_test_booking_id):
                raise ValueError
        except (ValueError, TypeError, json.JSONDecodeError):
            raise ConfigurationError("번호 매핑·허용 번호·통화 제한 설정을 확인해 주세요.") from None
        return cls(
            livekit_url=env.get("LIVEKIT_URL", ""), api_key=env.get("LIVEKIT_API_KEY", ""),
            api_secret=env.get("LIVEKIT_API_SECRET", ""), sip_uri=env.get("LIVEKIT_SIP_URI", ""),
            telkit_number=env.get("TELKIT_NUMBER", ""), telkit_number_id=env.get("TELKIT_NUMBER_ID", ""),
            telkit_key=(env.get("TELKIT_API_KEY") or key.get("secret", "")),
            sip_username=(env.get("TELKIT_SIP_USERNAME") or sip.get("username", "")),
            sip_password=(env.get("TELKIT_SIP_PASSWORD") or sip.get("password", "")),
            inbound_trunk_id=(env.get("LIVEKIT_INBOUND_TRUNK_ID") or setup.get("inbound_trunk_id", "")),
            outbound_trunk_id=(env.get("LIVEKIT_OUTBOUND_TRUNK_ID") or setup.get("outbound_trunk_id", "")),
            allowed_numbers=allowed, restaurant_numbers=mapping,
            enabled=env.get("PHONE_LIVE_ENABLED", "false").lower() == "true",
            inbound_enabled=inbound == "true",
            test_phone_input=test_phone_input == "true", callback_test_booking_id=callback_test_booking_id,
            max_seconds=seconds, daily_calls=daily, database=env.get("PHONE_DATABASE", cls.database),
            agent_name=env.get("PHONE_AGENT_NAME", cls.agent_name), secrets_dir=str(folder),
            stt_model=env.get("PHONE_STT_MODEL", cls.stt_model),
            soniox_key=env.get("SONIOX_API_KEY", "").strip(),
            llm_model=env.get("PHONE_LLM_MODEL", cls.llm_model),
            tts_model=env.get("PHONE_TTS_MODEL", cls.tts_model),
            noise_suppression=noise == "true", interruption_mode=interruption,
            echo_cancellation=echo == "true", echo_delay_ms=echo_delay,
            input_level_probe=probe == "true",
            tts_voice=env.get("PHONE_TTS_VOICE", cls.tts_voice))

    def missing(self, setup=False, direction="outbound"):
        fields = [("LIVEKIT_URL", self.livekit_url), ("LIVEKIT_API_KEY", self.api_key),
                  ("LIVEKIT_API_SECRET", self.api_secret), ("PHONE_ALLOWED_NUMBERS", self.allowed_numbers)]
        if setup:
            fields += [("LIVEKIT_SIP_URI", self.sip_uri), ("TELKIT_NUMBER", self.telkit_number),
                       ("TELKIT_NUMBER_ID", self.telkit_number_id), ("TELKIT_API_KEY", self.telkit_key),
                       ("TELKIT_SIP_USERNAME", self.sip_username), ("TELKIT_SIP_PASSWORD", self.sip_password)]
        else:
            fields += ([("LIVEKIT_INBOUND_TRUNK_ID", self.inbound_trunk_id)] if direction == "inbound"
                       else [("LIVEKIT_OUTBOUND_TRUNK_ID", self.outbound_trunk_id)])
            if self.soniox_trial:
                fields.append(("SONIOX_API_KEY", self.soniox_key))
        return [name for name, value in fields if not value]

    def validate(self, setup=False, direction="outbound"):
        missing = self.missing(setup, direction)
        if missing:
            raise ConfigurationError("필요한 설정: " + ", ".join(missing))
        url = urlsplit(self.livekit_url)
        if url.scheme != "wss" or not url.hostname or url.username or url.password:
            raise ConfigurationError("LIVEKIT_URL은 자격 증명 없는 wss:// 주소여야 해요.")
        if setup:
            if not re.fullmatch(r"sip:[a-zA-Z0-9-]+\.sip\.livekit\.cloud", self.sip_uri):
                raise ConfigurationError("LiveKit 프로젝트의 SIP URI를 확인해 주세요.")
            number = normalize_number(self.telkit_number)
            if not number.startswith("+8270"):
                raise ConfigurationError("Telkit의 070 번호를 설정해 주세요.")

    def require_live(self, direction="outbound"):
        if direction not in ("inbound", "outbound"):
            raise ConfigurationError("허용한 통화 방향이 아니에요.")
        if self.soniox_trial and (
            self.stt_model != SONIOX_TRIAL_MODEL or direction != "outbound" or self.inbound_enabled
            or self.test_phone_input or self.test_mobile is None or self.max_seconds > 60 or self.daily_calls != 1
        ):
            raise ConfigurationError("Soniox는 등록한 시험 휴대폰 1대에 발신 1회(최대 60초)만 사용할 수 있어요.")
        self.validate(direction=direction)
        if not (self.inbound_enabled if direction == "inbound" else self.enabled):
            flag = "PHONE_INBOUND_ENABLED" if direction == "inbound" else "PHONE_LIVE_ENABLED"
            raise ConfigurationError(f"실전화 실행은 {flag}=true와 허용 대상 설정 후 사용할 수 있어요.")

    def require_worker(self):
        if not self.enabled and not self.inbound_enabled:
            raise ConfigurationError("PHONE_LIVE_ENABLED=true 또는 PHONE_INBOUND_ENABLED=true가 필요해요.")
        if self.enabled:
            self.require_live()
        if self.inbound_enabled:
            self.require_live("inbound")

    @property
    def soniox_trial(self):
        return self.stt_model.startswith("soniox/")

    @property
    def test_mobile(self):
        mobiles = {number for number in self.allowed_numbers if re.fullmatch(r"\+8210\d{8}", number)}
        return next(iter(mobiles)) if len(mobiles) == 1 else None

    def report(self):
        return {"liveEnabled": self.enabled, "missingRuntime": self.missing(),
                "inboundEnabled": self.inbound_enabled,
                "missingInboundRuntime": self.missing(direction="inbound"),
                "missingSetup": self.missing(True), "allowedNumberCount": len(self.allowed_numbers),
                "restaurantCount": len(self.restaurant_numbers), "maxSeconds": self.max_seconds,
                "dailyCalls": self.daily_calls, "models": {"stt": self.stt_model, "llm": self.llm_model, "tts": self.tts_model},
                "audioHandling": {"noiseSuppression": self.noise_suppression,
                                  "echoCancellation": self.echo_cancellation,
                                  "echoInitialDelayMs": self.echo_delay_ms,
                                  "inputLevelProbe": self.input_level_probe,
                                  "interruptionMode": self.interruption_mode}}

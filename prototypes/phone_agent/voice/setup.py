"""Provision the documented Telkit/LiveKit SIP connection. No number purchases or calls."""
import json
import os
import urllib.error
import urllib.request
from pathlib import Path

from .config import ConfigurationError, normalize_number

TELKIT_IPS = ["34.64.81.145", "34.47.68.103"]


def save_private(folder, name, value):
    folder = Path(folder)
    folder.mkdir(parents=True, exist_ok=True)
    folder.chmod(0o700)
    path = folder / name
    descriptor = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    path.chmod(0o600)


class TelkitClient:
    def __init__(self, key):
        self.key = key

    def request(self, method, path, body=None):
        request = urllib.request.Request("https://api.telkit.io" + path, method=method,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + self.key, "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try:
                code = json.load(error).get("error", {}).get("code", "http_error")
            except ValueError:
                code = "http_error"
            # Never surface response bodies that may contain a human link or credentials.
            raise ConfigurationError(f"Telkit HTTP {error.code}: {code}") from None


def setup_plan(config, redact=True):
    number = normalize_number(config.telkit_number)
    return {
        "inbound": {"name": config.agent_name + "-in", "numbers": [number],
                    "allowed_addresses": TELKIT_IPS, "allowed_numbers": list(config.allowed_numbers),
                    "media": {"encryption": "SIP_MEDIA_ENCRYPT_REQUIRE"},
                    "max_call_duration": f"{config.max_seconds}s"},
        "outbound": {"name": config.agent_name + "-out", "numbers": [number],
                     "address": "sip.telkit.io", "transport": "SIP_TRANSPORT_TLS",
                     "auth_username": "<redacted>" if redact else config.sip_username,
                     "auth_password": "<redacted>" if redact else config.sip_password,
                     "media": {"encryption": "SIP_MEDIA_ENCRYPT_REQUIRE"}},
        "dispatch": {"name": config.agent_name + "-dispatch",
                     "rule": {"dispatch_rule_individual": {"room_prefix": "arriveon-in-"}},
                     "room_config": {"agents": [{"agent_name": config.agent_name,
                         "metadata": json.dumps({"direction": "inbound"})}]}},
        "telkitConnection": {"sip_uri": config.sip_uri, "transport": "tls",
                             "srtp": "sdes", "number_format": "e164"}}


async def apply_setup(config, client=None, telkit=None):
    """Create only named resources; record each ID immediately for restart/reconciliation."""
    from google.protobuf.json_format import MessageToDict, ParseDict
    from livekit import api
    config.validate(setup=True)
    own_client = client is None
    client = client or api.LiveKitAPI(config.livekit_url, config.api_key, config.api_secret)
    telkit = telkit or TelkitClient(config.telkit_key)
    plan = setup_plan(config, redact=False)
    folder = Path(config.secrets_dir)
    journal_path = folder / "livekit-setup.json"
    journal = json.loads(journal_path.read_text()) if journal_path.exists() else {}
    try:
        for direction in ("inbound", "outbound"):
            response = await getattr(client.sip, "list_sip_" + direction + "_trunk")(
                getattr(api, "ListSIP" + direction.title() + "TrunkRequest")())
            named = [item for item in response.items if item.name == plan[direction]["name"]]
            if len(named) > 1:
                raise ConfigurationError("동일한 이름의 LiveKit trunk가 여러 개 있어요. 먼저 대조해 주세요.")
            if named:
                item = named[0]
                actual = MessageToDict(item, preserving_proto_field_name=True)
                for key in ("numbers", "allowed_numbers", "allowed_addresses", "media", "max_call_duration") if direction == "inbound" else ("numbers", "address", "transport", "media"):
                    if actual.get(key) != plan[direction].get(key):
                        raise ConfigurationError("기존 LiveKit trunk 설정이 달라요. 자동 덮어쓰지 않았어요.")
            else:
                info = ParseDict(plan[direction], getattr(api, "SIP" + direction.title() + "TrunkInfo")())
                item = await getattr(client.sip, "create_sip_" + direction + "_trunk")(
                    getattr(api, "CreateSIP" + direction.title() + "TrunkRequest")(trunk=info))
            journal[direction + "_trunk_id"] = item.sip_trunk_id
            save_private(folder, "livekit-setup.json", journal)
        plan["dispatch"]["trunk_ids"] = [journal["inbound_trunk_id"]]
        response = await client.sip.list_sip_dispatch_rule(api.ListSIPDispatchRuleRequest())
        named = [item for item in response.items if item.name == plan["dispatch"]["name"]]
        if len(named) > 1:
            raise ConfigurationError("동일 이름의 dispatch rule이 여러 개 있어요.")
        if named:
            item = named[0]
            actual = MessageToDict(item, preserving_proto_field_name=True)
            for key in ("trunk_ids", "rule", "room_config"):
                if actual.get(key) != plan["dispatch"].get(key):
                    raise ConfigurationError("기존 dispatch 설정이 달라요. 먼저 대조해 주세요.")
        else:
            item = await client.sip.create_sip_dispatch_rule(api.CreateSIPDispatchRuleRequest(
                dispatch_rule=ParseDict(plan["dispatch"], api.SIPDispatchRuleInfo())))
        journal["dispatch_rule_id"] = item.sip_dispatch_rule_id
        save_private(folder, "livekit-setup.json", journal)
        # Only update the purchased number selected by the user; never buy or rotate credentials.
        telkit.request("PUT", f"/v1/numbers/{config.telkit_number_id}/connection", plan["telkitConnection"])
        journal["telkit_connected"] = True
        save_private(folder, "livekit-setup.json", journal)
        return {"configured": True, "propagationSeconds": 60}
    finally:
        if own_client:
            await client.aclose()

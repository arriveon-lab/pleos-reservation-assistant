"""LiveKit network calls, kept outside the SQLite transaction and never auto-retried."""
import json

from .workflow import LiveWorkflow


def failure_reason(error):
    """Keep structured SIP categories, never provider messages/headers/phone numbers."""
    metadata = getattr(error, "metadata", {})
    try:
        sip_code = int(metadata.get("sip_status_code", 0)) if isinstance(metadata, dict) else 0
    except (TypeError, ValueError):
        sip_code = 0
    reason = {403: "sip_forbidden", 404: "sip_not_found", 408: "sip_no_answer",
              480: "sip_unavailable", 486: "sip_busy", 488: "sip_media_rejected",
              503: "sip_service_unavailable", 603: "sip_declined"}.get(sip_code)
    if reason:
        return reason
    if getattr(error, "code", None) == "resource_exhausted":
        return "provider_capacity"
    return "dispatch_uncertain"


async def dial_booking(service, config, owner, booking_id, version, request_id, client=None):
    from google.protobuf.duration_pb2 import Duration
    from livekit import api
    workflow = LiveWorkflow(service, config)
    call, created = workflow.reserve(owner, booking_id, version, request_id)
    if not created:
        return {"id": call["id"], "reused": True, "status": call["status"]}
    own_client = client is None
    client = client or api.LiveKitAPI(config.livekit_url, config.api_key, config.api_secret)
    try:
        call = workflow.mark_dispatching(call["id"])
        await client.agent_dispatch.create_dispatch(api.CreateAgentDispatchRequest(
            room=call["room"], agent_name=config.agent_name,
            metadata=json.dumps({"direction": "outbound", "callId": call["id"], "nonce": call["nonce"]})))
        await client.sip.create_sip_participant(api.CreateSIPParticipantRequest(
            sip_trunk_id=config.outbound_trunk_id, sip_call_to=call["target"],
            room_name=call["room"], participant_identity="restaurant-" + call["id"],
            participant_name="Restaurant", wait_until_answered=True,
            ringing_timeout=Duration(seconds=30), max_call_duration=Duration(seconds=config.max_seconds)))
        return {"id": call["id"], "requested": True}
    except Exception as error:
        # A timeout is not proof that no INVITE went out. Do not redial an uncertain call.
        workflow.finish(call["id"], failure_reason(error))
        try:
            await client.room.delete_room(api.DeleteRoomRequest(room=call["room"]))
        except Exception:
            pass  # The SIP provider's max_call_duration still bounds a surviving call.
        return {"id": call["id"], "requested": False, "status": "UNKNOWN"}
    finally:
        if own_client:
            await client.aclose()

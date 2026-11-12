FROM python:3.12-slim-bookworm@sha256:935c3c5b12357a7e10caa9c1e6ca5692344d97cf229832ef584be2cec9fc03df

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HF_HUB_DISABLE_TELEMETRY=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgomp1 libstdc++6 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 phone \
    && useradd --uid 10001 --gid 10001 --create-home phone

WORKDIR /app
COPY prototypes/phone_agent/voice/requirements.txt /app/voice-requirements.txt
RUN pip install --no-cache-dir -r /app/voice-requirements.txt
COPY prototypes/phone_agent /app/prototypes/phone_agent

USER 10001:10001
# Fetch local model assets at build time, without account keys or inference requests.
RUN python -m prototypes.phone_agent.voice.worker download-files
ENV HF_HUB_OFFLINE=1

ARG SOURCE_REVISION=unknown
LABEL org.opencontainers.image.revision=$SOURCE_REVISION

CMD ["python", "-m", "prototypes.phone_agent", "--port", "18765", "--database", "/var/lib/arriveon-phone/phone-agent-prototype.sqlite3"]

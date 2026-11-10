"""Deployment entry point: reject disabled LIVE before registering a worker."""
import importlib
import os

from .config import VoiceConfig, load_env_file


def main():
    load_env_file(os.environ.get("PHONE_ENV_FILE", "work/phone-live.env"))
    VoiceConfig.from_env().require_live()
    worker = importlib.import_module("prototypes.phone_agent.voice.worker")
    worker.agents.cli.run_app(worker.server)


if __name__ == "__main__":
    main()

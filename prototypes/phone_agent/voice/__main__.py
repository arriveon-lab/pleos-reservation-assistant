"""Management commands; doctor/plan are read-only and never connect to a model."""
import argparse
import asyncio
import json

from .config import ConfigurationError, VoiceConfig, load_env_file
from .setup import apply_setup, setup_plan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["doctor", "plan", "setup"])
    parser.add_argument("--env-file", default="work/phone-live.env")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    try:
        load_env_file(args.env_file)
        config = VoiceConfig.from_env()
        if args.command == "doctor":
            result = config.report()
        elif args.command == "setup" and args.apply:
            result = asyncio.run(apply_setup(config))
        else:
            if not config.telkit_number:
                raise ConfigurationError("번호 구매 후 TELKIT_NUMBER를 설정하면 SIP 설정안을 만들 수 있어요.")
            result = setup_plan(config)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except ConfigurationError as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    main()

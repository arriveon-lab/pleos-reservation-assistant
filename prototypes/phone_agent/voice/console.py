"""Local phone-test screen. The optional LIVE worker waits for a browser-approved call."""
import argparse
import http.client
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import uuid

from ..server import load_test_restaurants, make_server
from ..service import DomainError, ReservationService
from ..web_recommendation import request as recommendation_request
from .config import VoiceConfig, load_env_file


def free_port():
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def stop(process):
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", required=True, type=Path)
    parser.add_argument("--env-file", type=Path, default=Path("work/phone-live.env"))
    parser.add_argument("--database", type=Path, default=Path("work/phone-live-test.sqlite3"))
    parser.add_argument("--port", type=int, default=8767)
    parser.add_argument("--live", action="store_true")
    recommendation = parser.add_mutually_exclusive_group()
    recommendation.add_argument("--web-search", action="store_true")
    recommendation.add_argument("--recommendation-port", type=int)
    parser.add_argument("--openai-env-file", type=Path, default=Path("work/openai-web.env"))
    parser.add_argument("--web-max-calls", type=int, default=1)
    args = parser.parse_args()
    if args.recommendation_port is not None and not 1 <= args.recommendation_port <= 65535:
        parser.error("Check the loopback recommendation port.")
    if not 1 <= args.web_max_calls <= 1000:
        parser.error("Set a web search limit between 1 and 1000.")
    with tempfile.TemporaryDirectory(prefix="arriveon-phone-test-") as runtime:
        try:
            if args.web_search:
                load_env_file(args.openai_env_file)
                openai_key = os.environ.pop("OPENAI_API_KEY", "").strip()
                if not openai_key:
                    parser.error("Set OPENAI_API_KEY in the environment or the private OpenAI settings file.")
                root = Path(__file__).resolve().parents[3]
                jar = root / "server/build/libs/reservation-server-0.1.0-SNAPSHOT.jar"
                if not jar.is_file():
                    parser.error("Build the recommendation server with ./gradlew -PincludeAndroid=false :server:bootJar.")
            load_env_file(args.env_file)
            os.environ.setdefault("PHONE_SECRETS_DIR", str(args.env_file.resolve().parent / "telkit-private"))
            os.environ.update(
                PHONE_ENV_FILE=str(Path(runtime) / "none.env"), PHONE_DATABASE=str(args.database.resolve()),
                PHONE_LIVE_ENABLED="true" if args.live else "false", PHONE_INBOUND_ENABLED="false",
            )
            restaurants = load_test_restaurants(args.directory)
            config = VoiceConfig.from_env()
            if args.live:
                config.require_live()
                if config.test_mobile is None:
                    parser.error("Set one registered mobile in PHONE_ALLOWED_NUMBERS.")
        except (OSError, ValueError) as error:
            parser.error(str(error))

        args.database.parent.mkdir(parents=True, exist_ok=True)
        service = ReservationService(args.database, test_restaurants=restaurants,
                                     web_test_enabled=args.web_search or args.recommendation_port is not None)
        worker_port = free_port() if args.live else None
        recommendation_port = free_port() if args.web_search else args.recommendation_port
        worker = None
        web_process = None

        def ready():
            if worker is None or worker.poll() is not None:
                return False
            connection = http.client.HTTPConnection("127.0.0.1", worker_port, timeout=0.5)
            try:
                connection.request("GET", "/")
                return connection.getresponse().status == 200
            except (OSError, http.client.HTTPException):
                return False
            finally:
                connection.close()

        try:
            with make_server(service, args.port, worker_ready=ready, web_search=True,
                             web_search_port=recommendation_port) as server:
                if args.web_search:
                    java_home = os.environ.get("JAVA_HOME")
                    java = str(Path(java_home) / "bin/java") if java_home else "java"
                    env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
                    env["OPENAI_API_KEY"] = openai_key
                    with (Path(runtime) / "recommendation.log").open("wb") as log:
                        web_process = subprocess.Popen([
                            java, "-Djdk.httpclient.disableRetryConnect=true",
                            "-Djdk.httpclient.redirects.retrylimit=1",
                            "-jar", str(jar), "--app.mode=MOCK", "--server.address=127.0.0.1",
                            f"--server.port={recommendation_port}", "--spring.datasource.url=jdbc:h2:mem:phone-web",
                            "--app.destination-recommendations.enabled=true",
                            "--app.destination-recommendations.source=WEB_SEARCH",
                            "--app.destination-recommendations.web.enabled=true",
                            "--app.destination-recommendations.web.runtime-enabled=true",
                            "--app.destination-recommendations.web.runtime-model=gpt-6-luna",
                            "--app.destination-recommendations.web.runtime-max-output-tokens=3072",
                            f"--app.destination-recommendations.web.runtime-max-calls-per-startup={args.web_max_calls}",
                        ], cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
                    for _ in range(60):
                        if web_process.poll() is not None:
                            raise SystemExit("The web recommendation server failed to start.")
                        try:
                            recommendation_request(recommendation_port, "GET", "/api/v1/status")
                            break
                        except DomainError:
                            time.sleep(0.5)
                    else:
                        raise SystemExit("The web recommendation server did not start within 30 seconds.")
                if args.live:
                    env = {**os.environ, "PHONE_WORKER_PORT": str(worker_port),
                           "PHONE_AGENT_NAME": "arriveon-phone-test-" + uuid.uuid4().hex[:12]}
                    os.environ["PHONE_AGENT_NAME"] = env["PHONE_AGENT_NAME"]
                    worker = subprocess.Popen([sys.executable, "-m", "prototypes.phone_agent.voice.launch", "start"],
                                              env=env)
                    for _ in range(60):
                        if ready():
                            break
                        if worker.poll() is not None:
                            raise SystemExit("The voice worker exited before registration.")
                        time.sleep(0.5)
                    else:
                        raise SystemExit("The voice worker did not register within 30 seconds.")
                print(f"Phone test screen: http://127.0.0.1:{server.server_port}/", flush=True)
                server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            stop(worker)
            stop(web_process)
            service.close()


if __name__ == "__main__":
    main()

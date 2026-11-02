"""Bounded, opt-in acceptance of the project workflow against an owned loopback API."""
import argparse
import http.server
import json
import os
from pathlib import Path
import subprocess
import threading
import uuid


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--k6", default="k6")
    parser.add_argument("--prometheus")
    parser.add_argument("--grafana", default="http://127.0.0.1:3000")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    suite = root / "build/api-load-acceptance" / str(uuid.uuid4())
    suite.mkdir(parents=True)

    class Handler(http.server.BaseHTTPRequestHandler):
        counts = {}
        broken = False

        def do_GET(self):
            Handler.counts[self.path] = Handler.counts.get(self.path, 0) + 1
            data = {"status": "UP"} if self.path == "/health" else {
                "items": "wrong shape" if Handler.broken else [{"id": "t-1", "title": "demo", "status": "open"}]
            }
            body = json.dumps(data).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            Handler.counts["POST"] = Handler.counts.get("POST", 0) + 1
            self.send_response(500)
            self.end_headers()

        def log_message(self, *_):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    target = f"http://127.0.0.1:{server.server_port}"
    # The fixture is public; do not forward a developer's real API or metrics credentials.
    environment = {key: value for key, value in os.environ.items()
                   if key not in {"API_TOKEN", "LOAD_API_TOKEN", "LOAD_PROMETHEUS_TOKEN"} and not key.startswith("K6_")}

    def run_pipeline(name):
        run = suite / name
        command = [str(root / "gradlew"), ":example-tests:apiLoadTest", f"-PloadBaseUrl={target}",
                   f"-Pk6Executable={args.k6}", f"-PapiLoadRunDir={run}", "-PloadIterations=3",
                   "--console=plain"]
        if args.prometheus:
            command += ["-PloadPrometheusEnabled=true", f"-PloadPrometheusUrl={args.prometheus}",
                        f"-PloadGrafanaUrl={args.grafana}"]
        with (suite / f"{name}.log").open("w") as log:
            result = subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        return result, run

    try:
        result, positive = run_pipeline("positive")
        require(result.returncode == 0, f"Positive pipeline failed; see {suite / 'positive.log'}")
        require(Handler.counts == {'/health': 4, '/api/v1/tasks': 4}, Handler.counts)
        report = json.loads(next((positive / "load").glob("*/report.json")).read_text())
        require(report['outcome'] == 'PASSED' and report['metrics']['requests'] == 6, "Expected a passing six-request load report")
        require((Path(report['directory']) / 'provenance.json').is_file(), "Load evidence must include provenance")

        summary = suite / "standalone-summary.json"
        native_environment = dict(environment, TESTFORGE_LOAD_BASE_URL=target,
                                  TESTFORGE_LOAD_SUMMARY=str(summary), LOAD_ITERATIONS="1")
        with (suite / "standalone.log").open("w") as log:
            direct = subprocess.run([args.k6, "run", "--no-usage-report", "--new-machine-readable-summary=false",
                                     str(positive / "prepared/scenario.ts")], cwd=root, env=native_environment,
                                    stdout=log, stderr=subprocess.STDOUT, timeout=30)
        require(direct.returncode == 0, 'Prepared bundle must run directly through k6')
        require(json.loads(summary.read_text())['metrics']['http_reqs']['values']['count'] == 2, "Standalone scenario must issue exactly two requests")

        Handler.broken = True
        before = dict(Handler.counts)
        failed, negative = run_pipeline("negative")
        require(failed.returncode != 0, 'A contract mismatch must stop before generating/running load')
        require(not (negative / 'prepared').exists() and (not (negative / 'load').exists()), "Contract failure must prevent preparation and load artifacts")
        require(all((Handler.counts[path] - before[path] == 1 for path in ('/health', '/api/v1/tasks'))), "Contract failure must perform research calls only")
        require('POST' not in Handler.counts, "The read-only fixture must not receive writes")
        (suite / "acceptance.json").write_text(json.dumps({"formatVersion": 1, "positiveLoadRequests": 6,
                "standaloneRequests": 2, "contractMismatchBlockedLoad": True, "writeRequests": 0}, indent=2) + "\n")
        print(f"API load acceptance passed: {suite}")
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()

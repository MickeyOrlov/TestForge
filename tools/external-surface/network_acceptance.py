"""Run the pinned CLI against owned open and closed loopback ports."""

import argparse
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("nmap", type=Path)
args = parser.parse_args()
runner = Path(__file__).with_name("network.py")

def check(family, ip, root):
    with socket.socket(family) as listening:
        listening.bind((ip, 0))
        listening.listen()
        with socket.socket(family) as reserved:
            reserved.bind((ip, 0))
            closed_port = reserved.getsockname()[1]
        open_port = listening.getsockname()[1]
        scope = root / "scope.json"
        scope.write_text(json.dumps({"targets": [{"ip": ip, "ports": [open_port, closed_port]}]}))
        subprocess.run([sys.executable, str(runner), str(scope), str(args.nmap), str(root)],
                       timeout=30, check=True)
    (report_file,) = root.glob("*/network.json")
    report = json.loads(report_file.read_text())
    if not report["complete"] or report["targets"][0]["ip"] != ip:
        raise AssertionError(report)
    ports = {row["port"]: row["state"] for row in report["targets"][0]["ports"]}
    if ports != {open_port: "open", closed_port: "closed"}:
        raise AssertionError(ports)
    if list(report_file.parent.iterdir()) != [report_file]:
        raise AssertionError("Unexpected raw scan artifacts")
    print(f"Native Nmap acceptance passed for {ip}")


with tempfile.TemporaryDirectory(prefix="testforge-network-fixture-") as directory:
    root = Path(directory)
    (root / "ipv4").mkdir()
    check(socket.AF_INET, "127.0.0.1", root / "ipv4")
    if socket.has_ipv6:
        with socket.socket(socket.AF_INET6) as probe:
            try:
                probe.bind(("::1", 0))
            except OSError:
                print("IPv6 loopback unavailable")
            else:
                (root / "ipv6").mkdir()
                check(socket.AF_INET6, "::1", root / "ipv6")

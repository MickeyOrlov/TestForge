"""External TCP inventory over the native Nmap connect scanner."""

import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import uuid
import xml.etree.ElementTree as ET

NMAP_VERSION = "7.993"
PORT_STATES = {"open", "closed", "filtered", "unfiltered", "open|filtered", "closed|filtered"}
MAX_OUTPUT = 1024 * 1024


class ScanFailure(Exception):
    """A sanitized native execution or evidence failure."""


def read_scope(config):
    if not isinstance(config, dict) or set(config) != {"targets"}:
        raise ValueError("Expected a targets array")
    targets = config["targets"]
    if not isinstance(targets, list) or not 1 <= len(targets) <= 10:
        raise ValueError("Select 1..10 explicit IP targets")
    approved = {}
    for target in targets:
        if not isinstance(target, dict) or set(target) != {"ip", "ports"}:
            raise ValueError("Each target needs an IP and TCP ports")
        if not isinstance(target["ip"], str) or "%" in target["ip"]:
            raise ValueError("Use a literal IPv4 or IPv6 address")
        ip = ipaddress.ip_address(target["ip"])
        if ip.is_multicast or ip.is_unspecified or str(ip) == "255.255.255.255":
            raise ValueError("Select an individual IP address")
        ports = target["ports"]
        if not isinstance(ports, list) or not 1 <= len(ports) <= 64 or any(
            type(port) is not int or not 1 <= port <= 65535 for port in ports
        ):
            raise ValueError("Select 1..64 integer TCP ports per target")
        if str(ip) in approved:
            raise ValueError("List each IP once")
        approved[str(ip)] = sorted(set(ports))
    return dict(sorted(approved.items()))


def scan_args(ip, ports):
    return [
        "--unprivileged", "-sT", "-Pn", "-n", "--disable-arp-ping",
        "--max-rate", "15", "--max-retries", "1", "--host-timeout", "15s",
        "-p", ",".join(map(str, ports)), "--no-stylesheet", "-oX", "-",
        *(["-6"] if ":" in ip else []), ip,
    ]


def native(nmap, args, work, timeout):
    with tempfile.TemporaryFile(dir=work) as stdout:
        try:
            result = subprocess.run(
                [nmap, *args], cwd=work, env={"HOME": work, "LC_ALL": "C"},
                stdin=subprocess.DEVNULL, stdout=stdout, stderr=subprocess.DEVNULL,
                timeout=timeout, check=False,
            )
        except subprocess.TimeoutExpired:
            raise ScanFailure("native timeout") from None
        except OSError:
            raise ScanFailure("could not start Nmap") from None
        if result.returncode:
            raise ScanFailure("native execution failed")
        stdout.seek(0)
        result_bytes = stdout.read(MAX_OUTPUT + 1)
        if len(result_bytes) > MAX_OUTPUT:
            raise ScanFailure("native output limit exceeded")
        return result_bytes


def scan_ports(services):
    """Expand Nmap's compact port list without accepting ranges as workflow input."""
    ports = set()
    for token in services.split(","):
        endpoints = token.split("-")
        first = int(endpoints[0])
        last = int(endpoints[-1])
        if len(endpoints) > 2 or not 1 <= first <= last <= 65535 or last - first >= 64:
            raise ValueError("Invalid Nmap port list")
        ports.update(range(first, last + 1))
        if len(ports) > 64:
            raise ValueError("Unexpected port count")
    return ports


def normalize(xml, ip, ports):
    # Native Nmap adds a bare DOCTYPE. Reject custom declarations before parsing.
    xml = xml.replace(b"<!DOCTYPE nmaprun>", b"", 1)
    if b"<!DOCTYPE" in xml or b"<!ENTITY" in xml:
        raise ValueError("Unexpected XML declaration")
    root = ET.fromstring(xml)
    finish = root.find("runstats/finished")
    scan = root.find("scaninfo")
    hosts = root.findall("host")
    if (
        root.tag != "nmaprun" or root.get("scanner") != "nmap"
        or root.get("version") != NMAP_VERSION
        or finish is None or finish.get("exit") != "success"
        or scan is None or scan.get("type") != "connect" or scan.get("protocol") != "tcp"
        or scan.get("numservices") != str(len(ports)) or len(hosts) != 1
        or scan_ports(scan.attrib["services"]) != set(ports)
    ):
        raise ValueError("Incomplete Nmap run")
    host = hosts[0]
    addresses = [
        str(ipaddress.ip_address(node.attrib["addr"])) for node in host.findall("address")
        if node.get("addrtype") in {"ipv4", "ipv6"}
    ]
    if addresses != [ip]:
        raise ValueError("Unexpected address")
    table = host.find("ports")
    if table is None:
        raise ValueError("Missing port evidence")
    states = {}
    for row in table.findall("port"):
        port = int(row.attrib["portid"])
        state = row.find("state")
        if (
            row.get("protocol") != "tcp" or port not in ports or port in states
            or state is None or state.get("state") not in PORT_STATES
        ):
            raise ValueError("Unexpected port record")
        states[port] = state.attrib["state"]

    # Nmap can group many ports with one state. Only assign it when the group
    # accounts for every omitted port without ambiguity.
    remaining = set(ports) - states.keys()
    summaries = table.findall("extraports")
    if len(summaries) == 1:
        summary = summaries[0]
        if summary.get("state") in PORT_STATES and summary.get("count") == str(len(remaining)):
            states.update(dict.fromkeys(remaining, summary.attrib["state"]))
    return states


def inventory(approved, nmap, work):
    report = {"formatVersion": 1, "tool": {"name": "nmap", "version": NMAP_VERSION}, "targets": []}
    startup_failure = None
    try:
        version = native(nmap, ["--version"], work, 5)
        if not re.search(rb"^Nmap version " + re.escape(NMAP_VERSION.encode()) + rb"(?:\s|$)", version):
            raise ScanFailure("tool version mismatch")
    except ScanFailure as error:
        startup_failure = str(error)

    for ip, ports in approved.items():
        error = startup_failure
        states = {}
        if error is None:
            try:
                xml = native(nmap, scan_args(ip, ports), work, 20)
                states = normalize(xml, ip, ports)
                if len(states) != len(ports):
                    error = "missing or ambiguous port evidence"
            except ScanFailure as failure:
                error = str(failure)
            except (ValueError, KeyError, ET.ParseError):
                error = "native XML failed validation"
        target = {
            "ip": ip, "complete": error is None,
            "ports": [{"port": port, "state": states.get(port, "not-measured")} for port in ports],
        }
        if error:
            target["reason"] = error
        report["targets"].append(target)
    report["complete"] = all(target["complete"] for target in report["targets"])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("scope", type=Path)
    parser.add_argument("nmap", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    try:
        if not args.nmap.is_absolute():
            raise ValueError("Use an absolute Nmap executable path")
        approved = read_scope(json.loads(args.scope.read_text(encoding="utf-8")))
        with tempfile.TemporaryDirectory(prefix="testforge-nmap-") as work:
            report = inventory(approved, str(args.nmap), work)
        directory = args.output.resolve() / str(uuid.uuid4())
        directory.mkdir(parents=True, mode=0o700)
        file = directory / "network.json"
        descriptor = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            json.dump(report, output, indent=2)
            output.write("\n")
    except (OSError, ValueError):
        parser.exit(2, "External TCP inventory failed. Check scope, Nmap path and output directory.\n")
    print(("COMPLETED " if report["complete"] else "INCOMPLETE ") + str(directory))
    return 0 if report["complete"] else 2


if __name__ == "__main__":
    raise SystemExit(main())

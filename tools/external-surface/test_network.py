import unittest

from network import normalize, read_scope


def evidence(rows, address="192.0.2.10"):
    return f"""<?xml version="1.0"?>
<!DOCTYPE nmaprun>
<nmaprun scanner="nmap" version="7.993">
  <scaninfo type="connect" protocol="tcp" numservices="3" services="80,443,5432"/>
  <host><address addr="{address}" addrtype="ipv4"/><ports>{rows}</ports></host>
  <runstats><finished exit="success"/></runstats>
</nmaprun>
""".encode()


class NetworkInventoryTest(unittest.TestCase):
    def test_scope_rejects_names_ranges_and_untyped_ports(self):
        for ip, ports in [
            ("example.test", [443]), ("192.0.2.0/24", [443]), ("127.0.0.1-8", [443]),
            ("--script", [443]), ("192.0.2.1", ["1-100"]), ("192.0.2.1", [True]),
        ]:
            with self.subTest(ip=ip, ports=ports), self.assertRaises(ValueError):
                read_scope({"targets": [{"ip": ip, "ports": ports}]})
        self.assertEqual(
            read_scope({"targets": [{"ip": "0:0:0:0:0:0:0:1", "ports": [443, 80, 80]}]}),
            {"::1": [80, 443]},
        )

    def test_xml_assigns_only_unambiguous_omitted_port_states(self):
        opened = '<port protocol="tcp" portid="443"><state state="open"/></port>'
        grouped = evidence(opened + '<extraports state="filtered" count="2"/>')
        self.assertEqual(normalize(grouped, "192.0.2.10", [80, 443, 5432]),
                         {80: "filtered", 443: "open", 5432: "filtered"})
        ambiguous = evidence(opened + '<extraports state="filtered" count="1"/>'
                             + '<extraports state="closed" count="1"/>')
        self.assertEqual(normalize(ambiguous, "192.0.2.10", [80, 443, 5432]), {443: "open"})

    def test_xml_rejects_evidence_for_other_targets_and_ports(self):
        opened = '<port protocol="tcp" portid="444"><state state="open"/></port>'
        with self.assertRaises(ValueError):
            normalize(evidence(opened), "192.0.2.10", [80, 443, 5432])
        with self.assertRaises(ValueError):
            normalize(evidence("", "192.0.2.11"), "192.0.2.10", [80, 443, 5432])
        with self.assertRaises(ValueError):
            normalize(evidence("").replace(b"80,443,5432", b"80,443,5433"),
                      "192.0.2.10", [80, 443, 5432])
        with self.assertRaises(ValueError):
            normalize(evidence("").replace(b'exit="success"', b'exit="error"'),
                      "192.0.2.10", [80, 443, 5432])


if __name__ == "__main__":
    unittest.main()

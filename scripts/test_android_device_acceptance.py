import subprocess
import unittest

from run_android_device_acceptance import (
    AcceptanceError,
    Device,
    command_for,
    parse_adb_devices,
    redact_command,
    require_loopback_backend,
    require_single_device,
)


class AndroidDeviceAcceptanceTest(unittest.TestCase):
    def test_parses_only_authorized_devices(self):
        output = """List of devices attached
emulator-5554 device product:sdk model:Pixel_6 device:emu
R5CT unauthorized usb:1-1
offline-one offline
"""
        self.assertEqual([Device("emulator-5554", "Pixel_6")], parse_adb_devices(output))

    def test_requires_exactly_one_device_unless_serial_is_explicit(self):
        output = """List of devices attached
one device model:Pixel_7
two device model:Pixel_8
"""

        def run(*_args, **_kwargs):
            return subprocess.CompletedProcess([], 0, stdout=output, stderr="")

        with self.assertRaises(AcceptanceError):
            require_single_device(run, "adb", None)
        self.assertEqual(Device("two", "Pixel_8"), require_single_device(run, "adb", "two"))

    def test_backend_must_be_loopback_http_for_adb_reverse(self):
        self.assertEqual(8080, require_loopback_backend("http://127.0.0.1:8080"))
        self.assertEqual(80, require_loopback_backend("http://localhost"))
        for value in (
            "https://example.com",
            "http://192.168.1.10:8080",
            "http://user:pass@localhost:8080",
            "http://localhost:8080/api",
        ):
            with self.subTest(value=value), self.assertRaises(AcceptanceError):
                require_loopback_backend(value)

    def test_evidence_command_redacts_operator_key(self):
        command = command_for(Device("serial", "Pixel"), "http://127.0.0.1:8080", "secret-value")
        redacted = redact_command(command)
        self.assertNotIn("secret-value", " ".join(redacted))
        self.assertIn("--dart-define=CONNECTED_API_KEY=<redacted>", redacted)

    def test_connected_test_targets_selected_physical_device(self):
        command = command_for(Device("R5CT", "SM-S918B"), "http://127.0.0.1:8080", "key")
        self.assertIn("integration_test/connected_health_app_test.dart", command)
        self.assertEqual("R5CT", command[command.index("-d") + 1])


if __name__ == "__main__":
    unittest.main()

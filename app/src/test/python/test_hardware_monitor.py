import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('hardware_monitor', Path(__file__).parents[2] / 'main/res/raw/hardware_monitor.py')
monitor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(monitor)


class HardwareMonitorTests(unittest.TestCase):
    def test_intel_legacy_and_array_streams_use_last_complete_interval(self):
        first = '{"engines":{"Render/3D/0":{"busy":0}}}'
        last = '{"engines":{"Render/3D/0":{"busy":37},"Video/0":{"busy":12}},"frequency":{"actual":600}}'
        for stream in (first + ',\n' + last + ',\n{"engines":', '[' + first + ',' + last + ']'):
            sample = monitor.intel_sample(stream)
            self.assertEqual({'Render/3D/0': 37, 'Video/0': 12}, monitor.intel_engines(sample))
            self.assertEqual(600, sample['frequency']['actual'])

    def test_unavailable_counters_do_not_become_zero(self):
        self.assertEqual({'Idle': 0}, monitor.intel_engines({'engines': {'Unsupported': {'busy': '-'}, 'Idle': {'busy': 0}, 'Invalid': {'busy': 101}}}))
        for value in ('N/A', 'nan', 'inf', -1, None):
            self.assertIsNone(monitor.number(value))

    def test_disk_temperature_is_not_reported_as_cpu_temperature(self):
        def paths(pattern):
            if pattern == '/sys/class/hwmon/hwmon*': return ['/sys/class/hwmon/hwmon0', '/sys/class/hwmon/hwmon1']
            if pattern.endswith('hwmon1/temp*_input'): return ['/sys/class/hwmon/hwmon1/temp1_input']
            return []
        readings = {'/sys/class/hwmon/hwmon0/name': 'drivetemp', '/sys/class/hwmon/hwmon1/name': 'k10temp', '/sys/class/hwmon/hwmon1/temp1_input': '54000'}
        with patch.object(monitor.glob, 'glob', side_effect=paths), patch.object(monitor, 'read', side_effect=lambda p: readings.get(p, '')):
            self.assertEqual(54, monitor.cpu_temperature())

    def test_missing_intel_tool_provides_install_guidance(self):
        with patch.object(monitor, 'read', return_value='0x8086'), patch.object(monitor, 'run', return_value=''), patch.object(monitor.glob, 'glob', return_value=[]), patch.object(monitor.shutil, 'which', return_value=None):
            gpu, tools = monitor.gpu_snapshot('/sys/class/drm/card0')
            self.assertEqual(['intel-gpu-tools'], tools)
            self.assertEqual({}, gpu['engines'])
            self.assertIsNone(gpu['utilization'])

    def test_nvidia_partial_readings_remain_unavailable(self):
        with patch.object(monitor, 'read', return_value='0x10de'), patch.object(monitor, 'run', side_effect=['', 'RTX Test, 21, N/A, 256, 4096, 900']), patch.object(monitor.glob, 'glob', return_value=[]), patch.object(monitor.shutil, 'which', return_value='/usr/bin/nvidia-smi'):
            gpu, tools = monitor.gpu_snapshot('/sys/class/drm/card1')
            self.assertEqual(21, gpu['utilization'])
            self.assertIsNone(gpu['temperature'])
            self.assertEqual(4096, gpu['memoryTotalMb'])
            self.assertEqual([], tools)


if __name__ == '__main__':
    unittest.main()

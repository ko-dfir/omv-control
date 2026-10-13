import csv
import glob
import json
import math
import os
import platform
import re
import shutil
import signal
import subprocess


def number(value):
    try:
        value = float(value)
        return value if math.isfinite(value) and value >= 0 else None
    except (ValueError, TypeError):
        return None


def read(path):
    try:
        with open(path) as source:
            return source.read().strip()
    except OSError:
        return ''


def run(args, seconds=3):
    try:
        result = subprocess.run(args, capture_output=True, text=True, timeout=seconds)
        return result.stdout if result.returncode == 0 else ''
    except (OSError, subprocess.TimeoutExpired):
        return ''


def intel_sample(raw):
    # Old releases emit comma-separated objects; newer ones may emit an array.
    decoder = json.JSONDecoder()
    objects = []
    offset = 0
    while offset < len(raw):
        start = raw.find('{', offset)
        if start < 0:
            break
        try:
            obj, length = decoder.raw_decode(raw[start:])
            if isinstance(obj, dict) and ('engines' in obj or 'frequency' in obj):
                objects.append(obj)
            offset = start + length
        except ValueError:
            offset = start + 1
    # Discard the first startup sample when a measured interval is available.
    return objects[-1] if objects else None


def intel_engines(sample):
    result = {}
    for name, values in sample.get('engines', {}).items():
        if isinstance(values, dict):
            busy = number(values.get('busy'))
            if busy is not None and busy <= 100:
                result[name] = busy
    return result


def cpu_temperature():
    values = []
    for hwmon in glob.glob('/sys/class/hwmon/hwmon*'):
        driver = read(hwmon + '/name').lower()
        if driver not in ('coretemp', 'k10temp', 'zenpower', 'cpu_thermal', 'bcm2835_thermal', 'soc_thermal'):
            continue
        for path in glob.glob(hwmon + '/temp*_input'):
            value = number(read(path))
            if value is not None and 0 < value < 150000:
                values.append(value / 1000)
    if not values:
        for zone in glob.glob('/sys/class/thermal/thermal_zone*'):
            kind = read(zone + '/type').lower()
            if 'cpu' in kind or kind in ('x86_pkg_temp', 'soc-thermal', 'soc_thermal'):
                value = number(read(zone + '/temp'))
                if value is not None and 0 < value < 150000:
                    values.append(value / 1000)
    return max(values) if values else None


def cpu_snapshot():
    data = {}
    try:
        for entry in json.loads(run(['lscpu', '-J']) or '{}').get('lscpu', []):
            data[entry['field'].rstrip(':')] = entry['data']
    except (ValueError, KeyError, TypeError):
        pass
    blocks = read('/proc/cpuinfo').split('\n\n')
    fields = [dict(line.split(':', 1) for line in block.splitlines() if ':' in line) for block in blocks]
    model = data.get('Model name')
    if not model:
        for block in fields:
            model = next((v.strip() for k, v in block.items() if k.strip() in ('model name', 'Hardware')), None)
            if model:
                break
    frequencies = [number(read(path)) for path in glob.glob('/sys/devices/system/cpu/cpu[0-9]*/cpufreq/scaling_cur_freq')]
    frequencies = [v / 1000 for v in frequencies if v is not None]
    if not frequencies:
        frequencies = [number(v.strip()) for block in fields for k, v in block.items() if k.strip() == 'cpu MHz']
        frequencies = [v for v in frequencies if v is not None]
    sockets = number(data.get('Socket(s)'))
    cores_per_socket = number(data.get('Core(s) per socket'))
    pairs = set()
    for block in fields:
        normalized = {k.strip(): v.strip() for k, v in block.items()}
        if 'physical id' in normalized and 'core id' in normalized:
            pairs.add((normalized['physical id'], normalized['core id']))
    cores = int(sockets * cores_per_socket) if sockets and cores_per_socket else len(pairs) or None
    return {'model': model, 'architecture': data.get('Architecture') or platform.machine(),
            'threads': int(number(data.get('CPU(s)')) or os.cpu_count() or 1), 'cores': cores,
            'sockets': int(sockets) if sockets else None,
            'mhz': sum(frequencies) / len(frequencies) if frequencies else None,
            'maxMhz': number(data.get('CPU max MHz')), 'minMhz': number(data.get('CPU min MHz')),
            'temperature': cpu_temperature()}


def gpu_snapshot(card):
    device = card + '/device'
    vendor = {'0x8086': 'Intel', '0x1002': 'AMD', '0x10de': 'NVIDIA'}.get(read(device + '/vendor'), 'Other')
    pci = os.path.basename(os.path.realpath(device))
    description = run(['lspci', '-s', pci]).strip()
    name = description.split(': ', 1)[-1] if description else vendor + ' GPU (' + os.path.basename(card) + ')'
    gpu = {'name': name, 'vendor': vendor, 'device': os.path.basename(card),
           'engines': {}, 'utilization': None, 'temperature': None, 'mhz': None,
           'memoryUsedMb': None, 'memoryTotalMb': None, 'note': None}
    temps = [number(read(path)) for path in glob.glob(device + '/hwmon/hwmon*/temp1_input')]
    temps = [v / 1000 for v in temps if v is not None and 0 < v < 150000]
    gpu['temperature'] = max(temps) if temps else None
    if vendor == 'Intel':
        if not shutil.which('intel_gpu_top'):
            gpu['note'] = 'Install intel-gpu-tools to read Intel GPU engine activity.'
            return gpu, ['intel-gpu-tools']
        process = None
        try:
            process = subprocess.Popen(['timeout', '--signal=INT', '--kill-after=1s', '4s', 'intel_gpu_top', '-J', '-s', '1000', '-o', '-', '-d', 'drm:/dev/dri/' + os.path.basename(card)],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                output, _ = process.communicate(timeout=2.4)
            except subprocess.TimeoutExpired:
                process.send_signal(signal.SIGINT)
                try:
                    output, _ = process.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    output, _ = process.communicate()
            sample = intel_sample(output)
            if sample:
                gpu['engines'] = intel_engines(sample)
                frequency = sample.get('frequency', {})
                if isinstance(frequency, dict):
                    gpu['mhz'] = number(frequency.get('actual'))
            if not gpu['engines']:
                gpu['note'] = 'Intel GPU counters are unavailable. Check SSH permissions, perf counter access and support for this GPU/driver. No permission or driver settings were changed.'
        except OSError:
            gpu['note'] = 'Unable to start intel_gpu_top on this server.'
        finally:
            if process is not None and process.poll() is None:
                process.kill()
                process.communicate()
    elif vendor == 'AMD':
        gpu['utilization'] = number(read(device + '/gpu_busy_percent'))
        for field, filename in [('memoryUsedMb', 'mem_info_vram_used'), ('memoryTotalMb', 'mem_info_vram_total')]:
            value = number(read(device + '/' + filename))
            gpu[field] = value / 1048576 if value is not None else None
        if gpu['utilization'] is None:
            gpu['note'] = 'AMD usage counters are not exposed by the current driver. Check amdgpu support and sysfs permissions.'
    elif vendor == 'NVIDIA':
        if shutil.which('nvidia-smi'):
            output = run(['nvidia-smi', '--id=' + pci, '--query-gpu=name,utilization.gpu,temperature.gpu,memory.used,memory.total,clocks.current.graphics', '--format=csv,noheader,nounits'])
            rows = list(csv.reader(output.splitlines()))
            if rows and len(rows[0]) == 6:
                gpu['name'] = rows[0][0].strip()
                for field, value in zip(['utilization', 'temperature', 'memoryUsedMb', 'memoryTotalMb', 'mhz'], rows[0][1:]):
                    gpu[field] = number(value.strip())
            else:
                gpu['note'] = 'nvidia-smi could not read this GPU. Check the installed driver and permissions.'
        else:
            gpu['note'] = 'Install nvidia-smi from packages matching your existing NVIDIA driver. The app does not install or replace GPU drivers automatically.'
    else:
        gpu['note'] = 'No supported activity reader for this GPU. Intel, AMD and NVIDIA monitoring is supported when their drivers expose counters.'
    return gpu, []


def main():
    cpu = cpu_snapshot()
    tools = set()
    warnings = []
    if not shutil.which('lspci'):
        tools.add('pciutils')
    if not shutil.which('lscpu'):
        tools.add('util-linux')
    if cpu['temperature'] is None:
        warnings.append('No CPU temperature sensor is exposed. lm-sensors can help diagnose sensor/driver support; installing it cannot guarantee a reading.')
        if not shutil.which('sensors'):
            tools.add('lm-sensors')
    gpus = []
    for card in sorted(glob.glob('/sys/class/drm/card*')):
        if not re.fullmatch(r'card[0-9]+', os.path.basename(card)) or not os.path.exists(card + '/device'):
            continue
        gpu, required = gpu_snapshot(card)
        gpus.append(gpu)
        tools.update(required)
    if not gpus:
        warnings.append('No DRM GPU device is exposed. A headless server, virtual machine or unsupported driver may not provide GPU monitoring.')
    print(json.dumps({'cpu': cpu, 'gpus': gpus, 'tools': sorted(tools), 'warnings': warnings}, allow_nan=False))


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""Optional Steam artwork for the isolated TV UI harness, never bundled into the main APK."""
from pathlib import Path
import argparse
import concurrent.futures
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', required=True)
args = parser.parse_args()
root = Path(args.output).resolve()
root.mkdir(parents=True, exist_ok=True)
apps = [1091500, 1551360, 1086940, 730, 292030, 275850]

def fetch(pair):
    index, app = pair
    variants = {'cover': ['library_600x900_2x.jpg', 'library_600x900.jpg', 'header.jpg'],
                'hero': ['library_hero.jpg', 'header.jpg']}
    for kind, names in variants.items():
        dest = root / f'{kind}-{index}.jpg'
        if dest.exists():
            continue
        for name in names:
            try:
                url = f'https://cdn.akamai.steamstatic.com/steam/apps/{app}/{name}'
                with urllib.request.urlopen(url, timeout=15) as response:
                    data = response.read(8 * 1024 * 1024)
                if data.startswith((b'\xff\xd8', b'\x89PNG', b'RIFF')):
                    dest.write_bytes(data)
                    break
            except OSError:
                pass
        if not dest.exists():
            raise RuntimeError(f'Artwork unavailable for {app}; obtain a licensed local fixture instead.')

with concurrent.futures.ThreadPoolExecutor(max_workers=6) as workers:
    list(workers.map(fetch, enumerate(apps)))
print(root)

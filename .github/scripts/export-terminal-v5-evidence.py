#!/usr/bin/env python3
"""Export exact benchmark JSON and bounded publication records, never complete logs or APKs."""
import argparse
import base64
import hashlib
import json
import re
import zlib
from pathlib import Path

PART_CHARS = 2800
PARTS_PER_BATCH = 8
MAX_PARTS = 96


def partition(encoded):
    parts = [encoded[index:index + PART_CHARS] for index in range(0, len(encoded), PART_CHARS)]
    if len(parts) > MAX_PARTS:
        raise ValueError('Evidence exceeds the explicit 96-part bound; refusing truncation')
    return parts


def prepare(root, output, sha, run_id, scenario):
    groups = []
    for path in sorted(root.rglob('request.json')):
        folder = path.parent
        request = json.loads(path.read_text())
        manifest = json.loads((folder / 'manifest.json').read_text())
        if (request['sourceSha'] != sha or manifest['sourceSha'] != sha or
                request['runId'] != run_id or manifest['buildRun'] != run_id or
                request['scenario'] != scenario or request['suite'] != 'production-viewport-v5'):
            raise ValueError('Evidence source mismatch')
        documents = {str(item.relative_to(folder)): item.read_text()
                     for item in sorted(folder.rglob('*-benchmarkData.json'))}
        hashes = {name: hashlib.sha256(text.encode()).hexdigest() for name, text in documents.items()}
        records, terminal = [], []
        with (folder / 'instrumentation.log').open(errors='replace') as stream:
            for line in stream:
                if 'PUBLICATION_WORK ' in line or 'MEASURED_LAUNCH ' in line:
                    records.append(line.strip())
                elif re.fullmatch(r'(?:OK \([0-9]+ tests\)|INSTRUMENTATION_CODE: -?[0-9]+)\s*', line):
                    terminal.append(line.strip())
        extra = {}
        for name in ('device.json', 'installed-apks.json', 'validated.json', 'publication.json'):
            item = folder / name
            extra[name] = json.loads(item.read_text()) if item.exists() else None
        groups.append(dict(request=request, manifest=manifest, rawDocuments=documents, resultFiles=hashes,
                           publicationRecords=records, instrumentationResult=terminal, extra=extra))
    if not groups:
        raise ValueError('No matching V5 artifact group')
    data = json.dumps(dict(sourceSha=sha, runId=run_id, scenario=scenario, groups=groups),
                      separators=(',', ':'), ensure_ascii=False).encode()
    encoded = base64.b64encode(zlib.compress(data, 9)).decode()
    parts = partition(encoded)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'data.json').write_bytes(data)
    (output / 'parts.json').write_text(json.dumps(dict(sha256=hashlib.sha256(data).hexdigest(), parts=parts)))


def emit(output, scenario, batch):
    doc = json.loads((output / 'parts.json').read_text())
    if batch not in range(MAX_PARTS // PARTS_PER_BATCH) or len(doc['parts']) > MAX_PARTS:
        raise ValueError('Invalid evidence batch or part count')
    start = batch * PARTS_PER_BATCH
    for index in range(start, min(start + PARTS_PER_BATCH, len(doc['parts']))):
        title = f'V5 original {scenario} part {index + 1}/{len(doc["parts"])}'
        print(f'::notice title={title}::zlib-base64 sha256={doc["sha256"]}%0A{doc["parts"][index]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('prepare', 'emit'))
    parser.add_argument('--root', type=Path)
    parser.add_argument('--out', required=True, type=Path)
    parser.add_argument('--sha')
    parser.add_argument('--run-id')
    parser.add_argument('--scenario', required=True)
    parser.add_argument('--batch', type=int, choices=range(MAX_PARTS // PARTS_PER_BATCH))
    args = parser.parse_args()
    if args.action == 'prepare':
        prepare(args.root, args.out, args.sha, args.run_id, args.scenario)
    else:
        emit(args.out, args.scenario, args.batch)


if __name__ == '__main__':
    main()

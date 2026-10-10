"""Synthetic validator fixtures only; no Android build or emulator is started by these tests."""
import base64
import copy
import hashlib
import importlib.util
import json
import os
import subprocess
import tempfile
import unittest
import zlib
from pathlib import Path
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('paired_report', Path(__file__).with_name('run-terminal-pipeline-paired.py'))
paired = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(paired)
SHA, RUN = 'a' * 40, '1234'
BOOT = '11111111-2222-4333-8444-555555555555'
DEVICE = dict(fingerprint='fixture-android-34', size='1440x2560', density='560', ime='fixture.ime')


def manifests():
    result = {}
    for index, arm in enumerate(paired.ARMS, 1):
        result[arm] = dict(sourceSha=SHA, runId=RUN,
            target_debuggable=False, target_minified=False, target_release_derived=True,
            sources={paired.PAGE: paired.CONTROL_PAGE_SHA if arm == 'legacy' else paired.PRODUCTION_PAGE_SHA,
                     'shared-source.kt': '0' * 64},
            proot_overlay=dict(source_sha=SHA, build_run=RUN, overlay_only=True, overlay_sha256='e' * 64),
            apks=dict(target=dict(package=paired.ci.PACKAGE, sha256=str(index) * 64,
                                  certificate_sha256=['4' * 64], file=f'{arm}/target.apk', bytes=100),
                      test=dict(package=paired.ci.TEST_PACKAGE, sha256='3' * 64,
                                certificate_sha256=['4' * 64], file='common-test.apk', bytes=50)))
    return result


def samples(launch_number, arm):
    values = []
    for index, mode in enumerate(paired.ci.MODES):
        for phase in paired.ci.PHASES:
            for iteration in range(3):
                row = dict(mode=mode, phase=phase, iteration=iteration,
                    launch=f'{launch_number:08x}-0000-4000-8000-{index:012x}', frameRevision=12,
                    virtual=mode == 'lazyHistoryIme' or (mode == 'lazyHistory' and phase in ('mounted', 'remount')),
                    outputBytes=len(f'ECHO:{phase}-{iteration}\r\n'.encode()),
                    inputToDrawMs=8.0, inputToVisibleCheckMs=10.0, panelCompositions=3 if arm == 'legacy' else 2)
                row.update({stage: number + 2.0 for number, stage in enumerate(paired.ci.STAGES)})
                values.append(row)
    return values


def document():
    plan = dict(suite=paired.SUITE, sourceSha=SHA, runId=RUN, pairs=paired.PAIRS, manifests=manifests())
    doc = dict(suite=paired.SUITE, plan=plan, bootId=BOOT, device=DEVICE,
               preflights={}, warmups={}, invocations=[])
    sequence = 0

    def entry(arm, kind, pair=None, position=None):
        nonlocal sequence
        sequence += 1
        bundle = plan['manifests'][arm]['apks']
        row = dict(arm=arm, kind=kind, targetSha256=bundle['target']['sha256'], testSha256=bundle['test']['sha256'],
                   installedApkHashes={role: apk['sha256'] for role, apk in bundle.items()},
                   bootBefore=BOOT, bootAfter=BOOT, device=DEVICE,
                   startedNanos=sequence * 10, endedNanos=sequence * 10 + 5)
        if kind == 'transport':
            row['probes'] = [dict(probe=name, matched=True, timedOut=False, readerFailure=None) for name in sorted(paired.PROBES)]
        else:
            row['samples'] = samples(sequence, arm)
        if pair is not None:
            row.update(pair=pair, position=position)
        return row

    for arm in paired.ARMS:
        doc['preflights'][arm] = entry(arm, 'transport')
    for arm in paired.ARMS:
        doc['warmups'][arm] = entry(arm, 'warmup')
    for pair in range(paired.PAIRS):
        for position, arm in enumerate(paired.order(pair)):
            doc['invocations'].append(entry(arm, 'measurement', pair, position))
    return doc


class PairedPipelineTest(unittest.TestCase):
    def test_local_execution_is_rejected_before_build_or_guest_side_effects(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(paired, 'command') as command:
            with self.assertRaisesRegex(ValueError, 'only in GitHub Actions'):
                paired.build()
            with self.assertRaisesRegex(ValueError, 'only in GitHub Actions'):
                paired.emulator()
            command.assert_not_called()

    def test_control_patch_restores_only_the_frozen_subscription_site(self):
        before = paired.original_bytes()
        self.assertEqual(paired.PRODUCTION_PAGE_SHA, hashlib.sha256(before).hexdigest())
        self.assertEqual(paired.CONTROL_PAGE_SHA, hashlib.sha256(before.replace(paired.NEW.encode(), paired.OLD.encode())).hexdigest())
        text = paired.PATCH.read_text()
        self.assertEqual(1, text.count('diff --git'))
        body = text.split('\n@@', 1)[1].split('\n', 1)[1].splitlines()
        self.assertEqual(paired.NEW.splitlines(), [line[1:] for line in body if line.startswith('-')])
        self.assertEqual(paired.OLD.splitlines(), [line[1:] for line in body if line.startswith('+')])

    def test_valid_four_ab_ba_pairs_contain_288_measured_and_72_excluded_echoes(self):
        doc = paired.validate(document())
        self.assertEqual(288, sum(len(entry['samples']) for entry in doc['invocations']))
        self.assertEqual(72, sum(len(entry['samples']) for entry in doc['warmups'].values()))
        self.assertEqual(['legacy', 'candidate', 'candidate', 'legacy', 'legacy', 'candidate', 'candidate', 'legacy'],
                         [entry['arm'] for entry in doc['invocations']])

    def test_more_than_one_source_difference_or_unfrozen_page_is_rejected(self):
        for change in ('extra', 'missing', 'wrong-page', 'unchanged-page'):
            arms = manifests()
            if change == 'extra':
                arms['legacy']['sources']['shared-source.kt'] = 'f' * 64
            elif change == 'missing':
                del arms['candidate']['sources']['shared-source.kt']
            else:
                arms['legacy']['sources'][paired.PAGE] = '0' * 64 if change == 'wrong-page' else paired.PRODUCTION_PAGE_SHA
            with self.subTest(change=change), self.assertRaises(ValueError):
                paired.verify_sources(arms)

    def test_manifest_identity_signer_release_flags_and_common_driver_are_required(self):
        for field, value in (('sourceSha', 'b' * 40), ('runId', '9876'), ('target_debuggable', True),
                             ('target_minified', True), ('target_release_derived', False)):
            arms = manifests()
            arms['legacy'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                paired.verify_bundles(arms, check_files=False, expected_identity=(SHA, RUN))
        for target, field, value in (('target', 'sha256', '2' * 64), ('test', 'sha256', '9' * 64),
                                     ('target', 'package', 'wrong.package'), ('target', 'certificate_sha256', []),
                                     ('test', 'certificate_sha256', ['5' * 64])):
            arms = manifests()
            arms['legacy']['apks'][target][field] = value
            with self.subTest(target=target, field=field), self.assertRaises(ValueError):
                paired.verify_bundles(arms, check_files=False, expected_identity=(SHA, RUN))

    def test_overlay_difference_rejects_the_comparison(self):
        arms = manifests()
        arms['legacy']['proot_overlay']['overlay_sha256'] = 'f' * 64
        with self.assertRaisesRegex(ValueError, 'same overlay'):
            paired.verify_bundles(arms, check_files=False, expected_identity=(SHA, RUN))

    def test_missing_duplicate_and_reordered_invocations_are_rejected(self):
        for kind in ('missing', 'duplicate', 'order', 'wrong-pair', 'boolean-position'):
            doc = document()
            if kind == 'missing':
                doc['invocations'].pop()
            elif kind == 'duplicate':
                doc['invocations'][1] = copy.deepcopy(doc['invocations'][0])
            elif kind == 'order':
                doc['invocations'][2:4] = reversed(doc['invocations'][2:4])
            elif kind == 'wrong-pair':
                doc['invocations'][0]['pair'] = 3
            else:
                doc['invocations'][0]['position'] = False
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                paired.validate(doc)

    def test_mixed_installed_apk_boot_device_or_overlapping_receipt_is_rejected(self):
        for field, value in (('targetSha256', '0' * 64), ('testSha256', '1' * 64),
                             ('bootBefore', 'aaaaaaaa-0000-4000-8000-000000000000'),
                             ('bootAfter', 'aaaaaaaa-0000-4000-8000-000000000000'),
                             ('device', dict(DEVICE, density='640')), ('installedApkHashes', {}),
                             ('startedNanos', 1), ('endedNanos', True), ('kind', 'warmup')):
            doc = document()
            doc['invocations'][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                paired.validate(doc)

    def test_preflights_and_excluded_warmups_cannot_be_missing_failed_or_reused(self):
        for kind in ('no-preflight', 'no-warmup', 'probe-count', 'probe-failure', 'probe-timeout', 'probe-type', 'warmup-reused'):
            doc = document()
            if kind == 'no-preflight':
                del doc['preflights']['legacy']
            elif kind == 'no-warmup':
                del doc['warmups']['candidate']
            elif kind == 'probe-count':
                doc['preflights']['legacy']['probes'].pop()
            elif kind == 'warmup-reused':
                doc['invocations'][0]['samples'] = copy.deepcopy(doc['warmups']['legacy']['samples'])
            else:
                probe = doc['preflights']['legacy']['probes'][0]
                if kind == 'probe-timeout':
                    probe['timedOut'] = True
                else:
                    probe['matched'] = 1 if kind == 'probe-type' else False
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                paired.validate(doc)

    def test_exact_sample_matrix_and_integer_work_count_remain_required(self):
        for kind in ('missing', 'duplicate', 'counter-missing', 'counter-bool', 'backend', 'nan'):
            doc = document()
            rows = doc['invocations'][0]['samples']
            if kind == 'missing':
                rows.pop()
            elif kind == 'duplicate':
                rows[-1] = copy.deepcopy(rows[0])
            elif kind == 'counter-missing':
                del rows[0]['panelCompositions']
            elif kind == 'counter-bool':
                rows[0]['panelCompositions'] = True
            elif kind == 'backend':
                rows[0]['virtual'] = True
            else:
                rows[0]['inputToDrawMs'] = float('nan')
            with self.subTest(kind=kind), self.assertRaises((ValueError, KeyError)):
                paired.validate(doc)

    def test_summary_uses_paired_launch_medians_and_does_not_demand_speedup(self):
        doc = document()
        for entry in doc['invocations']:
            for row in entry['samples']:
                # Pair medians: legacy=10,20,100,110; candidate=11,31,90,190.
                times = (10, 20, 100, 110) if entry['arm'] == 'legacy' else (11, 31, 90, 190)
                time = times[entry['pair']] + (0, 0, 1000)[row['iteration']]
                row.update(inputToDrawMs=time, FRAME_DRAWN=time, inputToVisibleCheckMs=time + 5)
        row = paired.summary(doc)[0]
        self.assertEqual(4, row['pairs'])
        self.assertEqual(12, row['samplesPerArm'])
        self.assertEqual([1, 11, -10, 80], row['inputToDrawMs']['pairedDeltas'])
        self.assertEqual(6, row['inputToDrawMs']['pairedMedianDelta'])
        self.assertEqual(-1, row['panelCompositions']['pairedMedianDelta'])

    def test_canonical_digest_and_all_export_sections_reconstruct_exact_result(self):
        doc = document()
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'paired-results.json').write_text(json.dumps(doc))
            pieces = {}
            with patch.object(paired, 'OUT', folder), patch.object(paired, 'load_plan', return_value=doc['plan']), \
                    patch.object(paired.ci, 'notice') as notices:
                paired.report()
                paired.report(setup=True)
                for number in range(paired.PAIRS):
                    paired.report(pair=number)
                for call in notices.call_args_list:
                    level, title, message = call.args
                    self.assertLess(len(message), 2800)
                    if not message.startswith('zlib-base64 '):
                        continue
                    label, part = title.rsplit(' ', 1)
                    position, count = map(int, part.split('/'))
                    self.assertLessEqual(count, 8)
                    header, text = message.split('\n', 1)
                    pieces.setdefault(label, dict(expected=count, digest=header.split('=')[1], parts={}))['parts'][position] = text
            decoded = {}
            for label, value in pieces.items():
                self.assertEqual(set(range(1, value['expected'] + 1)), set(value['parts']))
                raw = zlib.decompress(base64.b64decode(''.join(value['parts'][i] for i in sorted(value['parts'])), validate=True))
                self.assertEqual(value['digest'], hashlib.sha256(raw).hexdigest())
                decoded[label] = json.loads(raw)
            provenance = decoded['Terminal paired pipeline provenance']
            setup = decoded['Terminal paired pipeline setup']
            reconstructed = dict(suite=paired.SUITE, plan=provenance['plan'], bootId=provenance['bootId'],
                device=provenance['device'], preflights=setup['preflights'], warmups=setup['warmups'],
                invocations=[e for number in range(paired.PAIRS)
                             for e in decoded[f'Terminal paired samples pair={number}']['invocations']])
            self.assertEqual(doc, paired.validate(reconstructed))
            self.assertEqual(provenance['resultSha256'], paired.result_digest(reconstructed))
            self.assertEqual(provenance['summary'], paired.summary(reconstructed))

    def test_normal_runner_retains_source_validation_before_paired_entrypoint(self):
        manifest = dict(sourceSha=SHA, runId=RUN, sources={'wrong': 'source'})
        with tempfile.TemporaryDirectory() as directory:
            folder = Path(directory)
            (folder / 'manifest.json').write_text(json.dumps(manifest))
            with patch.object(paired.ci, 'OUT', folder), patch.object(paired.ci, 'identity', return_value=(SHA, RUN)), \
                    patch.object(paired.ci, 'sources', return_value={}), patch.object(paired.ci, 'run_verified_bundle') as invoke:
                with self.assertRaisesRegex(ValueError, 'requested source'):
                    paired.ci.run()
                invoke.assert_not_called()

    def test_installed_target_hash_is_checked_before_permission_or_compilation(self):
        bundle = manifests()['candidate']
        with patch.object(paired.ci, 'identity', return_value=(SHA, RUN)), \
                patch.object(paired.ci, 'digest', return_value=bundle['apks']['target']['sha256']), \
                patch.object(paired.ci, 'output', side_effect=['', 'Success',
                    'package:/data/app/fixture/base.apk\n', 'f' * 64 + ' /data/app/fixture/base.apk']) as output:
            with self.assertRaisesRegex(ValueError, 'Installed pipeline APK differs'):
                paired.ci.run_verified_bundle(bundle)
            self.assertEqual(4, output.call_count)
            self.assertEqual(['adb', 'shell', 'sha256sum', '/data/app/fixture/base.apk'], output.call_args.args[0])

    def test_failed_legacy_build_reverses_patch_and_does_not_publish_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            page = root / paired.PAGE
            page.parent.mkdir(parents=True)
            page.write_bytes(paired.NEW.encode())
            output = root / 'report'
            build = root / 'build'
            ci_output = root / 'pipeline'
            ci_output.mkdir()
            log = root / 'artifacts/terminal-validation/gradle.log'
            log.parent.mkdir(parents=True)
            log.write_text('synthetic build log\n')
            manifest = manifests()['candidate']
            for role, apk in manifest['apks'].items():
                apk['file'] = role + '.apk'
                (root / apk['file']).write_bytes(role.encode())
            (ci_output / 'manifest.json').write_text(json.dumps(manifest))
            commands = []

            def fake_command(args, timeout=60):
                commands.append(args)
                if args[0] == 'git' and '--check' not in args:
                    page.write_bytes(paired.NEW.encode() if '--reverse' in args else paired.OLD.encode())
                if args[0] == 'bash' and page.read_bytes() == paired.OLD.encode():
                    raise subprocess.CalledProcessError(1, args)

            with patch.dict(os.environ, dict(GITHUB_ACTIONS='true', RUNNER_OS='Linux')), \
                    patch.object(paired, 'ROOT', root), patch.object(paired, 'OUT', output), patch.object(paired, 'BUILD', build), \
                    patch.object(paired, 'original_bytes', return_value=paired.NEW.encode()), \
                    patch.object(paired, 'command', side_effect=fake_command), \
                    patch.object(paired.subprocess, 'check_output', side_effect=[b'', paired.PAGE + '\n']), \
                    patch.object(paired.ci, 'identity', return_value=(SHA, RUN)), patch.object(paired.ci, 'OUT', ci_output), \
                    patch.object(paired.ci, 'prepare'):
                with self.assertRaises(subprocess.CalledProcessError):
                    paired.build()
            self.assertEqual(paired.NEW.encode(), page.read_bytes())
            self.assertFalse((output / 'plan.json').exists())
            self.assertTrue(any('--reverse' in args and '--check' not in args for args in commands))


if __name__ == '__main__':
    unittest.main()

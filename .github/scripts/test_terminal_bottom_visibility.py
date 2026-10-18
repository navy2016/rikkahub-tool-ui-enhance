"""Validate bottom-visibility evidence and wiring without an Android/Kotlin/native build."""
import copy
import importlib.util
import json
from pathlib import Path
import re
import unittest

SPEC = importlib.util.spec_from_file_location('bottom_reports', Path(__file__).with_name('terminal-bottom-visibility-reports.py'))
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)
ROOT = report.ROOT


def rectangles(control=False):
    keys = [('cursor', 'initial')] + [('tuiGrowth', p) for p in ('128', '512', '1024')]
    if not control:
        keys += [('cursorIme', p) for p in ('initial', 'open', 'chunkedLayers', 'lazyHistoryIme', 'closed')]
        keys += [('tuiFont', p) for p in ('12', '21', '14', 'alt', 'main')]
        keys += [('cursorState', p) for p in ('hidden', 'prompt')]
        keys += [('tuiReuse', str(p)) for p in range(8)]
    result = []
    for case, phase in keys:
        history = int(phase) if case == 'tuiGrowth' else 64
        bottom = 192 if not control else 210 + history
        result.append(dict(case=case, phase=phase, control=control, historyRows=history,
            row=history + 23, rowHeight=24, top=bottom - 24, bottom=bottom, viewport=200,
            scroll=history * 24, virtual=False, tui=case.startswith('tui'), writers=1, clippedExpected=control))
    return result


def lines(marker, values):
    return '\n'.join(marker + json.dumps(v) for v in values)


def pages():
    result = []
    for tui in (False, True):
        phases = ['history160'] + (['history560', 'history1160'] if tui else [])
        phases += ['imeOpen', 'imeClosed', 'statusHidden', 'statusShown', 'remount']
        values = [dict(phase=p, tui=tui, ime=p == 'imeOpen', top=168, bottom=192, rowHeight=24,
            viewport=200, historyRows=160, cursorRow=23, draw=i * 3 + 10, revision=i + 1, confirmations=2)
            for i, p in enumerate(phases)]
        result.append(dict(suite='terminal-bottom-page-v1', case='tui' if tui else 'cursor',
            geometry=values, nativePty=True, defaultRenderer=True, statusAndKeys=True, initialRows=24))
    return result


def page_text(values):
    return lines('BOTTOM_PAGE_RECT ', [r for value in values for r in value['geometry']]) + '\n' + lines('BOTTOM_PAGE ', values)


class BottomVisibilityReportTest(unittest.TestCase):
    def test_candidate_and_negative_control_remain_distinct(self):
        self.assertEqual(24, len(report.validate_rectangles(lines('BOTTOM_RECT ', rectangles()))))
        self.assertEqual(4, len(report.validate_rectangles(lines('BOTTOM_RECT ', rectangles(True)), True)))
        for candidate, control in ((rectangles(), True), (rectangles(True), False)):
            with self.assertRaises(ValueError):
                report.validate_rectangles(lines('BOTTOM_RECT ', candidate), control)

    def test_clipping_missing_rectangles_fake_height_and_multiple_writers_fail(self):
        for field, value in (('bottom', 999), ('top', -10), ('rowHeight', 0), ('viewport', True),
                             ('writers', 2), ('historyRows', -1), ('control', True), ('clippedExpected', True)):
            values = rectangles()
            values[0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                report.validate_rectangles(lines('BOTTOM_RECT ', values))
        for values in (rectangles()[:-1], rectangles() + rectangles()[:1]):
            with self.assertRaises(ValueError):
                report.validate_rectangles(lines('BOTTOM_RECT ', values))

    def test_old_control_requires_actual_growing_clipping_not_only_its_label(self):
        values = rectangles(True)
        for row in values:
            row.update(top=168, bottom=192)
        with self.assertRaises(ValueError):
            report.validate_rectangles(lines('BOTTOM_RECT ', values), True)
        values = rectangles(True)
        last = values[-1]
        last.update(top=314, bottom=338)
        with self.assertRaises(ValueError):
            report.validate_rectangles(lines('BOTTOM_RECT ', values), True)

    def test_two_pages_require_fourteen_independent_actual_rectangles(self):
        self.assertEqual(2, len(report.validate_page(page_text(pages()))))
        for mutate in (
            lambda p: p[0]['geometry'].pop(),
            lambda p: p[1]['geometry'][0].update(bottom=300, top=276),
            lambda p: p[1]['geometry'][1].update(draw=10),
            lambda p: p[0]['geometry'][0].update(confirmations=1),
            lambda p: p[0].update(statusAndKeys=False),
        ):
            values = pages()
            mutate(values)
            with self.assertRaises(ValueError):
                report.validate_page(page_text(values))
        with self.assertRaises(ValueError):
            report.validate_page(lines('BOTTOM_PAGE ', pages()))


class BottomVisibilityWiringTest(unittest.TestCase):
    def test_exact_case_inventory_and_workflow_routing(self):
        source = (ROOT / report.TEST_FILE).read_text()
        self.assertEqual(set(report.CASES), set(re.findall(r'@Test\s+fun\s+(\w+)\(', source)))
        page = (ROOT / 'app/src/terminaltestAndroidTest/java/me/rerere/rikkahub/pipeline/TerminalBottomVisibilityPageTest.kt').read_text()
        self.assertEqual(set(report.PAGE_METHODS), set(re.findall(r'@Test\s+fun\s+(\w+)\(', page)))
        host = (ROOT / 'app/src/test/java/me/rerere/rikkahub/data/container/TerminalBottomVisibilityTest.kt').read_text()
        self.assertEqual(10, len(re.findall(r'@Test\s+fun\s+(\w+)\(', host)))
        workflow = (ROOT / '.github/workflows/terminal-emulator-tests.yml').read_text()
        preparation = workflow.split('- name: Create dummy google-services.json', 1)[1].split('- name:', 1)[0]
        self.assertNotIn('report-terminal-tests.py', preparation)
        focused = workflow.split('- name: Run bottom visibility semantics and affected viewport contracts once', 1)[1].split('- name:', 1)[0]
        reporting = workflow.split('if [ "$TERMINAL_UNIT_SUITE" = bottomVisibility ]; then', 1)[1].split('\n          fi', 1)[0]
        self.assertEqual(set(re.findall(r'--tests (\S+)', focused)), set(re.findall(r'--require-suite (\S+)', reporting)))
        view = (ROOT / '.github/workflows/terminal-viewport-interactions.yml').read_text()
        self.assertIn('baseline=' + report.BASELINE, view)
        self.assertIn('TERMINAL_BOTTOM_CONTROL:', view)
        self.assertIn('terminal-bottom-visibility-reports.py --control', view)

    def test_production_targets_and_visible_height_use_real_geometry(self):
        main = ROOT / report.MAIN
        binding = (main / 'ui/pages/container/TerminalViewportBinding.kt').read_text()
        self.assertIn('val measuredEager = !virtual', binding)
        self.assertNotIn('val measuredEager = !virtual &&', binding)
        self.assertIn('requireMeasuredGeometry = current.measureEager', binding)
        geometry = (main / 'data/container/TerminalEagerViewportGeometry.kt').read_text()
        self.assertIn('historyPrefix.totalHeightPx', geometry)
        self.assertIn('frame.terminalFollowRowIndex()', geometry)
        self.assertNotIn('screenStartRow *', geometry)
        item = (main / 'data/container/TerminalItemViewport.kt').read_text()
        self.assertIn('val followRowIndex: Int? get() = frame.terminalFollowRowIndex()', item)
        page = (main / 'ui/pages/container/ProcessSessionPage.kt').read_text()
        self.assertIn('outputViewportHeightPx = size.height', page)
        self.assertNotIn('outputViewportHeightPx = viewportHeightPx', page)
        self.assertIn('automaticImeAvoidance = true', page)
        self.assertIn('8.dp.roundToPx()', page)
        self.assertEqual(1, binding.count('runTerminalViewportScrollEffects('))
        self.assertNotIn('eager.scrollTo(', page)


if __name__ == '__main__':
    unittest.main()

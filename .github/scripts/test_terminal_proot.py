import hashlib
import importlib.util
import os
import re
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('proot_builder', Path(__file__).with_name('build-terminal-proot.py'))
builder = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(builder)


class TerminalProotOverlayTest(unittest.TestCase):
    def test_build_refuses_local_execution_before_writing(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(builder, 'command') as command:
            with self.assertRaisesRegex(RuntimeError, 'only in GitHub Actions'):
                builder.main()
            command.assert_not_called()

    def test_patch_contains_only_guarded_fork_rewrite(self):
        text = builder.PATCH.read_text()
        self.assertEqual(1, text.count('diff --git'))
        self.assertIn('a/src/tracee/seccomp.c b/src/tracee/seccomp.c', text)
        body = text.split('\n@@', 1)[1].split('\n', 1)[1].splitlines()
        self.assertEqual(6, sum(line[:1] in (' ', '-') for line in body))
        self.assertEqual(22, sum(line[:1] in (' ', '+') for line in body))
        added = '\n'.join(line[1:] for line in body if line.startswith('+'))
        self.assertIn('#if defined(ARCH_X86_64)', added)
        self.assertIn('case PR_fork:', added)
        self.assertIn('set_sysnum(tracee, PR_clone);', added)
        self.assertIn('poke_reg(tracee, SYSARG_1, SIGCHLD);', added)
        for index in range(2, 6):
            self.assertIn(f'poke_reg(tracee, SYSARG_{index}, 0);', added)
        self.assertIn('restart_syscall_after_seccomp(tracee);', added)
        self.assertNotIn('PR_vfork', added)
        self.assertNotIn('PROOT_NO_SECCOMP', added)

    def test_pinned_library_is_repository_asset_and_sources_are_commits(self):
        self.assertRegex(builder.SOURCE_SHA, r'^[a-f0-9]{40}$')
        library = ROOT / 'app/src/main/assets/proot/libtalloc-x86_64.so.2'
        self.assertEqual(builder.LIB_SHA, hashlib.sha256(library.read_bytes()).hexdigest())
        self.assertEqual(ROOT / 'app/build/generated/pipelineAssets/proot/proot-x86_64', builder.OVERLAY)

    def test_overlay_is_only_on_opt_in_test_variant(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        lines = [line.strip() for line in gradle.splitlines() if 'generated/pipelineAssets' in line]
        self.assertEqual(1, len(lines))
        self.assertTrue(lines[0].startswith('if (terminalPipelineTests) getByName("terminaltest")'))
        main = (ROOT / 'app/src/main/java/me/rerere/rikkahub/data/container/PRootManager.kt').read_text()
        self.assertNotIn('pipelineAssets', main)
        self.assertNotIn('fork-to-clone', main)


if __name__ == '__main__':
    unittest.main()

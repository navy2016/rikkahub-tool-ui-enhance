#!/usr/bin/env bash
# Keep compiler diagnostics reachable via the check-run API when artifact/log downloads are unavailable.
set -uo pipefail
mkdir -p artifacts/terminal-validation
./gradlew "$@" --console=plain 2>&1 | tee artifacts/terminal-validation/gradle.log
result=${PIPESTATUS[0]}
if [ "$result" != 0 ]; then
  python3 - <<'PY'
from pathlib import Path
text = Path("artifacts/terminal-validation/gradle.log").read_text(errors="replace")
errors = "\n".join(line for line in text.splitlines() if line.startswith("e:"))
reason = text.split("* What went wrong:")[-1].split("* Try:")[0]
message = (errors + "\n" + reason)[:3000]
print("::error title=Terminal validation Gradle failure::" + message.replace("%", "%25").replace("\n", "%0A").replace("\r", "%0D"))
PY
fi
exit "$result"

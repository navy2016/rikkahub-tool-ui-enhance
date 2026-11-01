# C1 visible geometry proof sharing — mechanism passed, not adopted

Source`db6f041f92e9bdc8db26a348c573dbaf92b5d5e8`, run[41335095300](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/41335095300)
passed528Release JVM methods(504original+24new) and44actual-layout component methods(37original+7new).
All original JUnit/report/fixture-source/logcat records were independently checked against the frozen
inventory and check annotations. The17new scene receipts match exactly, with no missing/extra/skipped
method and no claim of timing improvement. Production remains306/main treeac977984; C1 is not adopted.

## Mechanism and scope

Four isolated assets under`benchmarks/terminal-geometry-c1/candidate`, identity SHA256
`4b7e565a88d3f531c7c261ec9e3cb1f9ec2bfb3bf2e981d653863591fc9a4ab9`.
Protocol SHA256`387ca145acf8953fa700854312c266c73f4e0bf6b17d596f493797415fca5ba0`.

Live FULL_VIRTUAL observation still checks current layout keys, Text, metrics, actual item heights and
physical geometry on EVERY read. It detaches a private scalar row array and validates order/range once.
Only policy copies reuse that immutable proof; unchanged policy can return the same observation.
Changed policy still creates a new observation. Generic mutable lists and old backends keep original
validation. No frame/Text/LazyList item retention, global prefix, new writer or skipped controller intent.

Tests prove256same-policy applications reuse the same observation, while a second live read produces
a fresh vector. Native Compose scroll offsets, stale keys/Text/font/heights, MAIN/ALT/FIFO, font/density/
RTL/viewport/IME policy, old renderer/default and session replacement all preserve geometry boundaries.
Existing selection/copy/handle/mouse/IME,20item-native,8measurement,3binding tests also passed.
The JVM oracle compares500valid and1000malformed generated vectors to original acceptance/geometry.

This is NOT evidence of end-to-end benefit: no real-PTY page performance experiment, full182final
regression or formal Release was run. Original A1/B1 remain rejected; no candidate is combined.

## Original evidence

- JVM artifact13094519563,70707bytes, SHA256`33d9aff43742fac14787c36a76743fe01acb3e63d0bdd223d3579eb7d10096e8`.
- Component artifact13094789419,12870bytes, SHA256`6721527ff998dc17d7611ee008efa738024b244e498decddea9f33ef6ac0990f`.
- Read-only exact-source relays41336132955/41336337026 succeeded. No APK download or resampling.
- Workspace`/workspace/terminal-review/full-virtual-geometry-c1`, `verified-evidence/verify_evidence.py`
  verifies source hashes, both report/annotation/JUnit inventories and all17raw lifecycle records offline.
- A transient GitHub GET connection reset was retried only before dispatch; no POST or device run was repeated.

Next: qualify a separately frozen trace lifecycle using unchanged306, before any C1 performance protocol.
Do not retrofit B1's parser or reinterpret rejected windows as passing. Mechanism success alone does not authorize adoption.

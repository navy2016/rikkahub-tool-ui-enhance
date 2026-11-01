# B1 physical metrics performance V1 — rejected, production unchanged

## Decision and scope

**Do not adopt B1.** Source`a5ed6075853a155028c213a015de500ddc07672f`, run[41300006846](https://github.com/navy2016/rikkahub-tool-ui-enhance/actions/runs/41300006846)
finished with`B1 frame identity evidence incomplete; originals kept,no replacement sampling`.
Independent replay of ALL3360windows/96traces/8invocations exactly matches CI, including every null.
`dataComplete=false`, `passesFrozenPerformanceScreen=false`. No retry/replacement measurements,
threshold/parser/candidate changes, APK delivery or production adoption. Production remains
`306e0b80b508c961bc00f2d4b34a2688e5857a1a`, main tree`ac97798416997a8230c9fa2a5a8569cf0ed0a911`.

Mechanism529JVM/28component/24scene remains passed. This does not establish end-to-end gain.
The96native test cases, setup gates, full24redraw-row checks and final MAIN history identities passed;
trace qualification and performance are separate. No final182viewport round or formalRelease is warranted.

## Frozen identity

| Item | Identity |
|---|---|
| B1 candidate freeze | `f9c7e03643be06625c164ee0c4ef8359063941c1` |
| Timing driver/protocol freeze | `6ecaa60db0eb45f71aa6cd15bcfd0fca076bbee6` |
| Protocol SHA256 | `55f7d7b1edece23f2ac3a416cc7091d2315b9097cb6154270905ebbe75ec5f5a` |
| Build contract SHA256 | `14a6d4cfdf45f58a1ba6ee9280425ecbe0fd3f1f6be5a89d2fc50abfe005f610` |
| Original results SHA256 | `0987813a06e2612142700b3b60343471d4af974643e79b36baefc0c448c63554` |
| Artifact | `13084527235`,542237998bytes |
| Original archive SHA256 | `c290e4ba744fd5b65dac1441359a922c35ae95dc6073d2679fe6cefc38861707` |
| Independent per-slot verifier SHA256 | `5971d0f966787c1d6554e3e33d5d55dae6151e0e6745625fb1e843e55fa38ec9` |

Both Release-derived targets share the original driver, signer, native/Web/runtime assets and own-DEX
qualified profiles. **Raw assets also match byte-for-byte.** Local replay validates full symbolic/profile
receipts and bound APK hashes; actual DEX/APK binary checks were performed in CI, not downloaded locally.

## Complete inventory and integrity outcome

-4AB/BA pairs ×12native process cases;3360=160warmup+3200formal windows,20formal per condition/arm/pair.
-All96raw traces and96method logs independently checked, all8original results hashed and reconciled.
-4759qualified UI attempts;4001exact submitted and4001presented attempts. Zero`NO_SUBMISSION` attempts.
-FrameMetrics:3998`BOTH_EXACT`,3`ABSENT`,758`UNLINKED` due to trace-integrity rejection; no conflict.
-Eight unqualified traces invalidate462windows:440formal+22warmup.2898windows/2760formal windows have
  complete endpoints, but no missing window was discarded to fabricate a passing aggregate.

| Invocation | Affected case(s) | Invalidated windows |
|---|---|---:|
| pair0 legacy | MAIN1k shown;guard1k hidden/shown;guard10k hidden |210|
| pair0 candidate | guard1k shown;guard10k hidden |126|
| pair1 legacy | guard1k shown |63|
| pair3 legacy | guard1k shown |63|

Raw evidence contains seven`previous_packet_dropped=1` markers, all with
`first_packet_on_sequence=0` and6..17already observed packets on that sequence. They do not meet the
frozen initial-packet exception. These packets carry ProcessTree field2; the specific producer-side
cause is not proven, and no claim is made that measured frame packets themselves were lost.

Two relevant unclosed spans are`Choreographer#doFrame 8693` and`Choreographer#doFrame 44993`, starting
2734530007ns and5783747877ns AFTER their final timed cutoff. One trace has both fault types.
The frozen parser rejects the entire trace before frame-chain classification. We do not weaken that
rule after observation, and do not describe462windows as actual failed submissions.

All96original Perfetto start/stop/wait/pull receipts succeed; traces remain below96MiB. Successful
collection is not proof of complete trace content. Long-wall diagnostics are not CPU attribution.

## Performance screen also fails on decidable statistics

Each ratio is the median of FOUR paired within-arm20formal-window median ratios, not a pooled ratio.
Redraw submission/UI targets≤0.80 and≥3/4winning pairs; all system medians≤1.05. Lower is better.

| Case | Submission ratio | UI ratio | System-end ratio |
|---|---:|---:|---:|
| MAIN1k hidden |0.997733|1.012032|0.961422|
| MAIN1k shown |null|1.022742|null|
| MAIN10k hidden |1.213274|1.259627|1.013656|
| MAIN10k shown |1.037551|1.008725|1.092484|
| ALT1k hidden |0.941000|0.915769|0.989661|
| ALT1k shown |1.083727|1.088353|1.019794|
| ALT10k hidden |0.930532|0.930778|0.992973|
| ALT10k shown |1.275876|1.191800|1.103511|

None qualifies for the target gain; MAIN1k shown additionally lacks a complete submission aggregate.
The best complete submission ratio is0.930532, not0.80. MAIN10k hidden and ALT10k shown are regressions
in the fixed observations. This does not prove which implementation cost caused them.

There are103frozen gate failures:30numeric(17median,5sample-p95,8worst-pair) and73integrity/missing-value
failures(1global,20median-null,20p95-null,32worst-pair-null). Null-related failures are NOT counted as
measured slowdowns. Full ratios, all20-window samples and every failed pair remain in originals/CSV.
The samplep95 is rank19of20, not a population-percentile estimate. Return or other gains never offset
redraw/guard failures. No phoneFPS, photon time, CPU saving or formal-minified-APK claim is made.

## Evidence delivery and defects

Original relay41316773111 failed its historical400MiB cap. Commit770ae1d grants ONLY this exact
run/source/artifact/name/digest/size permit;7policy tests and transport-only relay41317096277 succeeded.
Other archives retain400MiB; no APK was rewritten/downloaded. The original failures remain unchanged.

Only9of10check-summary fragments are visible. Full artifactsummary canonical hash and all9available
fragments match; missing part10 was not manufactured. Future publication must budget annotations
separately. A local bursty-metadata range reader hit429 and was fixed with pacing/Retry-After handling;
all195additional provenance records were subsequently recovered, without new sampling.

Work directory:`/workspace/terminal-review/full-virtual-physical-metrics-performance-v1`.
`verified-report.json`,`verified-windows.csv`,`verified-incomplete-windows.json` and8raw-shard receipts
form the closed inventory. `raw-shards/slot-0..7.zip` retain exact200original members in lossless
containers, including all96traces. SHA-bound source capsule and`replay_archives.py --slot N`/`--all`
permit offline replay using standard-library Python, one shard at a time. No network/build is needed.
`merge_verified.py` rejects missing/duplicate/mixed slots and verifies full cross-arm device identity.

See`benchmarks/TERMINAL_BUG_LOG.md`. A1 stays rejected. Any next candidate starts independently from306;
trace lifecycle qualification must be established before another frozen performance protocol.

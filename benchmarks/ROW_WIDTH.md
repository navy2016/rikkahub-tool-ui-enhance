# Exact single-paragraph row-width experiment

The shipped BOM `2026.03.00` resolves `ui-text` to **1.10.5**. In that version
`MultiParagraphIntrinsics` normalizes paragraph ranges, constructs a substring
`AnnotatedString` and a `ParagraphIntrinsicInfo` list, then invokes the platform
`ParagraphIntrinsics`. Its maximum is the maximum of those paragraph widths.
For a nonempty emulator-owned physical row without paragraph styles or separators,
there is exactly one paragraph. Calling the same platform factory with its unchanged
SpanStyles omits the multi-paragraph wrappers, not shaping, bidi or integer rounding.

`TerminalRowWidthMeasurer` belongs to one synchronous width-index pass. It creates no
retained text/layout cache, estimates no character advance, never trims whitespace and
never changes visible Text rendering. Only emulator-owned `TerminalFrameRows` qualifies;
its producer emits SpanStyles and optional URL StringAnnotation, the latter not affecting
width. Synthetic/replaced frames keep the old MultiParagraph path with all annotations,
including LinkAnnotation and Bullet. Empty text and Unicode paragraph separators also
keep that path. Existing metric/font invalidation and FIFO/screen caches are unchanged.

The viewport suite retains seven existing width tests and adds three: exact comparison
with uncached TextMeasurer and the frozen multi-paragraph oracle across fonts, densities,
directions, ANSI, cursor, palette, URL, CJK, combining marks and emoji; conservative complex
annotation/empty/separator fallback; and four alternating old/new passes per history size
(1k/5k/10k plus six screen rows). Every candidate width vector is asserted identical to
the control, then exported as SHA-256 of big-endian 32-bit integer widths after timing.

Both paths receive excluded typeface warmup. Every timed pass measures every row with no
width-index, TextMeasurer or Paragraph cache. Android's normal font caches remain warm.
These are uncached row-width passes, not cold process startup or full-page FPS. Timings
can increase; the report validator does not require a speedup. Adoption requires correct
widths and a measured benefit, plus production viewport regression and Release validation.

The reference `TerminalIntrinsicWidthMeasurer.kt` stays unchanged at SHA-256
`c215a3537a3824ecfecba7bb2645f117a0d32fc97246e17cdbf619ac726a30a2`.
The reporter requires all ten exact JUnit cases, 24 unique alternating samples, expected
single/multi factory call counts, equal vector hashes for each history size, and source
copy/APK hashes. Scalar logcat evidence is saved immediately after this suite.

Upstream sources inspected, without a local Android build:

- https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.03.00/compose-bom-2026.03.00.pom
- https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text/1.10.5/ui-text-1.10.5-sources.jar
- https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.5/ui-text-android-1.10.5-sources.jar

Do not use `androidx-main` signatures for this dependency: later versions add `softWrap`
and locale arguments that do not exist in 1.10.5.

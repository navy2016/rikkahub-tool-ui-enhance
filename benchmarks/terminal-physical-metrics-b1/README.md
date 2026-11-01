# B1 physical-row measurement products — isolated from306

Status: mechanism candidate, not performance-qualified or adopted. A1/P2 remain rejected.

The existing FULL_VIRTUAL physical-height calculation already constructs a TextLayoutResult. B1
retains only its scalar natural width alongside the existing text/height entry (at most80rows).
The natural width is ceil(multiParagraph.intrinsics.maxIntrinsicWidth), not constraint-clamped size.width.

Only owned current rows and canonical packaged-font metrics can share. Results check exact weak
frame identity, metric equality, line ID and complete displayed/frame Text equality. Fonts/metrics,
unowned/complex input, same-revision new frames and held blank Text cannot bypass validation.
No TextLayoutResult/Paragraph/history document is stored. The height cache and returned product share
one bounded map. Existing MAIN screen-width reuse runs first, before consulting the product.

The index never retains the provider. Cold workers receive a captured immutable product; their
existing history/metric request and epoch/cancellation rules remain. No new effect key on every
screen output. ALT still clears history widths exactly as306: B1 is NOT an A1 lifecycle retry.

Five replacement assets, an exact patch/hash manifest, 25new scalar JVM methods and 7new real-font
component methods are isolated here. Mechanism CI runs original504+25JVM and original21+7device
methods, including6font-resolution/async guards. Production remains306 before/after every overlay.

The24device scenes assert routing, same-frame forced blanks, shape/height oracles, invalidation,
remount and bounded registration. No benchmark timing or speedup claim is part of this suite.
Only after mechanism/geometry validity is established may a new independent B performance
protocol be frozen. No A1 resampling, B/C stacking, dependency change, local APK, or formal release.

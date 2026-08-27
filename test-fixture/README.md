# test-fixture

A minimal Katalon Studio project used only by
`.github/workflows/katalon-compat-check.yml` to verify the bridge still
works against a real Katalon Studio install, across Windows, macOS, and
Linux, and to catch it if a future Katalon Studio release silently changes
something the bridge depends on.

Not part of the published npm package (`package.json`'s `files` field
doesn't include it) and not meant to be installed into by hand. The
`kzissues` Test Suite Collection under `Test Suites/TestSuitesFolder/` is
a trimmed copy of the same one used for manual multi-suite testing during
development: two suites run twice with different browsers, one suite
inside a nested folder, one API-only suite, and one WebUI suite, the same
shape that has caught real bugs before (report misnaming on nested suites,
a report-folder-resolution issue on Windows CI).

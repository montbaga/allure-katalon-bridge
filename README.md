# Allure-Katalon Bridge

Turn any Katalon Studio project into an Allure-reporting project by
double-clicking one file - no plugin installation, no OSGi packaging, no
command line, no changes to existing Test Cases or Test Suites.

See [`ARCHITECTURE.md`](ARCHITECTURE.md) for how this is actually built
under the hood.

## Install / Uninstall

Open the folder matching your OS - it contains only what you need, nothing
from the other platforms. Each section below is self-contained: the quick
double-click way, and the scripted/CI way with the same flags. Prefer one
command that works the same on every OS instead? See npm below.

<details>
<summary><b>📦 npm (any OS)</b></summary>

Needs Node.js. Works identically on Windows, macOS, and Linux - useful for
CI, or if you'd rather not pick an OS-specific script.

```
npx allure-katalon-bridge install "/path/to/your/katalon/project"      # add --force to also overwrite a customized allure.properties
npx allure-katalon-bridge uninstall "/path/to/your/katalon/project"    # add --remove-config to also delete allure.properties/categories.json
```

`npx` fetches and runs it without installing anything globally. To install
it once and reuse it: `npm install -g allure-katalon-bridge`, then run
`allure-katalon-bridge install ...` directly.
</details>

<details>
<summary><b>🪟 Windows/</b></summary>

**Just click:** double-click **`Windows\Install.bat`**, then pick your
Katalon project folder in the dialog that opens. Uninstall the same way
with **`Windows\Uninstall.bat`**.

**Drag-and-drop:** drop your project folder onto `Install.bat` (or
`Uninstall.bat`) to skip the dialog entirely.

**Scripted / CI** (run from the repo root):
```powershell
.\Windows\install.ps1 -ProjectPath "C:\path\to\your\katalon\project"      # add -Force to also overwrite a customized allure.properties
.\Windows\uninstall.ps1 -ProjectPath "C:\path\to\your\katalon\project"    # add -RemoveConfig to also delete allure.properties/categories.json
```
</details>

<details>
<summary><b>🍎 macOS/</b></summary>

**Just click:** double-click **`macOS\Install.command`**, then pick your
Katalon project folder in the dialog that opens. Uninstall the same way
with **`macOS\Uninstall.command`**.

**Scripted / CI** (run from the repo root) - macOS shares the Linux bash
engine below, since bash itself is identical on both:
```bash
./Linux/install.sh /path/to/your/katalon/project      # add --force to also overwrite a customized allure.properties
./Linux/uninstall.sh /path/to/your/katalon/project    # add --remove-config to also delete allure.properties/categories.json
```
</details>

<details>
<summary><b>🐧 Linux/</b></summary>

Run from the repo root:
```bash
./Linux/install.sh /path/to/your/katalon/project      # add --force to also overwrite a customized allure.properties
./Linux/uninstall.sh /path/to/your/katalon/project    # add --remove-config to also delete allure.properties/categories.json
```
Run either script with no path argument and it'll prompt you to paste one instead.
</details>

Uninstalling only removes what was installed, and keeps your
`allure.properties`/`categories.json` and `allure-results/` in place unless
you pass the force/remove-config flag shown above.

That's it. Reopen the project in Katalon Studio and run a test suite as
normal. When it finishes, a single, self-contained report file is already
sitting in **`allure-report/<Name>_<timestamp>.html`** - generated
automatically, no click, no script. Just double-click it - it opens
directly in your browser like any other HTML file, because all of its
data is embedded inline (Allure's native `--single-file` mode). No server,
no `View Allure Report` script needed for this - unlike a plain
`allure generate` report, whose `index.html` fails with a blank page /
"Failed to fetch" if you open it directly instead of serving it.

## Why this exists

Katalon Studio has no first-party Allure adapter (unlike TestNG/JUnit).
Most DIY attempts to bolt one on hit the same wall of problems - this
package solves all of them:

| Problem | How this solves it |
|---|---|
| No hook to drive Allure's lifecycle from Katalon | Uses Katalon's public, documented Test Listener API (`@BeforeTestSuite`/`@BeforeTestCase`/`@AfterTestCase`/`@AfterTestSuite`) |
| Allure's transitive Jackson clashes with Katalon's bundled Jackson | Ships only `allure-java-commons` + `allure-model`, whose Jackson is shaded/relocated internally - verified by inspecting the jar, not assumed |
| Results land in a different folder depending on how the suite was launched (IDE vs CLI vs CI) | Results directory is resolved explicitly against the project root, not the process's working directory |
| A reporting bug could fail or change the outcome of a real test | Every hook catches its own exceptions and only logs a warning |
| Can't afford to touch thousands of existing test cases | Fully automatic at the suite/case level; step-level detail is opt-in |

## Requirements

- Katalon Studio (tested on 11.4.0 locally and 11.3.0 in CI; uses only long-stable public APIs)
- Windows or macOS to use the double-click installer as-is; Linux works via `Linux/install.sh` in a terminal (PowerShell/bash ship with the OS either way - nothing extra to install for the installer itself)
- [Allure commandline](https://allurereport.org/docs/install/) installed (`npm install -g allure-commandline`) - used to auto-generate the HTML report after each suite and to view it. The bridge auto-detects it (PATH, common install locations, or your login shell on macOS/Linux - see Troubleshooting below); if it still can't find it, set `allure.commandline.path` or the bridge logs one warning and otherwise behaves normally (raw `allure-results/` JSON still gets written either way)

Verified end-to-end on Azure Pipelines, GitHub Actions, and GitLab CI - see [CI setup](#ci-setup) below for a ready-to-copy config for each.

## What the installer actually does

1. Verifies the target folder is a real Katalon project (looks for a `*.prj` file) before writing anything - refuses to run otherwise.
2. Copies the Test Listener, Keywords, config, and Drivers jars into it.
3. Leaves an existing, customized `allure.properties` alone (pass `-Force` / `--force` to overwrite it too).
4. Records everything it installed in `<project>/.allure-bridge/manifest.txt`, so uninstall can remove exactly that later - nothing else in the project is ever touched.
5. Registers the two jars in `.classpath` if one exists, so Katalon's editor resolves the Allure classes without a manual refresh.

Re-running install against the same project **upgrades** it in place.
Uninstall removes exactly what's in the manifest; `allure-results/`
(generated test output) and your `allure.properties` are kept by default.

## What gets installed

```
Test Listeners/AllureTestListener.groovy      auto-discovered by Katalon - the only wiring needed
Keywords/allure/AllureReportBridge.groovy     engine: status mapping, attachments, environment/executor/categories files
Keywords/allure/AllureConfig.groovy           allure.properties reader, with ALLURE_* env var overrides
Keywords/allure/AllureKeywords.groovy         optional: step(), attachText/Json/Html/File/Screenshot, epic/feature/story/severity/label/link/issue/tmsLink/parameter
Include/config/allure/allure.properties       configuration (results dir, screenshot policy)
Include/config/allure/categories.json         failure categorization tuned to Katalon/Selenium exception types
Drivers/allure-java-commons-2.35.4.jar        Apache-2.0, Qameta Software - the only 2 extra jars needed
Drivers/allure-model-2.35.4.jar
Drivers/fetch-allure-jars.ps1                 re-download the 2 jars from Maven Central (sha1-verified) if your org won't commit binaries to git
View Allure Report.bat / .command             optional convenience: opens the most recent report for you (Windows / macOS)
view-allure-report.sh                          same, for Linux/CI or manual use
```

### Do you actually need "View Allure Report"?

No, not by default. The report is a single `.html` file with everything
embedded inline (Allure's native `--single-file` mode) - just double-click
it directly, same as opening any other HTML file, from `allure-report/`.
`View Allure Report` is a convenience that finds the newest one for you so
you don't have to hunt through timestamped filenames, nothing more.

It still matters if you set `allure.report.single.file=false` (only
worth doing for very large suites, where one huge HTML file gets slow to
load): that mode produces a `<Name>_<timestamp>/` **folder** instead,
and its `index.html` genuinely cannot be opened directly - browsers block
a multi-file report's local JSON fetches over `file://` (a browser
security restriction, not an Allure or Katalon quirk). `View Allure
Report` runs `allure open` for that case, which serves it over a small
local `http://` server instead.

## Using it

**Zero-touch (default):** every test suite run automatically produces one
Allure result per test case - status, timing, a failure screenshot
(WebUI only) and stack trace on failure, suite/host/thread/framework
labels, and a stable history ID so retries and repeat runs show up as
history/trend in the report. A self-contained `allure-report/<Name>_
<timestamp>.html` is generated automatically too, at the end of the run
(requires the Allure commandline to be installed and found - see Requirements), with the
Trend/History graphs carried forward from the previous run.

`<Name>` is whatever you actually ran:

- **A Test Suite Collection** running multiple Test Suites -> named after
  the collection, with all of its Test Suites combined into **one**
  report (not one per Test Suite). Katalon has no public API that hands a
  collection's name to a Test Listener, so the bridge derives it from the
  run's own report folder structure instead - the only place that name is
  available at all, and reliable regardless of how many suites the
  collection has or how fast the machine running it is.
- **A single Test Suite** -> named after that suite.
- **A lone Test Case** run directly, no saved suite involved -> named
  after that test case.

A Test Suite that actually opens a browser shows it right in its name in
the Suites view (e.g. `API Test Suite (Chrome)`), and the Environment
panel lists every browser actually used across the run (e.g.
`Chrome, Firefox`) instead of just whichever suite happened to start
last. A suite that never opens a browser - an API-only test case, say,
even one sharing a Run Configuration that has a browser nominally
selected - doesn't get one shown, since it never actually used it. If a
Collection runs the same Test Suite more than once - same browser or
different - each occurrence shows up as its own entry instead of being
merged into one.

Each report reflects only the run it's named after - `allure-results/` is
cleared of unrelated earlier runs at the start of a suite (see
`allure.clean.results.before.run` below), but *not* between consecutive
Test Suites in the same Test Suite Collection, since those are meant to
combine into that one collection report.

**Opt-in step detail**, from inside a Test Case script or Cucumber glue:

```groovy
CustomKeywords.'allure.AllureKeywords.step'('Log in as admin', {
    WebUI.setText(findTestObject('Page/input_Username'), 'admin')
    WebUI.click(findTestObject('Page/button_Login'))
})
CustomKeywords.'allure.AllureKeywords.severity'('critical')
CustomKeywords.'allure.AllureKeywords.epic'('Patient Management')
CustomKeywords.'allure.AllureKeywords.attachJson'('Booking payload', responsePayload)
```

## Configuration

Edit `Include/config/allure/allure.properties` in the target project, or
override any key per environment with `ALLURE_<KEY_IN_UPPER_SNAKE_CASE>`
(e.g. `allure.results.dir` -> `ALLURE_RESULTS_DIR`):

| Key | Default | Meaning |
|---|---|---|
| `allure.enabled` | `true` | Master switch |
| `allure.results.dir` | `allure-results` | Relative to project root, or absolute |
| `allure.clean.results.before.run` | `true` | Clear last run's results before each suite starts, so a report only shows the run it's named after. Set `false` if you run suites in true parallel against the same results folder |
| `allure.attach.screenshot.on.failure` | `true` | Screenshot on any non-PASSED status (WebUI only) |
| `allure.attach.screenshot.always` | `false` | Screenshot on every test case |
| `allure.categories.file` | `Include/config/allure/categories.json` | Failure categorization template |
| `allure.auto.generate.report` | `true` | Auto-run `allure generate` at the end of every suite. Set `false` if your CI already does this itself |
| `allure.report.dir` | `allure-report` | Base folder for generated reports; each run writes its own `<Name>_<timestamp>` here |
| `allure.report.single.file` | `true` | One self-contained `.html` per run (double-click to open, no server). Set `false` for a `<Name>_<timestamp>/` folder instead - only worth it for very large suites |
| `allure.commandline.path` | *(auto-detected)* | Absolute path to the `allure` executable, only needed if auto-detection doesn't find it - see Troubleshooting below |

## CI setup

`executor.json` auto-detects Jenkins, Azure Pipelines, GitHub Actions, and
GitLab CI from each platform's own standard environment variables, so the
Allure report header links back to the build that produced it - nothing to
configure for that part on any of them. The self-contained HTML report
(`allure-report/<Name>_<timestamp>.html`) is already generated by the time
your Katalon step finishes, as long as the Allure commandline is on the CI
agent's PATH - each config below takes care of that too.

This repo includes three ready-to-copy configs - `azure-pipelines.example.yml`,
`github-actions.example.yml`, `gitlab-ci.example.yml` - each verified
working end-to-end, not just written against documentation. Pick the one
matching your platform, copy it in under the filename your CI expects,
fill in the one TODO (your Test Suite or Test Suite Collection path), and
add your Katalon API key as described below.

### Azure Pipelines

1. Copy `azure-pipelines.example.yml` into your repo as `azure-pipelines.yml`.
2. Install the **"Execute Katalon Studio Tests"** extension from the Azure
   DevOps Marketplace if your organization doesn't already have it
   (Organization Settings → Extensions → Browse Marketplace → search
   "Katalon").
3. Under **Pipelines → Library**, create a variable group named `Katalon`
   with a secret variable `KatalonApiKey` holding your Katalon Runtime
   Engine API key (Katalon Store → Profile → API Key). Marking it secret
   keeps it masked in every log line.
4. Replace `<YourCollection>` in the `executeArgs` line with your actual
   Test Suite Collection path (or swap `-testSuiteCollectionPath` for
   `-testSuitePath="Test Suites/<YourSuite>"` to run a single suite
   instead).
5. Commit and push - the pipeline runs on every push to `main`.

Reports show up on the pipeline run's **Summary** tab, in the small
"X published" artifacts panel near the top - both the raw Katalon output
(`katalon-reports`) and the generated Allure HTML (`allure-report`).

### GitHub Actions

1. Copy `github-actions.example.yml` into your repo as
   `.github/workflows/katalon-ci.yml`.
2. Under **Settings → Secrets and variables → Actions**, add a repository
   secret named `KATALON_API_KEY` with your Katalon Runtime Engine API key.
3. Replace `<YourCollection>` in the `args` line the same way as above.
4. Commit and push.

Reports show up on the **Actions** tab, under that run's summary page, in
the **Artifacts** section at the bottom.

### GitLab CI

1. Copy `gitlab-ci.example.yml` into your repo as `.gitlab-ci.yml`.
2. Under **Settings → CI/CD → Variables**, add a variable named
   `KATALON_API_KEY` with your Katalon Runtime Engine API key, and check
   "Mask variable".
3. Replace `<YourCollection>` in the `script` line the same way as above.
4. Commit and push.

This one differs from the other two in one respect: it uses Katalon's own
official Docker image (`katalonstudio/katalon`) instead of downloading
Katalon onto a Windows agent, so it runs on **Chrome, not Edge** - adjust
`-browserType` and any browser-specific test logic accordingly. If you'd
rather keep Edge/Windows parity with an Azure Pipelines or GitHub Actions
setup, GitLab.com also offers a free-tier Windows hosted runner (tag
`saas-windows-medium-amd64`) you can target instead.

Reports show up on the pipeline job's page, in the **Job artifacts** panel.

### Worth knowing regardless of platform

- **`--config -webui.autoUpdateDrivers=true`** is included in all three
  examples. A hosted CI agent's browser updates itself automatically, and
  Katalon's own bundled WebUI driver can fall behind it - without this
  flag, a WebUI suite can start failing with `SessionNotCreatedException`
  purely because the agent's browser moved past what the bundled driver
  supports. This tells Katalon to fetch a matching driver at run time
  instead. The `--config` prefix is required; `-webui.autoUpdateDrivers=true`
  on its own is rejected by Katalon's console-mode argument parser.
- **Running more than one Katalon step in the same Azure Pipelines job?**
  Add the `bin/`-clearing step before *each* one, not just the first.
  `katalonTask` locates the project by scanning for a `*.prj` file, and
  will find a stray copy left in `bin/` by an earlier step before it finds
  the real one.
- **Multi-suite Test Suite Collections** are named correctly in the
  generated report regardless of platform, browser, or how fast the CI
  runner executes - Katalon doesn't expose a public API for a collection's
  own name, so the bridge derives it from the run's own folder structure,
  verified against real multi-suite runs on all three platforms above.

## Troubleshooting

**HTML report folder is empty, or a `[Allure] Could not auto-generate the
HTML report...` warning shows up in the console.** The bridge couldn't
find the `allure` commandline. It tries, in order: `allure.commandline.path`
(if you set it), a few common install locations, then - on macOS/Linux -
asking your own login shell where `allure` resolves to, then finally a
bare `allure` on whatever `PATH` this process already inherited. This
mostly shows up running the Katalon Studio IDE as a desktop app on
macOS/Linux: a GUI-launched app (double-clicked, from Dock/Finder) does
not source the shell profile scripts (`.zshrc`, `.bash_profile`, etc)
that tools like volta/nvm/homebrew rely on to reach `PATH`, so it can be
installed and working fine from a Terminal yet still be invisible here.
CI pipelines aren't affected by this, since they already run Katalon from
inside a shell step with the right `PATH`. If none of the automatic
strategies find it, set `allure.commandline.path` (or
`ALLURE_COMMANDLINE_PATH`) to its absolute path - the warning message
names exactly which command it tried.

**Test Suite Collection report seems to be missing one member suite's
results.** Every suite in a Collection shares one `allure-results/`
folder, and the report is only (re)generated once every member has
finished - see "Using it" above. If a member suite never reaches its own
`AfterTestSuite` (aborted, killed, or crashed mid-run), its results won't
be in the folder yet when the report is built. Check the console for
`[Allure]` lines from every expected suite; if all of them show up but
results still seem to be missing, open an issue on this repo with the
console log.

For anything else, open an issue on this repo, or reach out - see Support
below.

## Support

Questions, bug reports, or need help wiring this into a specific CI/CD
setup? Open an issue on this repo, or reach out directly for consulting:
**bagati.monty@gmail.com**.

## License

See `LICENSE.md` - **read it before distributing this package**, it
explains why a decision is required and isn't made for you by default.

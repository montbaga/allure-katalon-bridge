package allure

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kms.katalon.core.configuration.RunConfiguration
import com.kms.katalon.core.context.TestCaseContext
import com.kms.katalon.core.context.TestSuiteContext
import com.kms.katalon.core.logging.KeywordLogger
import com.kms.katalon.core.logging.TestSuiteXMLLogParser
import com.kms.katalon.core.logging.model.ILogRecord
import com.kms.katalon.core.logging.model.TestCaseLogRecord
import com.kms.katalon.core.logging.model.TestStatus
import com.kms.katalon.core.logging.model.TestStepLogRecord
import com.kms.katalon.core.logging.model.TestSuiteLogRecord
import com.kms.katalon.core.webui.driver.DriverFactory
import io.qameta.allure.Allure
import io.qameta.allure.AllureLifecycle
import io.qameta.allure.FileSystemResultsWriter
import io.qameta.allure.model.Stage
import io.qameta.allure.model.Status
import io.qameta.allure.model.StatusDetails
import io.qameta.allure.model.StepResult
import io.qameta.allure.model.TestResult
import io.qameta.allure.util.ResultsUtils
import org.apache.commons.io.FileUtils
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.WebDriver

import java.io.FileFilter
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.TimeUnit

/**
 * Bridges Katalon Studio's Test Listener lifecycle to Allure's AllureLifecycle API.
 *
 * Design notes (see the integration guide for the full rationale):
 *  - Every public method swallows its own exceptions and only ever logs a
 *    warning: a bug in report generation must never fail, skip, or alter
 *    the outcome of the real test.
 *  - The results directory is set explicitly via Allure.setLifecycle(...)
 *    in startSuite() rather than relying on the "allure.results.directory"
 *    system property + Allure's lazy default, because Katalon's working
 *    directory differs between IDE runs and katalonc/CLI runs.
 *  - Test case granularity matches what Katalon's TestCaseContext exposes
 *    publicly (id, status, message) so this keeps working across Katalon
 *    versions. Finer, in-test detail is opt-in via AllureKeywords.step(),
 *    the same manual-instrumentation pattern Allure itself recommends for
 *    frameworks it has no first-party adapter for.
 *  - Katalon runs a suite's BeforeTestSuite, each TestCase, and
 *    AfterTestSuite as isolated phases that do not reliably share plain
 *    static field state. Anything that needs to survive across phases uses
 *    a file under allure.results.dir instead (see readRunMarker(),
 *    queuePendingSteps()).
 */
class AllureReportBridge {

    private static final KeywordLogger logger = KeywordLogger.getInstance(AllureReportBridge.class)

    /** Last-resort fallback only - see the class-level notes on cross-phase state. */
    private static volatile String currentSuiteName = 'Katalon Test Suite'

    /**
     * Guards access to .allure-run-marker.txt (and the clear/generate steps
     * that depend on it) across every suite in the current run - see
     * withRunLock() for why this has to be a real cross-process lock, not
     * just a Java 'synchronized' block.
     */
    private static final Object INTRA_JVM_LOCK = new Object()

    static void startSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!AllureConfig.isEnabled()) {
                return
            }
            File resultsDir = AllureConfig.getResultsDir()
            resultsDir.mkdirs()

            withRunLock(resultsDir) {
                // "Same overall run" (e.g. another Test Suite in the same
                // Test Suite Collection) is identified by comparing this
                // run's Reports/<timestamp> folder to the one recorded by
                // whichever suite ran before this one, not by an elapsed-
                // time window - suite durations vary too widely for a fixed
                // window to be reliable. Every sub-suite in one Test Suite
                // Collection run shares the same folder, so this comparison
                // is exact regardless of how long any suite takes. (This
                // same run can also span multiple OS processes - see
                // withRunLock()'s notes below.)
                File thisRunDir = currentRunDir()
                Map marker = readRunMarker(resultsDir)
                String previousRunDir = marker.runDir as String
                boolean continuingRun
                if (thisRunDir != null) {
                    continuingRun = thisRunDir.absolutePath == previousRunDir
                } else {
                    // This suite's own run directory couldn't be resolved -
                    // can happen if Katalon Studio IDE lays out Reports/
                    // differently than Katalon Runtime Engine's console
                    // mode does for a Test Suite Collection (see
                    // currentRunDir()). Treat an existing marker as still
                    // being continued rather than risk clearing an
                    // in-progress Collection's already-written results out
                    // from under it just because this one signal was
                    // inconclusive: losing an earlier sub-suite's results
                    // because we couldn't confirm a new run has started is
                    // worse than occasionally skipping a clear that would
                    // have been harmless anyway. A marker with genuinely
                    // nothing left to continue it gets cleaned up as soon
                    // as a later suite's run directory does resolve.
                    continuingRun = previousRunDir != null && !previousRunDir.trim().isEmpty()
                }
                if (!continuingRun) {
                    if (AllureConfig.cleanResultsBeforeRun()) {
                        clearPreviousResults(resultsDir)
                        // writeEnvironmentProperties() merges each suite's
                        // values into this file rather than overwriting it,
                        // so a fresh run needs its own clean slate here -
                        // otherwise it would keep merging into whatever an
                        // earlier, unrelated run left behind.
                        new File(resultsDir, 'environment.properties').delete()
                    }
                    marker.reportPath = ''
                    // Fresh run: forget which sub-suites (if any) had
                    // already reported themselves complete under a
                    // previous, unrelated run - see shouldGenerateReportNow().
                    new File(resultsDir, '.allure-collection-progress.txt').delete()
                }
                // Only overwrite the recorded run dir when this suite
                // actually resolved one - an unresolved thisRunDir must
                // not blank out a still-valid marker a later suite in
                // this same run may depend on.
                String runDirToRecord = thisRunDir != null ? thisRunDir.absolutePath : previousRunDir
                writeRunMarker(resultsDir, runDirToRecord ?: '', marker.reportPath as String)
            }

            Allure.setLifecycle(new AllureLifecycle(new FileSystemResultsWriter(resultsDir.toPath())))

            // Written for logging/diagnostics and as a last-resort fallback
            // only - startTestCase() and finishSuite() each re-resolve the
            // name fresh rather than trusting this field to survive into
            // their own (isolated) execution phase.
            currentSuiteName = resolveSuiteName(testSuiteContext)

            writeEnvironmentProperties(resultsDir)
            writeExecutorJson(resultsDir)
            writeCategoriesJson(resultsDir)

            logger.logInfo("[Allure] Reporting enabled. Results directory: ${resultsDir.absolutePath}")
        } catch (Throwable t) {
            logger.logWarning("[Allure] Failed to initialize Allure reporting: ${t}")
        }
    }

    static void finishSuite(TestSuiteContext testSuiteContext) {
        try {
            if (!AllureConfig.isEnabled()) {
                return
            }
            File resultsDir = AllureConfig.getResultsDir()
            logger.logInfo("[Allure] Suite finished. Results: ${resultsDir.absolutePath}")
            // Re-resolved here, not read from the static field startSuite()
            // set: a static field written in one isolated phase is not
            // reliable in another (see the class-level notes).
            String suiteName = resolveSuiteName(testSuiteContext)

            // Computed once - shouldGenerateReportNow() has a side effect
            // (it records this suite as complete), so it must only be
            // called once per finishSuite(), and its answer is also what
            // processPendingSteps() needs to know whether a leftover entry
            // still has a later suite to be retried by, or not.
            boolean isFinalSuite = shouldGenerateReportNow(resultsDir)

            // Every test case that finished during this suite gets its
            // captured steps patched in now, regardless of whether this
            // suite also happens to trigger HTML generation below - see
            // processPendingSteps() for why this can only safely happen
            // here (AfterTestSuite), never inside AfterTestCase itself.
            if (AllureConfig.captureSteps()) {
                processPendingSteps(resultsDir, isFinalSuite)
            }

            // Only actually (re)generate once every sub-suite Katalon
            // planned for this run has finished.
            if (isFinalSuite) {
                generateHtmlReport(resultsDir, suiteName)
            }
        } catch (Throwable ignored) {
            // never fail the suite because of reporting
        }
    }

    /**
     * Runs 'allure generate' synchronously so a finished HTML report is
     * already sitting on disk the moment the suite finishes - no extra
     * click, no script. By default (allure.report.single.file=true) that's
     * one self-contained "<Name>_<yyyyMMdd_HHmmss>.html" file directly
     * under allure.report.dir - Allure's native --single-file mode, which
     * embeds everything inline so it opens correctly straight from
     * file://, no local server required. Set allure.report.single.file to
     * false for very large suites, where one huge HTML file gets slow to
     * load - that mode writes a "<Name>_<timestamp>/" folder instead,
     * viewable via "View Allure Report" (needs "allure open").
     *
     * <Name> is whatever RunConfiguration.getExecutionSourceName() (or its
     * fallbacks - see resolveSuiteName) resolved for this run: a Test
     * Suite Collection's name, a Test Suite's name, or a lone Test Case's
     * own name if run directly without a saved suite.
     *
     * Deliberately generate-only, not serve/open: this must stay safe to
     * run unattended in CI (no server, no browser, exits on its own).
     * Never throws - if the Allure commandline isn't installed, this logs
     * one clear warning and the suite result is unaffected either way.
     */
    private static void generateHtmlReport(File resultsDir, String suiteName) {
        if (!AllureConfig.autoGenerateReport()) {
            return
        }
        File reportBaseDir = AllureConfig.getReportDir()
        File stagingDir = new File(reportBaseDir, ".staging-${System.nanoTime()}")
        String allureCommand = resolveAllureCommand()
        try {
            reportBaseDir.mkdirs()
            boolean singleFile = AllureConfig.singleFileReport()
            carryHistoryForward(reportBaseDir, resultsDir, singleFile)

            logger.logInfo('[Allure] Generating HTML report - this can take a few seconds, there is no separate progress indicator for it.')
            if (!runAllureGenerate(allureCommand, resultsDir, stagingDir, singleFile)) {
                return
            }

            // Only the swap-into-place + dedup + marker update is locked -
            // not the 'allure generate' call above, which already writes
            // into this call's own uniquely-named staging dir and is the
            // slow part, so suites running in parallel still generate
            // concurrently. See withRunLock() for why this must be a real
            // cross-process lock: Katalon can run different suites in the
            // same Test Suite Collection in genuinely separate OS processes
            // at the same time, so a plain 'synchronized' here would only
            // ever protect against other threads in this same process.
            withRunLock(resultsDir) {
                String timestamp = new java.text.SimpleDateFormat('yyyyMMdd_HHmmss').format(new Date())
                String displayName = sanitizeForFilename(suiteName)

                File finalTarget = singleFile ?
                    new File(reportBaseDir, "${displayName}_${timestamp}.html") :
                    new File(reportBaseDir, "${displayName}_${timestamp}")

                if (singleFile) {
                    FileUtils.moveFile(new File(stagingDir, 'index.html'), finalTarget)
                } else {
                    FileUtils.moveDirectory(stagingDir, finalTarget)
                }

                // If an earlier Test Suite in this same overall run (e.g. an
                // earlier suite in the same Test Suite Collection) already
                // produced a report, remove it - a multi-suite run should
                // end up with exactly one report file, not one per
                // sub-suite, since allure-results wasn't cleared between
                // them and this new one already contains everything the
                // earlier one did, plus more. Reading the marker and
                // deleting/replacing the previous report all happens inside
                // the same lock a concurrent sibling suite's finishSuite()
                // also uses, so two suites finishing at the same time can't
                // both read the same "previous" path and both survive.
                Map marker = readRunMarker(resultsDir)
                String previousPath = marker.reportPath as String
                if (previousPath) {
                    File previous = new File(previousPath)
                    if (previous.exists() && previous.canonicalPath != finalTarget.canonicalPath) {
                        if (previous.isDirectory()) {
                            FileUtils.deleteQuietly(previous)
                        } else {
                            previous.delete()
                        }
                    }
                }
                writeRunMarker(resultsDir, marker.runDir as String, finalTarget.absolutePath)

                logger.logInfo("[Allure] HTML report ready: ${finalTarget.absolutePath}")
            }
        } catch (Throwable t) {
            logger.logWarning("[Allure] Could not auto-generate the HTML report using '${allureCommand}' - install the Allure commandline (npm install -g allure-commandline), " +
                "point allure.commandline.path (Include/config/allure/allure.properties) or ALLURE_COMMANDLINE_PATH at its absolute path, or set allure.auto.generate.report=false. (${t.getMessage()})")
        } finally {
            if (stagingDir.exists()) {
                FileUtils.deleteQuietly(stagingDir)
            }
        }
    }

    private static boolean runAllureGenerate(String allureCommand, File resultsDir, File outputDir, boolean singleFile) {
        List<String> baseCommand = [allureCommand, 'generate', resultsDir.absolutePath, '--clean', '-o', outputDir.absolutePath]
        if (singleFile) {
            baseCommand << '--single-file'
        }
        boolean isWindows = System.getProperty('os.name', '').toLowerCase().contains('win')
        List<String> command = isWindows ? (['cmd', '/c'] + baseCommand) : baseCommand

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start()
        boolean finished = process.waitFor(90, TimeUnit.SECONDS)

        if (!finished) {
            process.destroyForcibly()
            logger.logWarning('[Allure] "allure generate" timed out after 90s - skipped.')
            return false
        }
        if (process.exitValue() != 0) {
            String output = process.inputStream.getText('UTF-8')
            logger.logWarning("[Allure] \"allure generate\" (using '${allureCommand}') failed - install the Allure commandline (npm install -g allure-commandline) or set allure.auto.generate.report=false. " +
                "Output: ${output.take(500)}")
            return false
        }
        return true
    }

    /** Cached for the lifetime of this process - resolution below can shell out, and does not need to repeat per suite. */
    private static volatile String resolvedAllureCommand = null

    /**
     * Finds the 'allure' commandline executable to run, since a bare
     * "allure" (relying on whatever PATH this JVM process already
     * inherited) does not work everywhere it needs to. On CI, it already
     * does: the pipeline installs Allure and runs Katalon from inside a
     * shell step, so the JVM inherits that shell's PATH correctly - this
     * whole resolution is a no-op there (last strategy below matches
     * today's behaviour exactly). The gap is the Katalon Studio IDE
     * launched as a desktop app (double-clicked, from Dock/Finder/Start
     * Menu) on macOS or Linux: that process inherits the OS's bare
     * session-default PATH, not the richer one a login shell builds by
     * sourcing .zshrc/.bash_profile/etc - so a tool installed through
     * volta/nvm/sdkman/homebrew and only ever added to PATH by a shell
     * profile script is invisible to it, even though `which allure` finds
     * it fine from a terminal. Windows does not have this split - an
     * installer-set User/System PATH entry is visible to every
     * subsequently launched process, GUI or shell alike - so this mostly
     * matters on macOS/Linux.
     *
     * findAllureCommand() below tries an explicit config override first
     * (for whenever the automatic strategies still guess wrong), then a
     * short list of common install locations, then - the actual general
     * fix for this whole class of problem, not just one install path -
     * asking the current user's own login shell what it resolves
     * "allure" to, before falling back to a bare "allure" unchanged.
     */
    private static String resolveAllureCommand() {
        if (resolvedAllureCommand != null) {
            return resolvedAllureCommand
        }
        synchronized (INTRA_JVM_LOCK) {
            if (resolvedAllureCommand == null) {
                resolvedAllureCommand = findAllureCommand()
            }
        }
        return resolvedAllureCommand
    }

    private static String findAllureCommand() {
        boolean isWindows = System.getProperty('os.name', '').toLowerCase().contains('win')

        String configured = safeCall { AllureConfig.getAllureCommandlinePath() }
        if (configured) {
            File f = new File(configured)
            if (isExecutableFile(f)) {
                logger.logInfo("[Allure] Using configured allure.commandline.path: ${f.absolutePath}")
                return f.absolutePath
            }
            logger.logWarning("[Allure] allure.commandline.path is set to '${configured}' but that is not an existing, executable file - ignoring it and trying to auto-detect instead.")
        }

        for (String candidate : commonAllureInstallLocations(isWindows)) {
            File f = new File(candidate)
            if (isExecutableFile(f)) {
                logger.logInfo("[Allure] Found allure at a common install location: ${f.absolutePath}")
                return f.absolutePath
            }
        }

        if (!isWindows) {
            String viaShell = resolveAllureViaLoginShell()
            if (viaShell) {
                logger.logInfo("[Allure] Found allure via the login shell's PATH: ${viaShell}")
                return viaShell
            }
        }

        return 'allure'
    }

    private static boolean isExecutableFile(File f) {
        try {
            return f.isFile() && f.canExecute()
        } catch (Throwable ignored) {
            return false
        }
    }

    private static List<String> commonAllureInstallLocations(boolean isWindows) {
        String home = System.getProperty('user.home', '')
        if (isWindows) {
            return [
                "${home}\\scoop\\shims\\allure.bat",
                "${System.getenv('ProgramData') ?: 'C:\\ProgramData'}\\chocolatey\\bin\\allure.exe",
                "${home}\\.volta\\bin\\allure.exe",
            ]
        }
        return [
            "${home}/.volta/bin/allure",
            '/opt/homebrew/bin/allure',
            '/usr/local/bin/allure',
            "${home}/.npm-global/bin/allure",
            '/snap/bin/allure',
            '/usr/bin/allure',
        ]
    }

    /**
     * Asks the user's own login shell where "allure" resolves to - the
     * same answer `which allure`/`command -v allure` gives in a real
     * Terminal, since a login shell (`-l`) sources the same profile
     * scripts (.zshrc, .bash_profile, etc) a Terminal window does. This is
     * what actually fixes the class of problem, not just one specific
     * tool's default install path: whatever the user's own shell
     * environment adds to PATH, this sees too.
     */
    private static String resolveAllureViaLoginShell() {
        try {
            String shell = System.getenv('SHELL') ?: '/bin/sh'
            Process process = new ProcessBuilder(shell, '-lc', 'command -v allure')
                .redirectErrorStream(true).start()
            boolean finished = process.waitFor(10, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return null
            }
            if (process.exitValue() != 0) {
                return null
            }
            String output = process.inputStream.getText('UTF-8')
            String firstLine = output.readLines().find { it?.trim() }
            if (!firstLine) {
                return null
            }
            File f = new File(firstLine.trim())
            return isExecutableFile(f) ? f.absolutePath : null
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String sanitizeForFilename(String name) {
        return (name ?: 'Suite').replaceAll('[^a-zA-Z0-9 _-]', '_').trim()
    }

    /**
     * Deletes this run's predecessor Allure result/container/attachment
     * files from resultsDir before a new suite starts, so each report
     * reflects only the run it's named after instead of accumulating
     * every test case from every run ever done in this project (Allure's
     * default behaviour, since it normally expects a fresh results
     * directory per CI build). Only deletes files matching Allure's own
     * naming convention (*-result.json, *-container.json, *-attachment*)
     * - never touches history/, executor.json, or categories.json (all
     * rewritten fresh below anyway), and never touches anything else a
     * user might have placed in that folder. environment.properties is
     * handled separately, right where this is called from - it's merged
     * across a run's suites rather than simply rewritten, so a fresh run
     * needs its own explicit reset instead of being covered here.
     *
     * If you run multiple suites in true parallel against the same
     * allure-results directory, turn this off (allure.clean.results
     * .before.run=false) - otherwise a suite starting mid-run can wipe
     * another suite's still-in-progress results.
     */
    private static void clearPreviousResults(File resultsDir) {
        File[] files = resultsDir.listFiles({ File f ->
            f.isFile() && (f.name.endsWith('-result.json') || f.name.endsWith('-container.json') || f.name.contains('-attachment'))
        } as FileFilter)
        files?.each { it.delete() }
    }

    /**
     * Reads .allure-run-marker.txt (2 lines: the Reports/<timestamp> folder
     * this run is using, then the last generated report's absolute path,
     * blank if none yet). A file, not a static field, because it needs to
     * survive Katalon running BeforeTestSuite/each TestCase/AfterTestSuite
     * as isolated phases - and, for a Test Suite Collection, different
     * sub-suites entirely.
     */
    private static Map readRunMarker(File resultsDir) {
        File markerFile = new File(resultsDir, '.allure-run-marker.txt')
        if (!markerFile.isFile()) {
            return [runDir: '', reportPath: '']
        }
        try {
            List<String> lines = markerFile.readLines()
            String runDir = lines.size() > 0 ? lines[0] : ''
            String reportPath = lines.size() > 1 ? lines[1] : ''
            return [runDir: runDir, reportPath: reportPath]
        } catch (Throwable ignored) {
            return [runDir: '', reportPath: '']
        }
    }

    private static void writeRunMarker(File resultsDir, String runDir, String reportPath) {
        try {
            new File(resultsDir, '.allure-run-marker.txt').text = "${runDir ?: ''}\n${reportPath ?: ''}\n"
        } catch (Throwable ignored) { }
    }

    /**
     * Runs `action` while holding an exclusive lock scoped to this results
     * directory - both a same-JVM lock and a real OS-level file lock, and
     * both are needed. Katalon can run individual suites within one Test
     * Suite Collection in genuinely separate OS processes at the same time
     * (suites marked "orchestration":"ISOLATED_PROCESS" in Katalon's own
     * plan.jsonl each get their own JVM, running concurrently with others
     * sharing the main engine process under "CLASSIC" orchestration). A
     * plain Java 'synchronized' block cannot serialize access between
     * separate processes, only a file lock can - but a second FileLock
     * attempt from another *thread in the same* JVM throws
     * OverlappingFileLockException instead of blocking, so the synchronized
     * block still matters for suites sharing one process (the "CLASSIC"
     * case): it ensures only one thread per JVM ever holds the file lock at
     * a time, so the two layers combined correctly serialize every
     * combination - same thread, same JVM different thread, and separate
     * OS process alike.
     */
    private static Object withRunLock(File resultsDir, Closure action) {
        synchronized (INTRA_JVM_LOCK) {
            RandomAccessFile raf = new RandomAccessFile(new File(resultsDir, '.allure-run.lock'), 'rw')
            try {
                FileLock lock = raf.getChannel().lock()
                try {
                    return action.call()
                } finally {
                    lock.release()
                }
            } finally {
                raf.close()
            }
        }
    }

    /**
     * Whether report generation should actually run for this finishSuite()
     * call, or whether other sub-suites in the same run are still going -
     * so the report is generated exactly once, only after every sub-suite
     * Katalon planned for this run has finished, instead of being
     * regenerated (and immediately superseded) after each one.
     *
     * Two things are needed to know "every sub-suite has finished" without
     * waiting on any of Katalon's own post-hoc artifacts (collection.json
     * and the native per-suite HTML reports are both written a second or
     * more *after* every listener in the run has already returned, so
     * counting on those from inside a listener would always be too early):
     *  - How many sub-suites are planned in total: read once from
     *    Reports/<run-ts>/plan.jsonl's first line, which Katalon writes
     *    with the complete plan *before* any sub-suite starts, not after -
     *    a TEST_SUITE_COLLECTION's "execution.children" entries of kind
     *    TEST_SUITE give the count; a plain TEST_SUITE run is always 1.
     *  - A stable per-suite-*instance* key, so two different suites named
     *    "TunnelTestSuite" both used in the same collection count as two
     *    completions, not one, while multiple retry attempts of the *same*
     *    instance (if Katalon ever re-invokes this listener per attempt)
     *    still count as one: RunConfiguration.getReportFolder() is
     *    Katalon's own public API for "where this specific execution
     *    writes its report" and is unique per instance (e.g.
     *    ".../TunnelTestSuite/<ts>" vs ".../TunnelTestSuite_8b358f94/<ts>"),
     *    and only the first path segment under the run folder is used as
     *    the key, since a retry attempt would still share that same
     *    top-level segment.
     *
     * If either signal can't be determined for any reason (older Katalon
     * version, unexpected plan.jsonl shape, blank report folder), this
     * returns true - i.e. falls back to generating every time, which stays
     * correct, just less efficient. This optimization can only ever skip
     * *extra* generations; it can never cause the one that matters to be
     * skipped.
     */
    private static boolean shouldGenerateReportNow(File resultsDir) {
        File runDir = currentRunDir()
        if (runDir == null) {
            return true
        }
        Integer expected = expectedSuiteCountForRun(runDir)
        String key = suiteInstanceKey(runDir)
        if (expected == null || key == null) {
            return true
        }
        return withRunLock(resultsDir) {
            File progressFile = new File(resultsDir, '.allure-collection-progress.txt')
            Set<String> completed = progressFile.isFile() ?
                (progressFile.readLines().findAll { it?.trim() } as Set) : ([] as Set)
            completed << key
            progressFile.text = completed.join('\n') + '\n'
            return completed.size() >= expected
        } as boolean
    }

    /**
     * Total number of sub-suites Katalon planned for this run, from
     * Reports/<run-ts>/plan.jsonl's first line (written once, with the
     * complete plan, before any sub-suite begins - see
     * shouldGenerateReportNow()). Returns null if this can't be determined,
     * which callers must treat as "unknown", not "zero".
     */
    private static Integer expectedSuiteCountForRun(File runDir) {
        try {
            File planFile = new File(runDir, 'plan.jsonl')
            if (!planFile.isFile()) {
                return null
            }
            String firstLine
            planFile.withReader('UTF-8') { reader -> firstLine = reader.readLine() }
            if (!firstLine || !firstLine.trim()) {
                return null
            }
            JsonObject root = JsonParser.parseString(firstLine).getAsJsonObject()
            JsonObject execution = root.has('execution') ? root.getAsJsonObject('execution') : null
            String kind = (execution != null && execution.has('kind')) ? execution.get('kind').getAsString() : null
            if (kind == 'TEST_SUITE_COLLECTION') {
                int count = 0
                execution.getAsJsonArray('children')?.each { child ->
                    if (child.getAsJsonObject().get('kind')?.getAsString() == 'TEST_SUITE') {
                        count++
                    }
                }
                return count > 0 ? count : null
            }
            if (kind == 'TEST_SUITE' || kind == 'TEST_CASE') {
                return 1
            }
            return null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * This specific suite execution's own report folder path relative to
     * runDir (e.g. "TunnelTestSuite_8b358f94", or
     * "TestSuitesFolder/FolderSuite" when the suite lives inside a Test
     * Suites folder) - see shouldGenerateReportNow() for why this, not
     * the suite's name, is the key that correctly distinguishes repeated
     * same-named suites within one collection.
     *
     * RunConfiguration.getReportFolder() doesn't sit at a fixed depth
     * below the suite's own instance folder - an ISOLATED_PROCESS suite
     * (its own separate process, rather than the shared engine process)
     * resolves it one level deeper, down to the specific test case,
     * than a CLASSIC suite does. Anchoring on the timestamp folder
     * instead of a fixed number of parent hops handles both: every
     * suite instance folder is immediately followed by a timestamp
     * segment (Katalon's own yyyyMMdd_HHmmss format), so everything
     * before the first such segment is the suite's own path, regardless
     * of how many more levels getReportFolder() goes below it or how many
     * Test Suites folders it's nested under above it.
     */
    private static String suiteInstanceKey(File runDir) {
        List<String> segments = suiteInstancePathSegments(runDir)
        if (segments == null) {
            return null
        }
        int timestampIndex = segments.findIndexOf { it ==~ /\d{8}_\d{6}/ }
        return timestampIndex > 0 ? segments[0..<timestampIndex].join('/') : null
    }

    /**
     * Same folder path as suiteInstanceKey(), but keeping the timestamp
     * segment instead of stopping before it. On Katalon Runtime Engine's
     * console mode on macOS, repeated occurrences of the same suite in
     * one Collection share a single parent folder with separate
     * timestamp subfolders (e.g. "New Test Suite/<ts1>" and
     * "New Test Suite/<ts2>"), rather than each getting its own
     * disambiguated sibling folder. suiteInstanceKey() alone resolves
     * both to the same "New Test Suite" - keeping the timestamp tells
     * them apart. See suiteOccurrenceDiscriminator().
     */
    private static String suiteInstancePathWithTimestamp(File runDir) {
        List<String> segments = suiteInstancePathSegments(runDir)
        if (segments == null) {
            return null
        }
        int timestampIndex = segments.findIndexOf { it ==~ /\d{8}_\d{6}/ }
        return timestampIndex >= 0 ? segments[0..timestampIndex].join('/') : null
    }

    private static List<String> suiteInstancePathSegments(File runDir) {
        try {
            String reportFolder = RunConfiguration.getReportFolder()
            if (!reportFolder || !reportFolder.trim()) {
                return null
            }
            String runDirCanonical = runDir.canonicalPath
            String reportFolderCanonical = new File(reportFolder).canonicalPath
            if (!reportFolderCanonical.startsWith(runDirCanonical + File.separator)) {
                return null
            }
            String relative = reportFolderCanonical.substring(runDirCanonical.length() + 1).replace('\\', '/')
            return relative.split('/') as List
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * [ordinal, totalOccurrences] for THIS suite occurrence among every
     * occurrence of the SAME underlying suite (matched by plan.jsonl's
     * entityId, e.g. "Test Suites/API Test Suite") within the current
     * run's plan. ordinal feeds suiteOccurrenceDiscriminator() (makes
     * startTestCase()'s historyId unique per occurrence); totalOccurrences
     * feeds suiteLabelSuffix() (only distinguishes the visible "Suite"
     * label when there's genuinely more than one). [0, 1] when a suite
     * appears only once, by far the common case - this only differs when
     * the very same suite is deliberately used more than once inside one
     * Test Suite Collection.
     *
     * Katalon's own per-run disambiguation suffix on the report folder
     * (e.g. "API Test Suite_45ff52a0") can't be used for the ordinal
     * directly: it is regenerated on every run, so baking it into
     * historyId would defeat Allure's trend graphs across days. Position
     * within plan.jsonl's children array reflects the collection's
     * *configured* suite order instead, which stays the same run to run.
     *
     * Returns null - not [0, 1] - whenever this genuinely can't be
     * determined (no plan.jsonl, unreadable, or this suite isn't findable
     * in it), so callers can tell "resolved" apart from "unknown" instead
     * of silently colliding two real occurrences onto the same "0"
     * (Katalon Runtime Engine's console mode always writes plan.jsonl;
     * the Katalon Studio IDE may not). Still a confident [0, 1] for a
     * plain, non-collection run whenever plan.jsonl is readable at all.
     */
    private static List<Integer> suiteOccurrenceFromPlan() {
        try {
            File runDir = currentRunDir()
            String myKey = runDir != null ? suiteInstanceKey(runDir) : null
            if (runDir == null || myKey == null) {
                return null
            }
            File planFile = new File(runDir, 'plan.jsonl')
            if (!planFile.isFile()) {
                return null
            }
            String firstLine
            planFile.withReader('UTF-8') { reader -> firstLine = reader.readLine() }
            if (!firstLine || !firstLine.trim()) {
                return null
            }
            JsonObject root = JsonParser.parseString(firstLine).getAsJsonObject()
            JsonObject execution = root.has('execution') ? root.getAsJsonObject('execution') : null
            String kind = execution?.get('kind')?.getAsString()
            if (kind != 'TEST_SUITE_COLLECTION') {
                // A plain suite/test case run - unambiguously the only
                // occurrence, whether or not plan.jsonl even models this.
                return [0, 1]
            }
            List<JsonObject> suiteChildren = []
            execution.getAsJsonArray('children')?.each { childEl ->
                JsonObject child = childEl.getAsJsonObject()
                if (child.get('kind')?.getAsString() == 'TEST_SUITE') {
                    suiteChildren << child
                }
            }
            JsonObject mine = suiteChildren.find { topLevelExecutionDirSegment(it) == myKey }
            String myEntityId = mine?.get('entityId')?.getAsString()
            if (mine == null || !myEntityId) {
                return null
            }
            List<JsonObject> siblings = suiteChildren.findAll { it.get('entityId')?.getAsString() == myEntityId }
            int ordinal = siblings.findIndexOf { topLevelExecutionDirSegment(it) == myKey }
            return ordinal >= 0 ? [ordinal, siblings.size()] : null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * The string startTestCase() bakes into historyId to keep two
     * occurrences of the very same suite within one run - e.g. the same
     * Test Suite Collection member run once per browser - from colliding
     * onto one Allure historyId. Allure treats matching historyIds as
     * retries of one logical test and collapses every earlier one out of
     * the default report view, which is indistinguishable, from the
     * report reader's side, from that browser's results being missing
     * entirely.
     *
     * Prefers suiteOccurrenceFromPlan()'s plan.jsonl-based position when
     * available, kept byte-for-byte identical to before this method
     * existed so nobody's existing Allure trend history changes. When
     * that's unavailable, falls back to Katalon's own per-occurrence
     * report folder name (suiteInstanceKey()) instead of blindly assuming
     * "0" - but only once this run is independently confirmed (via
     * resolveCollectionName(), which needs no plan.jsonl) to actually be
     * a Test Suite Collection, so a plain standalone suite keeps
     * returning "0" whether or not plan.jsonl exists, and only the
     * population actually at risk of two occurrences colliding is
     * affected.
     *
     * suiteInstanceKey() alone isn't enough on platforms where repeated
     * occurrences share one parent folder instead of getting their own
     * (see suiteInstancePathWithTimestamp()) - it resolves to the same
     * value for both, recreating the exact collision this method exists
     * to prevent. Falls back to the timestamp-inclusive path only once
     * that shared parent is confirmed to hold more than one timestamp
     * subfolder, trading historyId stability for that specific case; a
     * single-occurrence suite still gets suiteInstanceKey()'s stable
     * value, since a same-run collision is worse than losing trend
     * continuity, but only for suites actually at risk of it.
     */
    private static String suiteOccurrenceDiscriminator(String ownSuiteName) {
        List<Integer> fromPlan = suiteOccurrenceFromPlan()
        if (fromPlan != null) {
            return fromPlan[0].toString()
        }
        try {
            File runDir = currentRunDir()
            if (runDir == null) {
                return '0'
            }
            String collectionName = resolveCollectionName(runDir, ownSuiteName)
            if (!collectionName) {
                return '0'
            }
            String key = suiteInstanceKey(runDir)
            if (!key) {
                return '0'
            }
            if (key != ownSuiteName || !hasMultipleTimestampSubfolders(new File(runDir, key))) {
                return key
            }
            return suiteInstancePathWithTimestamp(runDir) ?: key
        } catch (Throwable ignored) {
            return '0'
        }
    }

    private static boolean hasMultipleTimestampSubfolders(File instanceDir) {
        File[] timestampDirs = instanceDir.listFiles({ File f -> f.isDirectory() && f.name ==~ /\d{8}_\d{6}/ } as FileFilter)
        return timestampDirs != null && timestampDirs.length > 1
    }

    /**
     * Suffix appended to the "Suite" label Allure groups results by, for
     * a suite with no browser to show (API/mobile-only) - see
     * finishTestCase() for the WebUI case, which is handled separately
     * once the test case's own browser use, if any, is actually known.
     *
     * Only added when this suite genuinely occurs more than once in the
     * current run - e.g. the same Collection member run twice. Without
     * one, two occurrences of "API Test Suite" would share one suite
     * label, and Allure's Suites view would merge them into what reads
     * as one suite holding every test case from both runs. A 1-based
     * occurrence count is used here, since there's no browser to
     * distinguish them by.
     *
     * The plan.jsonl path knows the total occurrence count upfront, so
     * every occurrence (including the first) gets suffixed together. The
     * suiteInstanceKey() fallback only recognizes occurrences after the
     * *first* (Katalon's disambiguating hash only starts from the second
     * one on), so a first occurrence found this way stays unsuffixed
     * while later ones get distinguished - still two separate suites,
     * just not symmetrically labelled.
     */
    private static String suiteLabelSuffix(String ownSuiteName) {
        List<Integer> fromPlan = suiteOccurrenceFromPlan()
        Integer ordinal
        boolean repeats
        if (fromPlan != null) {
            ordinal = fromPlan[0]
            repeats = fromPlan[1] > 1
        } else {
            ordinal = null
            repeats = false
            try {
                File runDir = currentRunDir()
                if (runDir != null && resolveCollectionName(runDir, ownSuiteName)) {
                    String key = suiteInstanceKey(runDir)
                    repeats = key != null && key != ownSuiteName
                }
            } catch (Throwable ignored) { }
        }
        return repeats ? " (occurrence ${(ordinal ?: 0) + 1})" : ''
    }

    /**
     * The report folder path (e.g. "API Test Suite_45ff52a0", or
     * "TestSuitesFolder/FolderSuite" when nested inside a Test Suites
     * folder) a plan.jsonl TEST_SUITE child will execute under, minus its
     * trailing timestamp segment, read from its first TEST_SUITE_ATTEMPT
     * child's executionDirPath - the same shape suiteInstanceKey()
     * derives from RunConfiguration.getReportFolder() at runtime, so the
     * two can be matched against each other.
     */
    private static String topLevelExecutionDirSegment(JsonObject suiteChild) {
        def attempts = suiteChild.getAsJsonArray('children')
        if (!attempts || attempts.size() == 0) {
            return null
        }
        JsonObject firstAttempt = attempts[0].getAsJsonObject()
        if (!firstAttempt.has('executionDirPath')) {
            return null
        }
        String dirPath = firstAttempt.get('executionDirPath').getAsString()
        int slash = dirPath.lastIndexOf('/')
        return slash > 0 ? dirPath.substring(0, slash) : dirPath
    }

    /**
     * The current run's Reports/<timestamp> folder - shared by every
     * sub-suite of the same overall run (e.g. every suite in one Test
     * Suite Collection writes into the same one). Two uses: identifying
     * "is this suite part of the same overall run as the last one"
     * (startSuite(), by comparing this to what's recorded in the run
     * marker) and looking up .metadata/.collection (resolveCollectionName()).
     *
     * Tries two things, in order, both derived from
     * RunConfiguration.getReportFolder() (the currently executing suite's
     * own report folder) rather than a hardcoded "<projectDir>/Reports"
     * scan - a hardcoded scan can't see a suite whose reports land
     * somewhere other than that expected root, and keeps resolving to a
     * different, unrelated suite's stale run directory instead: silently
     * treating every suite after the first as "continuing" that older
     * run, never clearing results, never resetting the report marker,
     * never generating its own report, and (for a collection) never
     * resolving the collection's own name either.
     *
     *  1. Walk up until reaching the directory containing plan.jsonl -
     *     written by Katalon once per overall run, directly in that
     *     run's own root (see expectedSuiteCountForRun(), which already
     *     depends on this same fact). Correct regardless of nesting
     *     depth, as long as plan.jsonl already exists.
     *  2. plan.jsonl is never written at all in Katalon Runtime Engine's
     *     console mode - true on every CI platform, not just some - so
     *     this is the path CI always actually takes. Falls back to
     *     runRootFromTimestampFolders() instead of a fixed parent-hop
     *     count: a suite nested inside a Test Suites folder (e.g.
     *     "TestSuitesFolder/FolderSuite") sits one level deeper below the
     *     run root than a suite that isn't, so counting hops guesses
     *     wrong for the nested case specifically - confirmed from a
     *     Windows CI run where this returned the "TestSuitesFolder"
     *     folder itself instead of the actual run root one level above
     *     it, breaking that suite's collection-name resolution.
     */
    private static File currentRunDir() {
        try {
            String reportFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!reportFolder || !reportFolder.trim()) {
                return null
            }
            File cursor = new File(reportFolder)
            int guard = 0
            while (cursor != null && !new File(cursor, 'plan.jsonl').isFile() && guard++ < 20) {
                cursor = cursor.parentFile
            }
            if (cursor != null && new File(cursor, 'plan.jsonl').isFile()) {
                return cursor
            }
            return runRootFromTimestampFolders(new File(reportFolder))
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * Walks up from a suite's own report folder to the run root, without
     * assuming a fixed number of parent hops - the run root and every
     * suite's own per-execution folder are both named with Katalon's
     * yyyyMMdd_HHmmss timestamp format, but a suite nested inside a Test
     * Suites folder has more path segments between them than one that
     * isn't. The first timestamp-shaped ancestor reached is the suite's
     * own (skipped); the next one after that is the run root, regardless
     * of how many plain-named folders (Test Suites folder nesting, an
     * ISOLATED_PROCESS suite's extra test-case-level segment) sit between
     * them.
     */
    private static File runRootFromTimestampFolders(File reportFolder) {
        File cursor = reportFolder
        boolean pastOwnTimestamp = false
        int guard = 0
        while (cursor != null && guard++ < 20) {
            if (cursor.name ==~ /\d{8}_\d{6}/) {
                if (pastOwnTimestamp) {
                    return cursor
                }
                pastOwnTimestamp = true
            }
            cursor = cursor.parentFile
        }
        return null
    }

    /**
     * When the current run is a Test Suite Collection, Katalon creates
     * Reports/<run-ts>/.metadata/.collection (an empty marker file) plus
     * exactly one Reports/<run-ts>/.metadata/<CollectionName>/ directory -
     * both written at the very start of the run, well before the first
     * sub-suite's AfterTestSuite fires. This is NOT the same timing as
     * Reports/<run-ts>/<CollectionName>/<ts>/collection.json, which Katalon
     * only writes *after* the last sub-suite's listener method returns -
     * too late for any listener code to read synchronously, and too late
     * for a background thread too, since Katalon tears down each phase's
     * execution context as soon as the listener method returns. Reading
     * the .metadata marker instead needs no waiting at all, so every
     * sub-suite's own finishSuite() resolves the real collection name
     * immediately, with nothing left to race or to silently miss.
     *
     * Returns null (falling through to the normal suite-name resolution)
     * for a plain, non-collection run, and also if a future Katalon version
     * ever stops writing this marker - this is undocumented internal
     * structure, not a public API, so resolveSuiteName() only ever
     * *upgrades* its answer with this, never depends on it.
     *
     * Katalon Runtime Engine's console mode does not write .metadata at
     * all, so resolveCollectionNameFromSiblingFolder() is tried as a
     * fallback whenever this comes back empty, rather than replacing this
     * check outright - whichever Katalon version does write .metadata
     * keeps using the more precise signal.
     *
     * When the collection itself lives inside a folder in the Test Suites
     * tree (e.g. "Test Suites/Regression/MyCollection" rather than
     * directly under "Test Suites/"), Katalon mirrors that folder path
     * here too - ".metadata/Regression/MyCollection/", not flatly
     * ".metadata/MyCollection/": the entry one level under .metadata is
     * the intermediate folder name ("Regression"), not the collection's
     * own name, with the collection's own (empty) directory one level
     * deeper still.
     */
    private static String resolveCollectionName(File targetRunDir, String ownSuiteName) {
        if (targetRunDir == null) {
            return null
        }
        String viaMetadata = resolveCollectionNameFromMetadata(targetRunDir, ownSuiteName)
        return viaMetadata ?: resolveCollectionNameFromSiblingFolder(targetRunDir, ownSuiteName)
    }

    /**
     * Descends from .metadata/ through single-subfolder chains until
     * reaching a directory with no subfolders of its own (a leaf) - that
     * leaf's name is the collection's own name. For a collection that
     * isn't nested inside any Test Suites folder, .metadata/<CollectionName>
     * is already a leaf, so this returns immediately with the same answer
     * as always. For a nested collection, this walks past each
     * intermediate folder-path segment (e.g. .metadata/Regression/
     * MyCollection/) until it reaches MyCollection, which has nothing
     * under it.
     */
    private static String resolveCollectionNameFromMetadata(File targetRunDir, String ownSuiteName) {
        File metadataDir = new File(targetRunDir, '.metadata')
        if (!new File(metadataDir, '.collection').isFile()) {
            return null
        }
        File cursor = metadataDir
        int guard = 0
        while (guard++ < 20) {
            File[] entries = cursor.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!entries || entries.length == 0) {
                return null
            }
            File chosen = entries.find { it.name != ownSuiteName } ?: entries[0]
            File[] deeper = chosen.listFiles({ File f -> f.isDirectory() } as FileFilter)
            if (!deeper || deeper.length == 0) {
                return chosen.name
            }
            cursor = chosen
        }
        return null
    }

    /**
     * What Katalon Runtime Engine's console mode writes instead of
     * .metadata: a sibling directory directly under the run root, named
     * after the collection itself (e.g. "TSC2" next to member suites' own
     * folders), already present by the time this runs.
     *
     * A candidate is treated as the collection's own folder only when both
     * of these hold:
     *  - it has a subfolder named exactly like the run root itself. The
     *    collection's own subfolder always shares the run root's
     *    timestamp (e.g. run root "20260808_040056" contains
     *    "TSC2/20260808_040056/"), while a member suite's own subfolder is
     *    timestamped for whenever that suite actually started.
     *  - that subfolder does not contain execution0.log.
     *
     * Neither signal holds up alone. Subfolder-name matching by itself
     * breaks on a fast enough machine: if a member suite happens to start
     * within the same second the run root was created, its own subfolder
     * shares that timestamp too, and more than one candidate matches.
     * collection.json's presence can't be used either, for the same reason
     * given on resolveCollectionNameFromMetadata() above - Katalon only
     * writes it after the last sub-suite's listener returns, so it never
     * exists yet at resolution time. execution0.log closes that gap: a
     * genuine member suite always has it once it runs, regardless of what
     * its subfolder is named or how fast it started; the collection's own
     * folder never does, since it isn't a real suite execution. Requiring
     * both conditions together correctly excludes a same-second-colliding
     * member suite (it has the log) and a not-yet-started sibling (no
     * subfolder matching the run root's name at all yet).
     *
     * Still returns null (falling through to the suite's own name) if zero
     * or more than one candidate matches - should not happen in practice,
     * but the fallback stays in place rather than guessing.
     *
     * A collection nested inside a Test Suites folder mirrors that folder
     * path here too (same as resolveCollectionNameFromMetadata() above) -
     * the top-level sibling is the intermediate folder (e.g.
     * "Regression"), which itself satisfies neither condition directly.
     * That intermediate folder can hold more than just the path down to
     * one collection, too - a Test Suite and a Collection organized in
     * the same Test Suites folder land as two siblings under it (e.g.
     * "Regression/MemberSuite" next to "Regression/MyCollection").
     * descendToCollectionFolder() explores every child at every level
     * below each top-level candidate, not just a single unbranching
     * chain, looking for the first directory, at any depth, that
     * satisfies the two conditions.
     */
    private static String resolveCollectionNameFromSiblingFolder(File targetRunDir, String ownSuiteName) {
        File[] siblingDirs = targetRunDir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!siblingDirs) {
            return null
        }
        List<File> candidates = siblingDirs.findAll { it.name != ownSuiteName && it.name != 'requests' && it.name != '.metadata' }
        String runRootName = targetRunDir.name
        List<String> matches = candidates.collect { descendToCollectionFolder(it, runRootName, 0) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    /**
     * Searches `dir` and everything below it for the first directory, at
     * any depth along any branch, with a subfolder named exactly like the
     * run root and no execution0.log in it - see
     * resolveCollectionNameFromSiblingFolder() above. Returns that
     * directory's own name, or null if nothing under this whole subtree
     * satisfies it (e.g. a genuine member suite candidate, which has
     * execution0.log under its own run-root-named subfolder). Ambiguous
     * results (more than one directory in the subtree satisfying it) also
     * return null rather than guessing which one is real.
     */
    private static String descendToCollectionFolder(File dir, String runRootName, int depth) {
        if (depth > 20) {
            return null
        }
        File matchingSubDir = new File(dir, runRootName)
        if (matchingSubDir.isDirectory() && !new File(matchingSubDir, 'execution0.log').isFile()) {
            return dir.name
        }
        File[] children = dir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!children) {
            return null
        }
        List<String> matches = children.collect { descendToCollectionFolder(it, runRootName, depth + 1) }.findAll { it != null }
        return matches.size() == 1 ? matches[0] : null
    }

    /**
     * Copies history/ from the most recently generated report into
     * allure-results/history so the new report's Trend/History graphs
     * accumulate across runs. In single-file mode there's no report
     * folder to read history back out of afterwards (it's embedded in the
     * .html), so this only looks at prior folder-mode reports - which is
     * fine, since carrying single-file history forward would need parsing
     * it back out of the HTML, not worth the complexity for what trend
     * graphs are for.
     */
    private static void carryHistoryForward(File reportBaseDir, File resultsDir, boolean singleFile) {
        if (singleFile || !reportBaseDir.exists()) {
            return
        }
        File[] previousRuns = reportBaseDir.listFiles({ File f -> f.isDirectory() && !f.name.startsWith('.staging-') } as FileFilter)
        if (!previousRuns) {
            return
        }
        File mostRecent = previousRuns.max { it.lastModified() }
        File previousHistory = new File(mostRecent, 'history')
        if (previousHistory.isDirectory()) {
            FileUtils.copyDirectory(previousHistory, new File(resultsDir, 'history'))
        }
    }

    static void startTestCase(TestCaseContext testCaseContext) {
        try {
            if (!AllureConfig.isEnabled()) {
                return
            }
            String testCaseId = testCaseContext.getTestCaseId()
            String uuid = UUID.randomUUID().toString()
            String name = readableName(testCaseId)
            String suiteName = resolveSuiteNameQuiet() ?: currentSuiteName ?: 'Suite'

            TestResult result = new TestResult()
            result.setUuid(uuid)
            // Suite name + this suite's occurrence discriminator, not
            // just the test case id alone - see suiteOccurrenceDiscriminator() for why.
            // Two different suites can legitimately share a reusable test
            // case (same testCaseId, different suite), and a Test Suite
            // Collection can legitimately run the very same suite more than
            // once - a plain testCaseId-only historyId collides in both
            // cases, and Allure treats same-historyId results as retries of
            // one logical test, silently collapsing every earlier one out
            // of the default report view.
            result.setHistoryId(ResultsUtils.md5("${suiteName}#${suiteOccurrenceDiscriminator(suiteName)}|${testCaseId}"))
            result.setTestCaseId(testCaseId)
            result.setName(name)
            result.setFullName(testCaseId)
            result.setStart(System.currentTimeMillis())
            result.setStage(Stage.RUNNING)
            result.setStatus(Status.PASSED)
            // The visible "Suite" grouping, not historyId (already
            // disambiguated above, independent of this) - see
            // suiteLabelSuffix() for why this needs its own distinguishing
            // suffix when the same suite runs more than once.
            result.setLabels([
                ResultsUtils.createSuiteLabel("${suiteName}${suiteLabelSuffix(suiteName)}"),
                ResultsUtils.createPackageLabel(testCaseId?.replace('/', '.') ?: 'UnknownTestCase'),
                ResultsUtils.createHostLabel(),
                ResultsUtils.createThreadLabel(),
                ResultsUtils.createFrameworkLabel('Katalon Studio'),
                ResultsUtils.createLanguageLabel('Groovy'),
                ResultsUtils.createLabel('executionProfile', RunConfiguration.getExecutionProfile() ?: 'default'),
            ])

            Allure.getLifecycle().scheduleTestCase(uuid, result)
            Allure.getLifecycle().startTestCase(uuid)
        } catch (Throwable t) {
            logger.logWarning("[Allure] Failed to start test case reporting: ${t}")
        }
    }

    static void finishTestCase(TestCaseContext testCaseContext) {
        try {
            if (!AllureConfig.isEnabled()) {
                return
            }
            Optional<String> current = Allure.getLifecycle().getCurrentTestCase()
            if (!current.isPresent()) {
                return
            }
            String uuid = current.get()
            Status status = mapStatus(testCaseContext.getTestCaseStatus())
            String message = testCaseContext.getMessage()

            if (status != Status.PASSED && message) {
                attachText('Failure details', message)
            }

            boolean shouldScreenshot = AllureConfig.attachScreenshotAlways() ||
                (AllureConfig.attachScreenshotOnFailure() && status != Status.PASSED)
            if (shouldScreenshot) {
                captureScreenshot(status == Status.PASSED ? 'Screenshot' : 'Screenshot on failure')
            }

            // See detectActiveBrowser() - only known reliably now, after
            // the test body has actually run.
            String activeBrowser = detectActiveBrowser()
            String suiteName = resolveSuiteNameQuiet() ?: currentSuiteName ?: 'Suite'

            Allure.getLifecycle().updateTestCase(uuid, { TestResult tr ->
                tr.setStatus(status)
                tr.setStage(Stage.FINISHED)
                tr.setStop(System.currentTimeMillis())
                if (status != Status.PASSED && message) {
                    StatusDetails details = new StatusDetails()
                    details.setMessage(firstLine(message))
                    details.setTrace(message)
                    tr.setStatusDetails(details)
                }
                if (activeBrowser) {
                    tr.getLabels()?.find { it.getName() == 'suite' }?.setValue("${suiteName} (${activeBrowser})")
                }
            })
            if (activeBrowser) {
                recordActiveBrowserInEnvironment(AllureConfig.getResultsDir(), activeBrowser)
            }
            Allure.getLifecycle().stopTestCase(uuid)
            Allure.getLifecycle().writeTestCase(uuid)

            // Steps are captured later, from this suite's own
            // AfterTestSuite (see processPendingSteps()), not here: this
            // test case's own execution0.log is still open for writing at
            // this exact point - Katalon doesn't close it until every
            // AfterTestCase listener for this test case, not just this
            // one, has returned. RunConfiguration.getReportFolder() is
            // still safe to read now though (correct for both dedicated
            // per-test-case folders and combined per-suite ones) - only
            // the file read itself needs to wait.
            if (AllureConfig.captureSteps()) {
                queuePendingSteps(uuid, testCaseContext.getTestCaseId())
            }
        } catch (Throwable t) {
            logger.logWarning("[Allure] Failed to finish test case reporting: ${t}")
        }
    }

    /**
     * Records that this now-finished (and already-written) test case's
     * steps still need to be captured, for processPendingSteps() to pick
     * up later from a safe point (this suite's own AfterTestSuite). A
     * file, not a static field - Katalon does not guarantee test cases
     * within a suite share enough state for a static field to survive
     * between them.
     */
    private static void queuePendingSteps(String uuid, String testCaseId) {
        try {
            String logFolder = safeCall { RunConfiguration.getReportFolder() }
            if (!logFolder || !logFolder.trim()) {
                return
            }
            File resultsDir = AllureConfig.getResultsDir()
            withRunLock(resultsDir) {
                new File(resultsDir, '.allure-pending-steps.txt').append("${uuid}|${testCaseId}|${logFolder}\n")
            }
        } catch (Throwable ignored) { }
    }

    /**
     * Processes every test case queued by queuePendingSteps() since the
     * last time this ran: parses its execution log into a step tree and
     * patches that step tree directly into its already-written
     * <uuid>-result.json (Allure.getLifecycle() has already forgotten this
     * test case by now - writeTestCase() both persists it and drops it
     * from in-memory tracking - so the JSON file itself is edited, not the
     * live lifecycle). Runs once per suite finish (AfterTestSuite) and
     * processes EVERY currently-queued entry, not just this suite's own -
     * since suites can run in parallel, whichever suite's AfterTestSuite
     * happens to run first ends up doing the processing for others too.
     *
     * That parallelism is exactly why a short, fixed retry isn't the whole
     * fix. Two distinct timing gaps exist:
     *  - Reading a test case's log from inside AfterTestCase itself can
     *    fail with a mid-write XML parse error, since Katalon doesn't close
     *    it until every AfterTestCase listener for that test case (not just
     *    this one) has returned. Deferring to AfterTestSuite fixes this.
     *  - For a suite whose before-listeners/test-case/after-listeners all
     *    share ONE combined log file (no per-test-case subfolder), THIS
     *    suite's own finishSuite() call - this exact method call - is
     *    itself still being appended to that same file while it runs. This
     *    part is NOT a short gap: a different, faster-finishing suite can
     *    race ahead and try to process a still-running suite's entry long
     *    before that suite's own finishSuite() has even returned.
     *    Structurally, no amount of retrying from a DIFFERENT suite's call
     *    can out-wait that, and no amount of retrying from the writing
     *    suite's OWN call can either, since the file can't close until that
     *    exact call returns - a bounded synchronous wait cannot resolve a
     *    dependency on its own return.
     *
     * So: parseWithRetry() below only ever covers the small, genuinely
     * bounded tail gap between a suite's own finishSuite() ending and its
     * log's true close. Anything that still fails is re-queued for a
     * *later* suite's finishSuite() to retry (which will have had more
     * real time to pass) - except when this is the final suite in the run
     * (isFinalSuite), where there is no later suite left to hand it to, so
     * it falls to retryPendingStepsSynchronously() instead, which blocks
     * this call rather than handing off to a background thread - see that
     * method for why a background thread is not safe here.
     */
    private static void processPendingSteps(File resultsDir, boolean isFinalSuite) {
        List<String> claimedLines = withRunLock(resultsDir) {
            File pendingFile = new File(resultsDir, '.allure-pending-steps.txt')
            if (!pendingFile.isFile()) {
                return []
            }
            List<String> lines = pendingFile.readLines()
            pendingFile.delete()
            return lines
        } as List<String>

        if (!claimedLines) {
            return
        }
        StringBuilder diag = new StringBuilder()
        List<String> stillPending = []
        claimedLines.each { line ->
            if (!line?.trim()) {
                return
            }
            String[] parts = line.split('\\|', 3)
            if (parts.length != 3) {
                return
            }
            String uuid = parts[0]
            String testCaseId = parts[1]
            String logFolder = parts[2]
            try {
                diag << "[Allure][step-diag] ${new Date()} uuid=${uuid} testCaseId=${testCaseId}\n"
                diag << "  logFolder=${logFolder} exists=${new File(logFolder).isDirectory()}\n"
                if (!new File(logFolder).isDirectory()) {
                    return
                }
                List<StepResult> steps = parseWithRetry(logFolder, testCaseId, diag)
                diag << "  parsed step count=${steps?.size()}\n"
                if (steps) {
                    patchResultFileWithSteps(resultsDir, uuid, steps)
                } else {
                    diag << "  not ready yet - ${isFinalSuite ? 'final suite, retrying synchronously' : 're-queueing for a later suite'}\n"
                    stillPending << line
                }
            } catch (Throwable t) {
                diag << "  EXCEPTION: ${t}\n"
                stillPending << line
            }
        }
        appendStepDiag(diag.toString())

        if (!stillPending) {
            return
        }
        if (isFinalSuite) {
            retryPendingStepsSynchronously(resultsDir, stillPending)
        } else {
            withRunLock(resultsDir) {
                new File(resultsDir, '.allure-pending-steps.txt').append(stillPending.collect { "${it}\n" }.join(''))
            }
        }
    }

    /**
     * Last resort for the final suite's own entries, which by definition
     * have no later suite left to re-queue to. Blocks this call (and so
     * this suite's own AfterTestSuite, and so the whole suite run) rather
     * than handing off to a background thread: a CI pipeline that runs
     * each Test Suite as its own separate katalonc.exe invocation has
     * exactly one suite per process, so that suite's finishSuite() is
     * always "the final suite" and that process has nothing left to do
     * once this call returns - it exits almost immediately afterward,
     * which would kill a daemon thread before it gets a real chance to
     * finish. A background thread only survives long enough to matter for
     * a suite sharing a longer-lived process (mid-collection, or an
     * interactive IDE session) - not for a standalone suite run, which is
     * exactly the case that needs this fallback most often. Blocking here
     * is the only way to guarantee the
     * retry actually completes before there is no process left to run it
     * in; the caller (finishSuite(), via processPendingSteps()) already
     * calls generateHtmlReport() right after this returns, so the patched
     * steps are included in the one and only report generation - no
     * separate regeneration needed.
     */
    private static void retryPendingStepsSynchronously(File resultsDir, List<String> pendingLines) {
        StringBuilder diag = new StringBuilder()
        try {
            diag << "[Allure][step-diag] ${new Date()} synchronous final-suite retry starting for ${pendingLines.size()} entries\n"
            pendingLines.each { line ->
                String[] parts = line.split('\\|', 3)
                if (parts.length != 3) {
                    return
                }
                String uuid = parts[0]
                String testCaseId = parts[1]
                String logFolder = parts[2]
                List<StepResult> steps = []
                for (int attempt = 1; attempt <= 20 && !steps; attempt++) {
                    try {
                        steps = parseStepsFromExecutionLog(logFolder, testCaseId, diag)
                    } catch (Throwable t) {
                        diag << "  uuid=${uuid} attempt ${attempt}/20 failed: ${t.getMessage()}\n"
                    }
                    if (!steps && attempt < 20) {
                        Thread.sleep(1000)
                    }
                }
                if (steps) {
                    patchResultFileWithSteps(resultsDir, uuid, steps)
                    diag << "  uuid=${uuid} succeeded after synchronous retry\n"
                } else {
                    diag << "  uuid=${uuid} gave up after 20 synchronous attempts\n"
                }
            }
        } finally {
            appendStepDiag(diag.toString())
        }
    }

    /**
     * Retries parseStepsFromExecutionLog() a few times, briefly, if the log
     * isn't fully closed yet - see processPendingSteps() for why this gap
     * exists and is expected to be small. Every attempt (including
     * failures) is logged to the diagnostic trail so a persistent failure
     * is visible rather than silently swallowed.
     */
    private static List<StepResult> parseWithRetry(String logFolder, String testCaseId, StringBuilder diag) {
        int maxAttempts = 5
        List<StepResult> steps = []
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                steps = parseStepsFromExecutionLog(logFolder, testCaseId, diag)
            } catch (Throwable t) {
                diag << "  attempt ${attempt}/${maxAttempts} failed: ${t.getMessage()}\n"
            }
            if (steps) {
                return steps
            }
            if (attempt < maxAttempts) {
                Thread.sleep(300)
            }
        }
        return steps
    }

    /**
     * Diagnostic trail for step capture, written straight to a file
     * because KeywordLogger output from a plain (non-@Keyword) static
     * method doesn't reliably show up in Katalon's persisted
     * execution0.log. Useful for troubleshooting a specific test case
     * whose steps didn't show up in a report.
     */
    private static void appendStepDiag(String text) {
        if (!text) {
            return
        }
        try {
            new File(RunConfiguration.getProjectDir(), 'allure-step-diag.txt').append(text)
        } catch (Throwable ignored) { }
    }

    /**
     * Reads an already-written <uuid>-result.json and splices a "steps"
     * array into it. Built by hand field-by-field, deliberately not via
     * Gson's reflection-based serialization of StepResult/Status: Allure's
     * own writer uses an internal, shaded Jackson with custom serializers
     * that lowercase enum values ("passed", not "PASSED") - a generic Gson
     * pass over the model objects would not reproduce that and could hand
     * the Allure report generator JSON it doesn't recognise. Hand-building
     * the exact shape Allure itself produces sidesteps needing access to
     * that internal serializer at all.
     */
    private static void patchResultFileWithSteps(File resultsDir, String uuid, List<StepResult> steps) {
        File resultFile = new File(resultsDir, "${uuid}-result.json")
        if (!resultFile.isFile()) {
            return
        }
        JsonObject resultJson = JsonParser.parseString(resultFile.text).getAsJsonObject()
        resultJson.add('steps', stepsToJsonArray(steps))
        resultFile.text = new GsonBuilder().create().toJson(resultJson)
    }

    private static com.google.gson.JsonArray stepsToJsonArray(List<StepResult> steps) {
        com.google.gson.JsonArray array = new com.google.gson.JsonArray()
        steps.each { step ->
            JsonObject obj = new JsonObject()
            obj.addProperty('name', step.getName())
            obj.addProperty('status', (step.getStatus() ?: Status.PASSED).toString().toLowerCase())
            obj.addProperty('stage', 'finished')
            if (step.getStatusDetails()?.getMessage()) {
                JsonObject details = new JsonObject()
                details.addProperty('message', step.getStatusDetails().getMessage())
                obj.add('statusDetails', details)
            }
            if (step.getStart() != null) {
                obj.addProperty('start', step.getStart())
            }
            if (step.getStop() != null) {
                obj.addProperty('stop', step.getStop())
            }
            obj.add('steps', stepsToJsonArray(step.getSteps() ?: []))
            obj.add('attachments', new com.google.gson.JsonArray())
            obj.add('parameters', new com.google.gson.JsonArray())
            array.add(obj)
        }
        return array
    }

    /**
     * Turns a suite's execution logs into a nested Allure step tree for one
     * test case, using Katalon's own TestSuiteXMLLogParser instead of a
     * hand-rolled XML parser. A strict javax.xml.parsers.DocumentBuilder
     * parse of execution0.log can fail with Xerces's fatal "XML document
     * structures must start and end within the same entity" even on a
     * file that is otherwise well-formed (single <?xml?>/<log>/</log>,
     * cleanly closed) and regardless of external DTD resolution.
     * Katalon's own TestSuiteXMLLogParser has its own
     * cleanUpXmlLogFile()/isMalformedXmlLine() pass that strips raw
     * control characters (matching [\p{Cntrl}&&[^\n\r\t]]+) before
     * parsing - bytes that are illegal in XML content but can end up
     * embedded in a step's logged message (e.g. from a captured HTTP
     * response body), invisibly, anywhere in a large file. Katalon's own
     * team already solved this for their own log format; re-implementing
     * that cleanup ourselves would just be re-deriving what their parser
     * already does, with more code and less confidence.
     */
    // com.kms.katalon.core.logging.model.* is marked @deprecated ("Replaced
    // by com.katalon.execution plugin"), but that plugin's API shape and
    // version availability across Katalon releases aren't verified.
    // Suppressed, not fixed: these classes work correctly across the
    // versions this bridge targets today.
    @SuppressWarnings('deprecation')
    private static List<StepResult> parseStepsFromExecutionLog(String logFolder, String testCaseId, StringBuilder diag) {
        // The single-arg overload only exists from some Katalon version
        // onward (present in Katalon Studio 11.4.0's core jar, absent from
        // Katalon Runtime Engine 11.3.0, which throws MissingMethodException
        // naming the two-arg overload as the only match). A plain null
        // monitor isn't safe for the two-arg form either - it calls
        // progressMonitor.beginTask(...) directly with no null-check - so a
        // real no-op NullProgressMonitor is required instead.
        TestSuiteLogRecord suiteRecord = new TestSuiteXMLLogParser().readTestSuiteLogFromXMLFiles(logFolder, new org.eclipse.core.runtime.NullProgressMonitor())
        List<TestCaseLogRecord> testCases = suiteRecord.getAllTestCaseLogRecords() ?: []
        TestCaseLogRecord match = testCases.find { matchesTestCaseId(it.getName(), testCaseId) }
        if (match == null) {
            diag << "  no TestCaseLogRecord matched testCaseId='${testCaseId}' - found names=${testCases.collect { it.getName() }}\n"
            return []
        }
        return convertLogRecordsToSteps(match.getChildRecords())
    }

    /**
     * TestCaseLogRecord.getName() (per Katalon's own parser source) is
     * either an explicit "name" property on the log record, or the raw,
     * unstripped "Start Test Case : <id>" message - unlike step names,
     * which the same parser does strip a "Start action : " prefix from.
     * Matching on either string containing the other covers both possible
     * shapes without depending on exactly which one a given Katalon
     * version produces.
     */
    private static boolean matchesTestCaseId(String recordName, String testCaseId) {
        if (!recordName || !testCaseId) {
            return false
        }
        return recordName == testCaseId || recordName.endsWith(testCaseId) || testCaseId.endsWith(recordName)
    }

    /**
     * This library's own listener actions (AllureReportBridge.*,
     * AllureKeywords.*) are filtered out; every other TestStepLogRecord
     * (Katalon's own model of one logged keyword call, already correctly
     * nested by Katalon's own parser) becomes one Allure step, recursively.
     */
    @SuppressWarnings('deprecation')
    private static List<StepResult> convertLogRecordsToSteps(ILogRecord[] records) {
        List<StepResult> result = []
        if (!records) {
            return result
        }
        records.each { ILogRecord rec ->
            if (!(rec instanceof TestStepLogRecord)) {
                return
            }
            String name = rec.getName()
            if (isInternalStepName(name)) {
                return
            }
            StepResult step = new StepResult()
            step.setName(name)
            step.setStart(rec.getStartTime())
            step.setStop(rec.getEndTime() > 0 ? rec.getEndTime() : rec.getStartTime())
            TestStatus.TestStatusValue statusValue = rec.getStatus()?.getStatusValue()
            step.setStatus(mapTestStatusValue(statusValue))
            if (statusValue?.isError() && rec.getMessage()) {
                StatusDetails details = new StatusDetails()
                details.setMessage(rec.getMessage())
                step.setStatusDetails(details)
            }
            step.setSteps(convertLogRecordsToSteps(rec.getChildRecords()))
            result << step
        }
        return result
    }

    private static Status mapTestStatusValue(TestStatus.TestStatusValue value) {
        switch (value) {
            case TestStatus.TestStatusValue.PASSED: return Status.PASSED
            case TestStatus.TestStatusValue.FAILED: return Status.FAILED
            case TestStatus.TestStatusValue.SKIPPED: return Status.SKIPPED
            case TestStatus.TestStatusValue.ERROR:
            case TestStatus.TestStatusValue.INCOMPLETE: return Status.BROKEN
            default: return Status.PASSED
        }
    }

    private static boolean isInternalStepName(String stepName) {
        return stepName != null && (stepName.contains('AllureReportBridge.') || stepName.contains('AllureKeywords.'))
    }

    /** Exposed for AllureKeywords.attachScreenshot() so tests can capture ad-hoc evidence too. */
    static void captureScreenshot(String name) {
        try {
            WebDriver driver = DriverFactory.getWebDriver()
            if (driver instanceof TakesScreenshot) {
                byte[] bytes = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES)
                Allure.getLifecycle().addAttachment(name, 'image/png', '.png', bytes)
            }
        } catch (Throwable ignored) {
            // No active WebUI driver (API/mobile-only test, or driver already closed) - skip silently
        }
    }

    static void attachText(String name, String content) {
        try {
            Allure.getLifecycle().addAttachment(name, 'text/plain', '.txt', content.getBytes('UTF-8'))
        } catch (Throwable ignored) { }
    }

    private static Status mapStatus(String katalonStatus) {
        switch (katalonStatus?.toUpperCase()) {
            case 'PASSED': return Status.PASSED
            case 'FAILED': return Status.FAILED
            case 'SKIPPED': return Status.SKIPPED
            case 'ERROR':
            case 'INCOMPLETE': return Status.BROKEN
            default: return Status.BROKEN
        }
    }

    /**
     * RunConfiguration-based resolution only - no TestSuiteContext needed,
     * so this can be called from any phase (including startTestCase(),
     * which Katalon never hands a TestSuiteContext to).
     * RunConfiguration.getExecutionSourceName() stays correct across
     * phases even though a plain static field written in one phase does
     * not, so this - not a cached field - is the safe thing to call fresh
     * every time a name is needed.
     */
    private static String resolveSuiteNameQuiet() {
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        if (executionSourceName && executionSourceName.trim()) {
            return executionSourceName
        }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }
        if (executionSource && executionSource.trim()) {
            return readableName(executionSource.replace('\\', '/')).replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
        }
        return null
    }

    /**
     * Resolves the real name of whatever is being run (Test Suite
     * Collection, Test Suite, or a lone Test Case) for the report filename
     * and the Allure "Suite" label. Katalon's
     * RunConfiguration.getExecutionSourceName() is documented for exactly
     * this but only ever reflects the individual sub-suite's own name, even
     * when run as part of a Test Suite Collection - resolveCollectionName()
     * below corrects for that specific case.
     *
     * Same resolution as resolveSuiteNameQuiet(), plus a TestSuiteContext
     * fallback (for phases that receive one) and diagnostic output written
     * to <project root>/allure-diag.txt, since KeywordLogger output from a
     * plain (non-@Keyword) static method doesn't reliably show up in
     * Katalon's persisted Reports/.../execution0.log.
     */
    private static String resolveSuiteName(TestSuiteContext testSuiteContext) {
        String testSuiteId = safeCall { testSuiteContext?.getTestSuiteId() }
        String executionSourceName = safeCall { RunConfiguration.getExecutionSourceName() }
        String executionSource = safeCall { RunConfiguration.getExecutionSource() }
        String executedEntity = safeCall { RunConfiguration.getExecutedEntity() }

        String resolved
        String via
        if (executionSourceName && executionSourceName.trim()) {
            resolved = executionSourceName
            via = 'getExecutionSourceName()'
        } else if (executionSource && executionSource.trim()) {
            resolved = readableName(executionSource.replace('\\', '/'))
            resolved = resolved.replaceAll(/(?i)\.(ts|tc|tsc)$/, '')
            via = 'getExecutionSource() path basename'
        } else if (testSuiteId && testSuiteId.trim()) {
            resolved = readableName(testSuiteId)
            via = 'testSuiteContext.getTestSuiteId()'
        } else {
            resolved = 'Suite'
            via = 'fallback default'
        }

        // Upgrade to the real Test Suite Collection name when this run is
        // one - see resolveCollectionName().
        String collectionName = safeCall { resolveCollectionName(currentRunDir(), resolved) }
        if (collectionName && collectionName.trim() && collectionName != resolved) {
            resolved = collectionName
            via = "${via}, overridden by Test Suite Collection name"
        }

        String diagText = """[Allure][diag] run at ${new Date()}
testSuiteContext.getTestSuiteId()               = '${testSuiteId}'
RunConfiguration.getExecutionSourceName()        = '${executionSourceName}'
RunConfiguration.getExecutionSource()            = '${executionSource}'
RunConfiguration.getExecutedEntity()             = '${executedEntity}'
=> resolved name = '${resolved}' (via ${via})
"""
        logger.logInfo(diagText)
        try {
            new File(RunConfiguration.getProjectDir(), 'allure-diag.txt').text = diagText
        } catch (Throwable ignored) { }

        return resolved
    }

    private static String safeCall(Closure<String> supplier) {
        try {
            return supplier.call()
        } catch (Throwable ignored) {
            return null
        }
    }

    private static String readableName(String id) {
        if (!id) {
            return 'Unknown'
        }
        String[] parts = id.split('/')
        return parts[parts.length - 1]
    }

    private static String firstLine(String text) {
        if (!text) {
            return ''
        }
        int idx = text.indexOf('\n')
        return idx > 0 ? text.substring(0, idx) : text
    }

    private static String safe(String value) {
        return (value == null || value.trim().isEmpty()) ? 'N/A' : value
    }

    /**
     * A Test Suite Collection's member suites can legitimately differ -
     * most commonly Browser, one member run with Chrome and another with
     * Firefox - but all of them share this one environment.properties
     * file. Each suite's own values are merged into whatever's already on
     * disk instead of overwriting it, so a key that varies across the run
     * ends up holding every distinct value seen so far (e.g.
     * "Chrome, Firefox") instead of just whichever suite happened to
     * start last. See startSuite()'s reset of this file for how a fresh
     * run avoids merging into an unrelated earlier run's leftovers.
     *
     * Browser is deliberately not one of the keys written here: this runs
     * at suite start, before any test case has actually run, so the only
     * "browser" available yet is the Run Configuration's configured one -
     * the same value that turned out to be misleading for the "Suite"
     * label (see detectActiveBrowser()), since a suite can have one
     * configured without ever opening it. recordActiveBrowserInEnvironment()
     * merges Browser in separately, from finishTestCase(), only once a
     * browser is confirmed actually open.
     */
    private static void writeEnvironmentProperties(File resultsDir) {
        Properties fresh = new Properties()
        fresh.setProperty('Project', safe(RunConfiguration.getProjectName()))
        fresh.setProperty('Execution Profile', safe(RunConfiguration.getExecutionProfile()))
        fresh.setProperty('Katalon Studio Version', safe(RunConfiguration.getAppVersion()))
        fresh.setProperty('OS', safe(RunConfiguration.getOS()))
        fresh.setProperty('Host', safe(RunConfiguration.getHostName()))
        fresh.setProperty('Java Version', safe(System.getProperty('java.version')))
        mergeIntoEnvironmentProperties(resultsDir, fresh)
    }

    /**
     * Merges Browser into environment.properties as soon as
     * finishTestCase() confirms a test case actually opened one -
     * writeEnvironmentProperties() above can't do this at suite start,
     * before any test case has run.
     */
    private static void recordActiveBrowserInEnvironment(File resultsDir, String browser) {
        Properties fresh = new Properties()
        fresh.setProperty('Browser', browser)
        mergeIntoEnvironmentProperties(resultsDir, fresh)
    }

    /**
     * Merges `fresh`'s keys into environment.properties, combining each
     * key's value with whatever's already on disk (see
     * writeEnvironmentProperties()) rather than overwriting the whole
     * file - a key already on disk but absent from `fresh` (e.g. Browser,
     * when this call is writeEnvironmentProperties()'s own suite-start
     * one) is carried forward unchanged, not dropped.
     */
    private static void mergeIntoEnvironmentProperties(File resultsDir, Properties fresh) {
        File envFile = new File(resultsDir, 'environment.properties')
        withRunLock(resultsDir) {
            Properties existing = new Properties()
            if (envFile.isFile()) {
                try {
                    envFile.withInputStream { existing.load(it) }
                } catch (Throwable ignored) { }
            }
            Properties merged = new Properties()
            (fresh.stringPropertyNames() + existing.stringPropertyNames()).each { key ->
                String newValue = fresh.getProperty(key)
                merged.setProperty(key, newValue == null ? existing.getProperty(key) : mergedEnvironmentValue(existing.getProperty(key), newValue))
            }
            envFile.withOutputStream { out ->
                merged.store(out, 'Generated by AllureTestListener - do not edit by hand')
            }
        }
    }

    private static String mergedEnvironmentValue(String existingValue, String newValue) {
        if (!existingValue || existingValue == newValue) {
            return newValue
        }
        List<String> values = existingValue.split(',\\s*') as List
        if (!values.contains(newValue)) {
            values << newValue
        }
        return values.join(', ')
    }

    private static String detectBrowser() {
        try {
            return DriverFactory.getExecutedBrowser()?.toString()
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * getExecutedBrowser() reflects the Run Configuration's configured
     * browser, not whether this specific test case actually opened one -
     * a pure API test case sharing a Run Configuration with a nominal
     * browser selection still reports it, even though it's never used.
     * Checking for an actually-open driver instead - only meaningful once
     * the test body has run, which is why finishTestCase() uses this and
     * startTestCase() doesn't - distinguishes "this suite genuinely used
     * a browser" from "a browser happened to be configured for it".
     */
    private static String detectActiveBrowser() {
        try {
            return DriverFactory.getWebDriver() != null ? detectBrowser() : null
        } catch (Throwable ignored) {
            return null
        }
    }

    private static void writeExecutorJson(File resultsDir) {
        Map ci = detectCI()
        JsonObject json = new JsonObject()
        json.addProperty('name', ci.name as String)
        json.addProperty('type', ci.type as String)
        if (ci.buildName) json.addProperty('buildName', ci.buildName as String)
        if (ci.buildUrl) json.addProperty('buildUrl', ci.buildUrl as String)
        json.addProperty('buildOrder', (ci.buildOrder ?: 1) as Integer)
        new File(resultsDir, 'executor.json').text = new GsonBuilder().setPrettyPrinting().create().toJson(json)
    }

    private static Map detectCI() {
        Map<String, String> e = System.getenv()
        if (e['JENKINS_URL']) {
            return [name: 'Jenkins', type: 'jenkins',
                    buildName: e['JOB_NAME'], buildUrl: e['BUILD_URL'],
                    buildOrder: toInt(e['BUILD_NUMBER'])]
        }
        if (e['TF_BUILD'] || e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI']) {
            String collectionUri = e['SYSTEM_TEAMFOUNDATIONCOLLECTIONURI'] ?: ''
            String project = e['SYSTEM_TEAMPROJECT'] ?: ''
            String buildId = e['BUILD_BUILDID'] ?: ''
            String buildUrl = (collectionUri && project && buildId) ?
                "${collectionUri}${project}/_build/results?buildId=${buildId}" : ''
            return [name: 'Azure Pipelines', type: 'azure',
                    buildName: e['BUILD_DEFINITIONNAME'], buildUrl: buildUrl,
                    buildOrder: toInt(buildId)]
        }
        if (e['GITHUB_ACTIONS']) {
            String serverUrl = e['GITHUB_SERVER_URL'] ?: 'https://github.com'
            String repo = e['GITHUB_REPOSITORY'] ?: ''
            String runId = e['GITHUB_RUN_ID'] ?: ''
            return [name: 'GitHub Actions', type: 'github',
                    buildName: e['GITHUB_WORKFLOW'], buildUrl: "${serverUrl}/${repo}/actions/runs/${runId}",
                    buildOrder: toInt(e['GITHUB_RUN_NUMBER'])]
        }
        if (e['GITLAB_CI']) {
            return [name: 'GitLab CI', type: 'gitlab',
                    buildName: e['CI_JOB_NAME'], buildUrl: e['CI_JOB_URL'],
                    buildOrder: toInt(e['CI_PIPELINE_IID'])]
        }
        return [name: 'Local Run', type: 'local', buildOrder: 1]
    }

    private static int toInt(String value) {
        return (value != null && value.isInteger()) ? value.toInteger() : 0
    }

    private static void writeCategoriesJson(File resultsDir) {
        File src = AllureConfig.getCategoriesFile()
        if (src.exists()) {
            new File(resultsDir, 'categories.json').text = src.text
        }
    }
}

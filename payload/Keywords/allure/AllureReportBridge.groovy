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
                boolean continuingRun = thisRunDir != null && thisRunDir.absolutePath == (marker.runDir as String)
                if (!continuingRun) {
                    if (AllureConfig.cleanResultsBeforeRun()) {
                        clearPreviousResults(resultsDir)
                    }
                    marker.reportPath = ''
                    // Fresh run: forget which sub-suites (if any) had
                    // already reported themselves complete under a
                    // previous, unrelated run - see shouldGenerateReportNow().
                    new File(resultsDir, '.allure-collection-progress.txt').delete()
                }
                writeRunMarker(resultsDir, thisRunDir?.absolutePath ?: '', marker.reportPath as String)
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
        try {
            reportBaseDir.mkdirs()
            boolean singleFile = AllureConfig.singleFileReport()
            carryHistoryForward(reportBaseDir, resultsDir, singleFile)

            if (!runAllureGenerate(resultsDir, stagingDir, singleFile)) {
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
            logger.logWarning("[Allure] Could not auto-generate the HTML report - is 'allure' on PATH? (${t.getMessage()})")
        } finally {
            if (stagingDir.exists()) {
                FileUtils.deleteQuietly(stagingDir)
            }
        }
    }

    private static boolean runAllureGenerate(File resultsDir, File outputDir, boolean singleFile) {
        List<String> baseCommand = ['allure', 'generate', resultsDir.absolutePath, '--clean', '-o', outputDir.absolutePath]
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
            logger.logWarning('[Allure] "allure generate" failed - install the Allure commandline (npm install -g allure-commandline) or set allure.auto.generate.report=false. ' +
                "Output: ${output.take(500)}")
            return false
        }
        return true
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
     * - never touches history/, environment.properties, executor.json, or
     * categories.json (all rewritten fresh below anyway), and never
     * touches anything else a user might have placed in that folder.
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
     * This specific suite execution's own top-level report folder name
     * under Reports/<run-ts>/ (e.g. "TunnelTestSuite_8b358f94"), derived
     * from RunConfiguration.getReportFolder() - see
     * shouldGenerateReportNow() for why this, not the suite's name, is the
     * key that correctly distinguishes repeated same-named suites within
     * one collection. Returns null (forcing the safe fallback) if
     * getReportFolder() is blank or isn't actually under runDir.
     */
    private static String suiteInstanceKey(File runDir) {
        try {
            String reportFolder = RunConfiguration.getReportFolder()
            if (!reportFolder || !reportFolder.trim()) {
                return null
            }
            String runDirCanonical = runDir.canonicalPath
            File cursor = new File(reportFolder)
            int guard = 0
            while (cursor?.parentFile != null && cursor.parentFile.canonicalPath != runDirCanonical && guard++ < 20) {
                cursor = cursor.parentFile
            }
            return (cursor?.parentFile?.canonicalPath == runDirCanonical) ? cursor.name : null
        } catch (Throwable ignored) {
            return null
        }
    }

    /**
     * 0-based position of THIS suite occurrence among every occurrence of
     * the SAME underlying suite (matched by plan.jsonl's entityId, e.g.
     * "Test Suites/API Test Suite") within the current run's plan - used to
     * make startTestCase()'s historyId unique per occurrence. Always 0 when
     * a suite appears only once, by far the common case - this only
     * differs when the very same suite is deliberately used more than once
     * inside one Test Suite Collection.
     *
     * Katalon's own per-run disambiguation suffix on the report folder
     * (e.g. "API Test Suite_45ff52a0") can't be used for this directly: it
     * is regenerated on every run, so baking it into historyId would defeat
     * Allure's trend graphs across days. Position within plan.jsonl's
     * children array reflects the collection's *configured* suite order
     * instead, which stays the same run to run.
     *
     * Falls back to 0 (not null) on any failure, since 0 is exactly what
     * every single-occurrence suite already gets - this can only ever fail
     * to disambiguate a genuine duplicate, never misidentify a suite that
     * only appears once.
     */
    private static int suiteOccurrenceOrdinal() {
        try {
            File runDir = currentRunDir()
            String myKey = runDir != null ? suiteInstanceKey(runDir) : null
            if (runDir == null || myKey == null) {
                return 0
            }
            File planFile = new File(runDir, 'plan.jsonl')
            if (!planFile.isFile()) {
                return 0
            }
            String firstLine
            planFile.withReader('UTF-8') { reader -> firstLine = reader.readLine() }
            if (!firstLine || !firstLine.trim()) {
                return 0
            }
            JsonObject root = JsonParser.parseString(firstLine).getAsJsonObject()
            JsonObject execution = root.has('execution') ? root.getAsJsonObject('execution') : null
            if (execution == null || execution.get('kind')?.getAsString() != 'TEST_SUITE_COLLECTION') {
                return 0
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
                return 0
            }
            List<JsonObject> siblings = suiteChildren.findAll { it.get('entityId')?.getAsString() == myEntityId }
            int ordinal = siblings.findIndexOf { topLevelExecutionDirSegment(it) == myKey }
            return ordinal >= 0 ? ordinal : 0
        } catch (Throwable ignored) {
            return 0
        }
    }

    /**
     * The top-level report folder segment (e.g. "API Test Suite_45ff52a0")
     * a plan.jsonl TEST_SUITE child will execute under, read from its first
     * TEST_SUITE_ATTEMPT child's executionDirPath - the same shape
     * suiteInstanceKey() derives from RunConfiguration.getReportFolder() at
     * runtime, so the two can be matched against each other.
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
        int slash = dirPath.indexOf('/')
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
     *  2. plan.jsonl isn't always written yet at this point: an early
     *     sub-suite's own startSuite() inside a Test Suite Collection can
     *     run before Katalon has flushed plan.jsonl to disk, even though
     *     the run's own folder tree already exists. For that case, fall
     *     back to getReportFolder()'s own grandparent directly - a
     *     standalone suite and a Test Suite Collection's own member suite
     *     both use the identical shape "<run-dir>/<SuiteName>/<run-ts>"
     *     (a collection does not add its own extra nesting level), so
     *     the grandparent is the correct run root either way, without
     *     needing plan.jsonl to exist yet.
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
            File grandparent = new File(reportFolder).parentFile?.parentFile
            return (grandparent != null && grandparent.isDirectory()) ? grandparent : null
        } catch (Throwable ignored) {
            return null
        }
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
     */
    private static String resolveCollectionName(File targetRunDir, String ownSuiteName) {
        if (targetRunDir == null) {
            return null
        }
        String viaMetadata = resolveCollectionNameFromMetadata(targetRunDir, ownSuiteName)
        return viaMetadata ?: resolveCollectionNameFromSiblingFolder(targetRunDir, ownSuiteName)
    }

    private static String resolveCollectionNameFromMetadata(File targetRunDir, String ownSuiteName) {
        File metadataDir = new File(targetRunDir, '.metadata')
        if (!new File(metadataDir, '.collection').isFile()) {
            return null
        }
        File[] entries = metadataDir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!entries || entries.length == 0) {
            return null
        }
        File match = entries.find { it.name != ownSuiteName }
        return (match ?: entries[0]).name
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
     */
    private static String resolveCollectionNameFromSiblingFolder(File targetRunDir, String ownSuiteName) {
        File[] siblingDirs = targetRunDir.listFiles({ File f -> f.isDirectory() } as FileFilter)
        if (!siblingDirs) {
            return null
        }
        List<File> candidates = siblingDirs.findAll { it.name != ownSuiteName && it.name != 'requests' }
        String runRootName = targetRunDir.name
        List<File> matches = candidates.findAll { File dir ->
            File matchingSubDir = new File(dir, runRootName)
            matchingSubDir.isDirectory() && !new File(matchingSubDir, 'execution0.log').isFile()
        }
        return matches.size() == 1 ? matches[0].name : null
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
            // Suite name + this suite's occurrence ordinal, not just the
            // test case id alone - see suiteOccurrenceOrdinal() for why.
            // Two different suites can legitimately share a reusable test
            // case (same testCaseId, different suite), and a Test Suite
            // Collection can legitimately run the very same suite more than
            // once - a plain testCaseId-only historyId collides in both
            // cases, and Allure treats same-historyId results as retries of
            // one logical test, silently collapsing every earlier one out
            // of the default report view.
            result.setHistoryId(ResultsUtils.md5("${suiteName}#${suiteOccurrenceOrdinal()}|${testCaseId}"))
            result.setTestCaseId(testCaseId)
            result.setName(name)
            result.setFullName(testCaseId)
            result.setStart(System.currentTimeMillis())
            result.setStage(Stage.RUNNING)
            result.setStatus(Status.PASSED)
            result.setLabels([
                ResultsUtils.createSuiteLabel(suiteName),
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
            })
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

    private static void writeEnvironmentProperties(File resultsDir) {
        Properties env = new Properties()
        env.setProperty('Project', safe(RunConfiguration.getProjectName()))
        env.setProperty('Execution Profile', safe(RunConfiguration.getExecutionProfile()))
        env.setProperty('Katalon Studio Version', safe(RunConfiguration.getAppVersion()))
        env.setProperty('Browser', safe(detectBrowser()))
        env.setProperty('OS', safe(RunConfiguration.getOS()))
        env.setProperty('Host', safe(RunConfiguration.getHostName()))
        env.setProperty('Java Version', safe(System.getProperty('java.version')))
        new File(resultsDir, 'environment.properties').withOutputStream { out ->
            env.store(out, 'Generated by AllureTestListener - do not edit by hand')
        }
    }

    private static String detectBrowser() {
        try {
            return DriverFactory.getExecutedBrowser()?.toString()
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

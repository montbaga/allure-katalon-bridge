package allure

import com.kms.katalon.core.configuration.RunConfiguration

/**
 * Reads Include/config/allure/allure.properties, with every key overridable
 * via an ALLURE_<KEY_IN_UPPER_SNAKE_CASE> environment variable so CI systems
 * can redirect output without editing files checked into the repo.
 */
class AllureConfig {

    private static final String CONFIG_RELATIVE_PATH = 'Include/config/allure/allure.properties'

    private static final String DEFAULT_CATEGORIES_RELATIVE_PATH = 'Include/config/allure/categories.json'

    private static final String DEFAULT_RESULTS_DIR_NAME = 'allure-results'

    private static final String DEFAULT_REPORT_DIR_NAME = 'allure-report'

    private static Properties fileProps

    private static synchronized Properties fileProperties() {
        if (fileProps == null) {
            fileProps = new Properties()
            File configFile = new File(RunConfiguration.getProjectDir(), CONFIG_RELATIVE_PATH)
            if (configFile.exists()) {
                configFile.withInputStream { stream -> fileProps.load(stream) }
            }
        }
        return fileProps
    }

    private static String read(String key, String defaultValue) {
        // Every key here already starts with "allure." (e.g.
        // "allure.results.dir"), so upper-casing and swapping dots for
        // underscores alone already produces "ALLURE_RESULTS_DIR" - no
        // extra "ALLURE_" prefix needed on top of that.
        String envKey = key.toUpperCase().replace('.', '_')
        String envValue = System.getenv(envKey)
        if (envValue == null || envValue.trim().isEmpty()) {
            envValue = savedEnvironment().getProperty(envKey)
        }
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim()
        }
        return fileProperties().getProperty(key, defaultValue)
    }

    private static final String SAVED_ENV_FILE = '.allure-env.properties'

    /** Saves ALLURE_* variables for the run - Katalon doesn't pass them to parallel test case processes. */
    static void saveEnvironment(File runDir) {
        Properties vars = new Properties()
        System.getenv().each { String name, String value ->
            if (name.toUpperCase().startsWith('ALLURE_') && value?.trim()) {
                vars.setProperty(name.toUpperCase(), value)
            }
        }
        if (runDir != null && !vars.isEmpty()) {
            new File(runDir, SAVED_ENV_FILE).withOutputStream { vars.store(it, 'ALLURE_* variables of this run') }
        }
    }

    /** What saveEnvironment() saved, found by walking up from this process's report folder. */
    private static Properties savedEnvironment() {
        Properties vars = new Properties()
        try {
            String reportFolder = RunConfiguration.getReportFolder()
            File dir = reportFolder ? new File(reportFolder) : null
            for (int i = 0; i < 10 && dir != null; i++) {
                File saved = new File(dir, SAVED_ENV_FILE)
                if (saved.isFile()) {
                    saved.withInputStream { vars.load(it) }
                    break
                }
                dir = dir.parentFile
            }
        } catch (Throwable ignored) { }
        return vars
    }

    private static File resolvePath(String configuredPath) {
        File file = new File(configuredPath)
        return file.isAbsolute() ? file : new File(RunConfiguration.getProjectDir(), configuredPath)
    }

    static boolean isEnabled() {
        return Boolean.parseBoolean(read('allure.enabled', 'true'))
    }

    static File getResultsDir() {
        return resolvePath(read('allure.results.dir', DEFAULT_RESULTS_DIR_NAME))
    }

    /**
     * Whether to clear this run's predecessor result files out of
     * allure.results.dir at the start of every suite, so each generated
     * report reflects only the run it's named after. Turn off if you run
     * multiple suites in true parallel against the same results
     * directory (a suite starting mid-run could wipe another suite's
     * still-in-progress results), or if you want reports to keep
     * accumulating every test case from every run in the session.
     */
    static boolean cleanResultsBeforeRun() {
        return Boolean.parseBoolean(read('allure.clean.results.before.run', 'true'))
    }

    static boolean attachScreenshotOnFailure() {
        return Boolean.parseBoolean(read('allure.attach.screenshot.on.failure', 'true'))
    }

    static boolean attachScreenshotAlways() {
        return Boolean.parseBoolean(read('allure.attach.screenshot.always', 'false'))
    }

    /** HTML of the open page (XML view hierarchy for mobile) when a test case doesn't pass. */
    static boolean attachPageSourceOnFailure() {
        return Boolean.parseBoolean(read('allure.attach.page.source.on.failure', 'true'))
    }

    /** Whether to honour an Allure test plan (run only the test cases it lists). */
    static boolean testPlanEnabled() {
        return Boolean.parseBoolean(read('allure.testplan.enabled', 'true'))
    }

    /** Test plan file - Allure TestOps sets it per rerun through ALLURE_TESTPLAN_PATH. Empty if none. */
    static String testPlanPath() {
        return read('allure.testplan.path', '')?.trim()
    }

    /** Each API request a test case sent (from Katalon's .har files), secrets masked. */
    static boolean attachHttp() {
        return Boolean.parseBoolean(read('allure.attach.http', 'true'))
    }

    /** e.g. allure.link.issue.pattern=https://jira.example.com/browse/{} - empty if not set. */
    static String linkPattern(String type) {
        return read("allure.link.${type}.pattern", '')?.trim()
    }

    static File getCategoriesFile() {
        return resolvePath(read('allure.categories.file', DEFAULT_CATEGORIES_RELATIVE_PATH))
    }

    /** Whether to run 'allure generate' automatically at the end of every suite, so the HTML report is sitting in allure.report.dir with no extra step. Requires the Allure commandline on PATH; silently skipped (with a log warning) if it isn't. */
    static boolean autoGenerateReport() {
        return Boolean.parseBoolean(read('allure.auto.generate.report', 'true'))
    }

    /**
     * Whether the generated report is a single self-contained .html file
     * (Allure's native "--single-file" mode - all data embedded inline, no
     * server needed, works when opened directly via file://) or the
     * regular multi-file folder (needs "allure open"/a server; only worth
     * it for very large suites where a single huge HTML file gets slow to
     * load in the browser).
     */
    static boolean singleFileReport() {
        return Boolean.parseBoolean(read('allure.report.single.file', 'true'))
    }

    static File getReportDir() {
        return resolvePath(read('allure.report.dir', DEFAULT_REPORT_DIR_NAME))
    }

    /**
     * Absolute path to the 'allure' commandline executable to use for
     * report generation. Only needed when the bridge can't find it on its
     * own - see AllureReportBridge's command resolution (checked before
     * falling back to common install locations, then the current user's
     * login shell PATH, then a bare "allure" relying on whatever PATH this
     * process already inherited). Overridable via ALLURE_COMMANDLINE_PATH.
     */
    static String getAllureCommandlinePath() {
        return read('allure.commandline.path', '')?.trim()
    }

    /**
     * Whether to automatically turn each test case's own Katalon execution
     * log (every keyword/script line Katalon itself already records, with
     * pass/fail per line) into nested Allure steps - no test script changes
     * needed. Reads an already-written, already-closed log file per test
     * case, so this can never affect the actual test outcome; turn off for
     * very large/slow suites if the extra per-test-case parsing overhead
     * isn't worth it, or if you only want the manual AllureKeywords.step()
     * calls you've explicitly added.
     */
    static boolean captureSteps() {
        return Boolean.parseBoolean(read('allure.capture.steps', 'true'))
    }

    /** Test-only hook so a single JVM run can pick up edited properties between tests. */
    static synchronized void reset() {
        fileProps = null
    }
}

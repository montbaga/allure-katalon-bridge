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
        String envKey = 'ALLURE_' + key.toUpperCase().replace('.', '_')
        String envValue = System.getenv(envKey)
        if (envValue != null && !envValue.trim().isEmpty()) {
            return envValue.trim()
        }
        return fileProperties().getProperty(key, defaultValue)
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

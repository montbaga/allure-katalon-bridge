package allure

import com.kms.katalon.core.annotation.Keyword
import io.qameta.allure.Allure
import io.qameta.allure.SeverityLevel

/**
 * Optional, opt-in enrichment API for Allure reporting from inside a Test
 * Case script or Cucumber glue code. The AllureTestListener already gives
 * every test case a result with zero code changes; call these only where
 * you want finer detail (nested steps, attachments, links, severity...).
 *
 * Usage from a Test Case script:
 *   CustomKeywords.'allure.AllureKeywords.step'('Log in as admin', {
 *       WebUI.setText(findTestObject('Page/input_Username'), 'admin')
 *       WebUI.setText(findTestObject('Page/input_Password'), 'pwd')
 *       WebUI.click(findTestObject('Page/button_Login'))
 *   })
 */
class AllureKeywords {

    @Keyword
    static void step(String stepName, Closure body) {
        Allure.step(stepName, body as Allure.ThrowableRunnableVoid)
    }

    @Keyword
    static void attachText(String name, String content) {
        Allure.addAttachment(name, 'text/plain', content ?: '', '.txt')
    }

    @Keyword
    static void attachJson(String name, String jsonContent) {
        Allure.addAttachment(name, 'application/json', jsonContent ?: '', '.json')
    }

    @Keyword
    static void attachHtml(String name, String htmlContent) {
        Allure.addAttachment(name, 'text/html', htmlContent ?: '', '.html')
    }

    @Keyword
    static void attachFile(String name, String filePath) {
        File file = new File(filePath)
        if (file.exists()) {
            file.withInputStream { stream -> Allure.addAttachment(name, stream) }
        }
    }

    /** Captures a screenshot of the active WebUI browser session, if any. No-op otherwise. */
    @Keyword
    static void attachScreenshot(String name = 'Screenshot') {
        AllureReportBridge.captureScreenshot(name)
    }

    @Keyword
    static void epic(String name) {
        Allure.epic(name)
    }

    @Keyword
    static void feature(String name) {
        Allure.feature(name)
    }

    @Keyword
    static void story(String name) {
        Allure.story(name)
    }

    /** level: blocker | critical | normal | minor | trivial (case-insensitive). Falls back to normal. */
    @Keyword
    static void severity(String level) {
        try {
            Allure.label('severity', SeverityLevel.valueOf(level.toUpperCase()).value())
        } catch (Throwable ignored) {
            Allure.label('severity', SeverityLevel.NORMAL.value())
        }
    }

    @Keyword
    static void label(String name, String value) {
        Allure.label(name, value)
    }

    @Keyword
    static void description(String text) {
        Allure.description(text)
    }

    @Keyword
    static void link(String url) {
        Allure.link(url)
    }

    @Keyword
    static void link(String name, String url) {
        Allure.link(name, url)
    }

    @Keyword
    static void issue(String name, String url) {
        Allure.issue(name, url)
    }

    @Keyword
    static void tmsLink(String name, String url) {
        Allure.tms(name, url)
    }

    @Keyword
    static void parameter(String name, String value) {
        Allure.parameter(name, value)
    }
}

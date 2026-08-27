import static com.kms.katalon.core.testobject.ObjectRepository.findTestObject
import com.kms.katalon.core.webui.keyword.WebUiBuiltInKeywords as WebUI
import com.kms.katalon.core.mobile.keyword.MobileBuiltInKeywords as Mobile
import com.kms.katalon.core.cucumber.keyword.CucumberBuiltinKeywords as CucumberKW
import com.kms.katalon.core.webservice.keyword.WSBuiltInKeywords as WS
import com.kms.katalon.core.windows.keyword.WindowsBuiltinKeywords as Windows
import com.kms.katalon.core.testng.keyword.TestNGBuiltinKeywords as TestNGKW
import static com.kms.katalon.core.testobject.ObjectRepository.findWindowsObject
import static com.kms.katalon.core.testdata.TestDataFactory.findTestData
import static com.kms.katalon.core.testcase.TestCaseFactory.findTestCase
import static com.kms.katalon.core.checkpoint.CheckpointFactory.findCheckpoint
import com.kms.katalon.core.model.FailureHandling as FailureHandling
import com.kms.katalon.core.testcase.TestCase as TestCase
import com.kms.katalon.core.testdata.TestData as TestData
import com.kms.katalon.core.testobject.TestObject as TestObject
import com.kms.katalon.core.checkpoint.Checkpoint as Checkpoint
import org.openqa.selenium.Keys as Keys
import internal.GlobalVariable as GlobalVariable

WebUI.openBrowser(null)

WebUI.navigateToUrl('https://katalon-demo-cura.herokuapp.com/')

WebUI.maximizeWindow()

WebUI.click(findTestObject('Page_CURA Healthcare Service/a_btn-make-appointment'))

WebUI.click(findTestObject('Page_CURA Healthcare Service/input_Username'))

WebUI.setText(findTestObject('Page_CURA Healthcare Service/input_Username_1'), user)

WebUI.setEncryptedText(findTestObject('Page_CURA Healthcare Service/input_Password'), password)

WebUI.click(findTestObject('Page_CURA Healthcare Service/button_btn-login'))

WebUI.selectOptionByValue(findTestObject('Page_CURA Healthcare Service/select_Facility'), 'Seoul CURA Healthcare Center', 
    false)

WebUI.click(findTestObject('Page_CURA Healthcare Service/label_Apply for hospital readmission'))

WebUI.click(findTestObject('Page_CURA Healthcare Service/input_radio_program_medicaid'))

WebUI.click(findTestObject('Page_CURA Healthcare Service/span_glyphicon glyphicon-calendar'))

WebUI.click(findTestObject('Page_CURA Healthcare Service/td_29'))

WebUI.click(findTestObject('Page_CURA Healthcare Service/div_Comment'))

WebUI.setText(findTestObject('Page_CURA Healthcare Service/textarea_Comment'), 'klmlk')

WebUI.click(findTestObject('Page_CURA Healthcare Service/button_btn-book-appointment'))

WebUI.assertElementText(findTestObject('Page_CURA Healthcare Service/p_Please be informed that your appointment has b'), 
    'Please be informed that your appointment has been booked as following:', 0)


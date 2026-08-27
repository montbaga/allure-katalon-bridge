import static com.kms.katalon.core.testobject.ObjectRepository.findTestObject

import com.kms.katalon.core.testobject.ResponseObject
import com.kms.katalon.core.webservice.keyword.WSBuiltInKeywords as WS

import groovy.json.JsonSlurper
import internal.GlobalVariable

// --- authenticate ---
// Object Repository contains "DummyJSONLogin"; there is no object named "Login".
ResponseObject loginRes = WS.sendRequest(findTestObject('Object Repository/DummyJSONLogin'))
WS.verifyResponseStatusCode(loginRes, 200)

String loginBody = loginRes.getResponseBodyContent()
def json = new JsonSlurper().parseText(loginBody)

// DummyJSON currently returns accessToken. Keep token as fallback for older response formats.
String token = (json.accessToken ?: json.token ?: '').toString()
assert token.trim() : "accessToken was empty/missing in login response. Response body: ${loginBody}"

GlobalVariable.accessToken = token
println('Token prefix: ' + GlobalVariable.accessToken.substring(0, Math.min(25, GlobalVariable.accessToken.length())) + '...')

// --- use the token ---
ResponseObject meRes = WS.sendRequest(findTestObject('Object Repository/GetMe', [('token') : GlobalVariable.accessToken]))
WS.verifyResponseStatusCode(meRes, 200)
WS.verifyElementPropertyValue(meRes, 'username', 'emilys')
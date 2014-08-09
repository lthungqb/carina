/*
 * Copyright 2013 QAPROSOFT (http://qaprosoft.com/).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.qaprosoft.carina.core.foundation.listeners;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.jfree.util.Log;
import org.testng.IResultMap;
import org.testng.ITestContext;
import org.testng.ITestNGMethod;
import org.testng.ITestResult;

import com.qaprosoft.carina.core.foundation.jira.Jira;
import com.qaprosoft.carina.core.foundation.log.GlobalTestLog;
import com.qaprosoft.carina.core.foundation.log.GlobalTestLog.Type;
import com.qaprosoft.carina.core.foundation.report.ReportContext;
import com.qaprosoft.carina.core.foundation.report.TestResultItem;
import com.qaprosoft.carina.core.foundation.report.TestResultType;
import com.qaprosoft.carina.core.foundation.retry.RetryCounter;
import com.qaprosoft.carina.core.foundation.utils.Configuration;
import com.qaprosoft.carina.core.foundation.utils.Configuration.Parameter;
import com.qaprosoft.carina.core.foundation.utils.DateUtils;
import com.qaprosoft.carina.core.foundation.utils.Messager;
import com.qaprosoft.carina.core.foundation.utils.SpecialKeywords;
import com.qaprosoft.carina.core.foundation.utils.StringGenerator;
import com.qaprosoft.carina.core.foundation.utils.naming.TestNamingUtil;
import com.qaprosoft.carina.core.foundation.utils.parser.XLSDSBean;

public abstract class AbstractTestListener extends TestArgsListener
{
	private static final int MAX_COUNT = Configuration.getInt(Parameter.RETRY_COUNT);

	@Override
	public void onStart(ITestContext testContext)
	{
		testContext.setAttribute(SpecialKeywords.UUID, StringGenerator.generateWordAN(8));
	}
	
	@Override
	public void onTestStart(ITestResult result)
	{
		super.onTestStart(result);
		
		if (!result.getTestContext().getCurrentXmlTest().getTestParameters().containsKey(SpecialKeywords.EXCEL_DS_CUSTOM_PROVIDER) &&
				result.getParameters().length > 0) //set parameters from XLS only if test contains any parameter at all)
		{
			if (result.getTestContext().getCurrentXmlTest().getTestParameters().containsKey(SpecialKeywords.EXCEL_DS_ARGS))
			{				
				XLSDSBean dsBean = new XLSDSBean(result.getTestContext());
				int index = 0;
				for (String arg : dsBean.getArgs())
				{
					dsBean.getTestParams().put(arg, (String) result.getParameters()[index++]);
				}
				result.getTestContext().getCurrentXmlTest().setParameters(dsBean.getTestParams());

			}
		}

		result.setAttribute(GlobalTestLog.KEY, new GlobalTestLog());
		
		
		// Populate JIRA ID
		String jiraTicket = "";
		if(result.getTestContext().getCurrentXmlTest().getParameter(SpecialKeywords.JIRA_TICKET) != null) {
			jiraTicket = result.getTestContext().getCurrentXmlTest().getParameter(SpecialKeywords.JIRA_TICKET);
		}
		if(result.getMethod().getDescription() != null && result.getMethod().getDescription().contains(SpecialKeywords.JIRA_TICKET)) {
			jiraTicket = result.getMethod().getDescription().split("#")[1]; 
		}

		@SuppressWarnings("unchecked")
		Map<Object[], String> testnameJiraMap = (Map<Object[], String>) result.getTestContext().getAttribute("jiraTicketsMappedToArgs");		
		if (testnameJiraMap != null) {
			String testHash = String.valueOf(Arrays.hashCode(result.getParameters()));					
			if (testnameJiraMap.containsKey(testHash)) {
				jiraTicket = testnameJiraMap.get(testHash);
			}
		}
		
		result.setAttribute(SpecialKeywords.JIRA_TICKET, jiraTicket);
		String test = TestNamingUtil.getCanonicalTestName(result);

		RetryCounter.initCounter(test);

		Messager.TEST_STARTED.info(test, DateUtils.now());
	}

	@Override
	public void onTestSuccess(ITestResult result)
	{
		((GlobalTestLog)result.getAttribute(GlobalTestLog.KEY)).log(Type.COMMON, Messager.TEST_PASSED.info(TestNamingUtil.getCanonicalTestName(result), DateUtils.now()));
		Jira.updateAfterTest(result);
		super.onTestSuccess(result);
	}

	@Override
	public void onTestFailure(ITestResult result)
	{
		String test = TestNamingUtil.getCanonicalTestName(result);
		int count = RetryCounter.getRunCount(test);
		if (count >= MAX_COUNT && result.getThrowable().getMessage() != null)
		{
			String errorMessage = "";
			Throwable thr = (Throwable) result.getTestContext().getAttribute(SpecialKeywords.INITIALIZATION_FAILURE);
			if (thr != null) {
				errorMessage = getFullStackTrace(thr);
			}
			
			GlobalTestLog globalLog = (GlobalTestLog)result.getAttribute(GlobalTestLog.KEY);
			if (globalLog != null) {
				if (result.getThrowable() != null) {
					//errorMessage = result.getThrowable().getMessage();
					errorMessage = getFullStackTrace(result.getThrowable());
				}				
				String msg = Messager.TEST_FAILED.error(test, DateUtils.now(), errorMessage);
				globalLog.log(Type.COMMON, msg);
			}
			else{
				Log.error("GlobalTestLog is NULL! for " + result.toString());
			}
		}
		Jira.updateAfterTest(result);
		super.onTestFailure(result);
	}
	
	@Override
	public void onTestSkipped(ITestResult result)
	{
		String test = TestNamingUtil.getCanonicalTestName(result);
		int count = RetryCounter.getRunCount(test);
		if (count >= MAX_COUNT)
		{
			String errorMessage = "";
			Throwable thr = (Throwable) result.getTestContext().getAttribute(SpecialKeywords.INITIALIZATION_FAILURE);
			if (thr != null) {
				//errorMessage = thr.getMessage();
				errorMessage = getFullStackTrace(thr);
			}

			GlobalTestLog globalLog = (GlobalTestLog)result.getAttribute(GlobalTestLog.KEY);
			if (globalLog != null) {
/*				if (result.getThrowable() != null) {
					//errorMessage = result.getThrowable().getMessage();
					errorMessage = getFullStackTrace(result.getThrowable());
				}*/
				String msg = Messager.TEST_SKIPPED.error(test, DateUtils.now(), errorMessage);
				globalLog.log(Type.COMMON, msg);
			}
			else{
				Log.error("GlobalTestLog is NULL! for " + result.toString());
			}
		}
		super.onTestSkipped(result);
	}
	
	@Override
	public void onFinish(ITestContext testContext)
	{
		removeIncorrectlyFailedTests(testContext);
		super.onFinish(testContext);
	}

	/**
	 * When the test is restarted this method cleans fail statistics in test
	 * context.
	 * 
	 * @param test
	 *            - test context.
	 */
	public static void removeIncorrectlyFailedTests(ITestContext test)
	{
		IResultMap failedTests = test.getFailedTests();
		IResultMap skippedTests = test.getSkippedTests();

		int countOfSkippedResults = test.getSkippedTests().getAllResults().size();
		int countOfPassedResults = test.getPassedTests().getAllResults().size();
		int countOfFailedResults = test.getFailedTests().getAllResults().size();
		int countOfAllResults = test.getAllTestMethods().length;

		List<ITestNGMethod> failsToRemove = new ArrayList<ITestNGMethod>();
		for (ITestResult result : test.getFailedTests().getAllResults())
		{
			long failedResultTime = result.getEndMillis();

			for (ITestResult resultToCheck : test.getSkippedTests().getAllResults())
			{
				if (failedResultTime == resultToCheck.getEndMillis())
				{
					failsToRemove.add(resultToCheck.getMethod());
					break;
				}
			}

			for (ITestResult resultToCheck : test.getPassedTests().getAllResults())
			{
				if (failedResultTime == resultToCheck.getEndMillis())
				{
					failsToRemove.add(resultToCheck.getMethod());
					break;
				}
			}
		}

		for (ITestNGMethod method : failsToRemove)
		{
			failedTests.removeResult(method);
		}

		// remove rerun skipped tests
		if ((countOfAllResults == countOfPassedResults || countOfAllResults == countOfFailedResults) && countOfSkippedResults != 0)
		{
			for (ITestNGMethod method : test.getSkippedTests().getAllMethods())
			{
				skippedTests.removeResult(method);
			}
		}
	}

	protected TestResultItem createTestResult(ITestResult test, TestResultType result, String failReason, String description)
	{
		String group = TestNamingUtil.getPackageName(test);
		String testName = TestNamingUtil.getCanonicalTestName(test);
		String linkToLog = ReportContext.getTestLogLink(testName);
		String linkToScreenshots = null;
		if(!FileUtils.listFiles(ReportContext.getTestDir(testName), new String[]{"png"}, false).isEmpty()
			&& Configuration.getBoolean(Parameter.AUTO_SCREENSHOT)
			&& (TestResultType.FAIL.equals(result) || Configuration.getBoolean(Parameter.KEEP_ALL_SCREENSHOTS)))
		{
			linkToScreenshots = ReportContext.getTestScreenshotsLink(testName);
		}
		TestResultItem testResultItem = new TestResultItem(group, testName, result, linkToScreenshots, linkToLog, failReason);
		testResultItem.setDescription(description);
		testResultItem.setJiraTicket((String)test.getAttribute(SpecialKeywords.JIRA_TICKET));
		return testResultItem;
	}
	
	protected String getFullStackTrace(Throwable thr) {
		String stackTrace = "";
		
	    if (thr != null) {
	    	stackTrace = thr.getMessage() + "\n";
	    	
            StackTraceElement[] elems = thr.getStackTrace();
	        for (StackTraceElement elem : elems) {
	        	stackTrace = stackTrace + "\n" + elem.toString();
            }
	    }
	    return stackTrace;
	}	
}

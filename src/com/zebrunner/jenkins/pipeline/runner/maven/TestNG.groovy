package com.zebrunner.jenkins.pipeline.runner.maven

import com.zebrunner.jenkins.jobdsl.factory.pipeline.CronJobFactory
import com.zebrunner.jenkins.jobdsl.factory.pipeline.TestJobFactory
import com.zebrunner.jenkins.jobdsl.factory.view.ListViewFactory
import com.zebrunner.jenkins.pipeline.Configuration
import com.zebrunner.jenkins.pipeline.integration.zafira.StatusMapper
import com.zebrunner.jenkins.pipeline.integration.zafira.ZafiraUpdater
import com.wangyin.parameter.WHideParameterDefinition
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import javaposse.jobdsl.plugin.actions.GeneratedJobsBuildAction
import jp.ikedam.jenkins.plugins.extensible_choice_parameter.ExtensibleChoiceParameterDefinition
import org.testng.xml.XmlSuite

import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Matcher
import java.util.regex.Pattern

import static com.zebrunner.jenkins.Utils.*
import static com.zebrunner.jenkins.pipeline.Executor.*

// #608 imports
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;


@Grab('org.testng:testng:6.8.8')

public class TestNG extends Runner {

    protected def library = "" // variable to be overrided on custom private pipeline level
    protected def runnerClass = "com.zebrunner.jenkins.pipeline.runner.maven.TestNG"
    protected def onlyUpdated = false
    protected def uuid
    protected ZafiraUpdater zafiraUpdater

    protected qpsInfraCrossBrowserMatrixName = "qps-infra-matrix"
    protected qpsInfraCrossBrowserMatrixValue = "browser: chrome; browser: firefox" // explicit versions removed as we gonna to deliver auto upgrade for browsers

    //CRON related vars
    protected def listPipelines = []
    protected orderedJobExecNum = 0

    protected static final String JOB_TYPE = "job_type"
	protected static final String JENKINS_REGRESSION_MATRIX = "jenkinsRegressionMatrix"
	protected static final String JENKINS_REGRESSION_SCHEDULING = "jenkinsRegressionScheduling"
	
	protected static final String CARINA_SUITES_PATH = "/resources/testng_suites/"

    public TestNG(context) {
        super(context)
        onlyUpdated = Configuration.get("onlyUpdated")?.toBoolean()
        def locale = Configuration.get("locale")
        def language = Configuration.get("language")
        setDisplayNameTemplate('#${BUILD_NUMBER}|${suite}|${branch}|${env}|${browser}|${browserVersion}|${locale}|${language}')
    }

    //Events
    @Override
    public void onPullRequest() {
        context.node("built-in") {
            context.timestamps {
                context.withEnv(getVariables(Configuration.VARIABLES_ENV)) { // read values from variables.env
                    logger.info("TestNG->onPullRequest")
                    
                    def node = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"
                    context.node(node) {
                        getScm().clonePR()
                        compile("-U clean compile test", true)
                    }
                }
            }
        }
    }
    
    @Override
    public void onPush() {
        boolean isValid = false
        
        def nodeMaven = "maven"
        
        context.node("built-in") {
            context.timestamps {
                context.withEnv(getVariables(Configuration.VARIABLES_ENV)) { // read values from variables.env
                    logger.info("TestNG->onPush")
                    
                    nodeMaven = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"

                    try {
                        getScm().clone(true)
                        if (isUpdated(currentBuild,"**.xml,**/zafira.properties") || !onlyUpdated) {
                            scan()
                        }

                        jenkinsFileScan()
                        isValid = true
                    } catch (Exception e) {
                        logger.error("Scan failed.\n" + e.getMessage())
                        this.currentBuild.result = BuildResult.FAILURE
                    }
                }
            }
        }
        
        
        context.node(nodeMaven) {
            context.timestamps {
                context.withEnv(getVariables(Configuration.VARIABLES_ENV)) { // re-read values from variables.env for maven runner
                    if (isValid) {
                        getScm().clonePush()
                        compile("-U clean compile test")
                    }
                    
                    clean()
                }
            }
        }
    }
    
	protected void scan() {

        context.stage("Scan Repository") {
            def buildNumber = Configuration.get(Configuration.Parameter.BUILD_NUMBER)
            def repoFolder = parseFolderName(getWorkspace())
            def branch = Configuration.get("branch")

            setDisplayNameTemplate("#${buildNumber}|${this.repo}|${branch}")
            currentBuild.displayName = getDisplayName()

            def workspace = getWorkspace()
            logger.info("WORKSPACE: ${workspace}")

            for (pomFile in context.getPomFiles()) {
                // Ternary operation to get subproject path. "." means that no subfolder is detected
                def subProject = Paths.get(pomFile).getParent() ? Paths.get(pomFile).getParent().toString() : "."
                logger.debug("subProject: " + subProject)
                def subProjectFilter = subProject.equals(".") ? "**" : subProject
                def zbrProject = getZebrunnerProject(subProjectFilter)
                generateDslObjects(repoFolder, zbrProject, subProject, subProjectFilter, branch)

				factoryRunner.run(dslObjects, Configuration.get("removedConfigFilesAction"),
										Configuration.get("removedJobAction"),
										Configuration.get("removedViewAction"))
            }
        }
    }

    protected String getWorkspace() {
        return context.pwd()
    }

    protected def getSubProjectPomFiles(subDirectory) {
        if (".".equals(subDirectory)){
            subDirectory = ""
        } else {
            subDirectory = subDirectory + "/"
        }
        return context.findFiles(glob: subDirectory + "**/pom.xml")
    }

    def getZebrunnerProject(subProjectFilter){
        def zbrProject = "DEF"
        def zbrProperties = context.findFiles glob: subProjectFilter + "/**/agent.properties"
        zbrProperties.each {
            Map properties  = context.readProperties file: it.path
            if (!isParamEmpty(properties."reporting.project-key")){
                logger.debug("reporting.project-key: " + properties."reporting.project-key")
                zbrProject = properties."reporting.project-key"
            }
        }
        logger.info("Zebrunner Project: " + zbrProject)
        return zbrProject
    }

    def generateDslObjects(repoFolder, zbrProject, subProject, subProjectFilter, branch){
	
        // VIEWS
        registerObject("cron", new ListViewFactory(repoFolder, 'CRON', '.*cron.*'))
        //registerObject(project, new ListViewFactory(jobFolder, project.toUpperCase(), ".*${project}.*"))

        //TODO: create default personalized view here
        def suites = context.findFiles glob: subProjectFilter + "/**/*.xml"
        logger.info("SUITES: " + suites)
        // find all tetsng suite xml files and launch dsl creator scripts (views, folders, jobs etc)
        for (File suite : suites) {
            def suitePath = suite.path
			logger.debug("suitePath: " + suitePath)
			
			//verify if it is testNG suite xml file and continue scan only in this case!
			def currentSuitePath = workspace + "/" + suitePath
			
			if (!isTestNgSuite(currentSuitePath)) {
				logger.debug("Skip from scanner as not a TestNG suite xml file: " + currentSuitePath)
				// not under /src/test/resources or not a TestNG suite file
				continue
			}

            XmlSuite currentSuite = parsePipeline(currentSuitePath)
			def suiteName = ""
			if (currentSuitePath.toLowerCase().contains(CARINA_SUITES_PATH) && currentSuitePath.endsWith(".xml")) {
				// carina core TestNG suite
				int testResourceIndex = currentSuitePath.toLowerCase().lastIndexOf(CARINA_SUITES_PATH)
				logger.debug("testResourceIndex : " + testResourceIndex)
				suiteName = currentSuitePath.substring(testResourceIndex + CARINA_SUITES_PATH.length(), currentSuitePath.length() - 4)
                
                if (suiteName.isEmpty()) {
                    continue
                }
			}
			


            logger.info("suite name: " + suiteName)
            logger.info("suite path: " + suitePath)

            def suiteThreadCount = getSuiteAttribute(currentSuite, "thread-count")
            logger.info("suite thread-count: " + suiteThreadCount)

            def suiteDataProviderThreadCount = getSuiteAttribute(currentSuite, "data-provider-thread-count")
            logger.info("suite data-provider-thread-count: " + suiteDataProviderThreadCount)

            def suiteOwner = getSuiteParameter("anonymous", "suiteOwner", currentSuite)
            if (suiteOwner.contains(",")) {
                // to workaround problem when multiply suiteowners are declared in suite xml file which is unsupported
                suiteOwner = suiteOwner.split(",")[0].trim()
            }

            def currentZbrProject = getSuiteParameter(zbrProject, "reporting.project-key", currentSuite)

            // put standard views factory into the map
            registerObject(currentZbrProject, new ListViewFactory(repoFolder, currentZbrProject.toUpperCase(), ".*${currentZbrProject}.*"))
            registerObject(suiteOwner, new ListViewFactory(repoFolder, suiteOwner, ".*${suiteOwner}"))

            switch(suiteName.toLowerCase()){
                case ~/^.*api.*$/:
                    registerObject("API_VIEW", new ListViewFactory(repoFolder, "API", "", ".*(?i)api.*"))
                    break
                case ~/^.*web.*$/:
                    registerObject("WEB_VIEW", new ListViewFactory(repoFolder, "WEB", "", ".*(?i)web.*"))
                    break
                case ~/^.*android.*$/:
                    registerObject("ANDROID_VIEW", new ListViewFactory(repoFolder, "ANDROID", "", ".*(?i)android.*"))
                    break
                case ~/^.*ios.*$/:
                    registerObject("IOS_VIEW", new ListViewFactory(repoFolder, "IOS", "", ".*(?i)ios.*"))
                    break
            }

            def nameOrgRepoScheduling = (repoFolder.replaceAll('/', '-') + "-scheduling")
            def orgRepoScheduling = true
            if (!isParamEmpty(configuration.getGlobalProperty(nameOrgRepoScheduling)) && configuration.getGlobalProperty(nameOrgRepoScheduling).toBoolean() == false) {
                orgRepoScheduling = false
            }

            //pipeline job
            def jobDesc = "zbr_project: ${currentZbrProject}; owner: ${suiteOwner}"
            branch = getSuiteParameter(Configuration.get("branch"), "jenkinsDefaultGitBranch", currentSuite)
            registerObject(suitePath, new TestJobFactory(repoFolder, getPipelineScript(), this.repoUrl, branch, subProject, currentSuitePath, suiteName, jobDesc, orgRepoScheduling, suiteThreadCount, suiteDataProviderThreadCount))

			//cron job
            if (!isParamEmpty(currentSuite.getParameter("jenkinsRegressionPipeline"))) {
                def cronJobNames = currentSuite.getParameter("jenkinsRegressionPipeline")
                for (def cronJobName : cronJobNames.split(",")) {
                    cronJobName = cronJobName.trim()
					def cronDesc = "type: cron"
					def cronJobFactory = new CronJobFactory(repoFolder, getCronPipelineScript(), cronJobName, this.repoUrl, branch, currentSuitePath, cronDesc, orgRepoScheduling)

					if (!dslObjects.containsKey(cronJobName)) {
						// register CronJobFactory only if its declaration is missed
						registerObject(cronJobName, cronJobFactory)
					} else {
						cronJobFactory = dslObjects.get(cronJobName)
					}

					// try to detect scheduling in current suite
					def scheduling = null
					if (!isParamEmpty(currentSuite.getParameter(JENKINS_REGRESSION_SCHEDULING))) {
						scheduling = currentSuite.getParameter(JENKINS_REGRESSION_SCHEDULING)
					}
					if (!isParamEmpty(currentSuite.getParameter(JENKINS_REGRESSION_SCHEDULING + "_" + cronJobName))) {
						scheduling = currentSuite.getParameter(JENKINS_REGRESSION_SCHEDULING + "_" + cronJobName)
					}

					if (!isParamEmpty(scheduling)) {
						logger.info("Setup scheduling for cron: ${cronJobName} value: ${scheduling}")
						cronJobFactory.setScheduling(scheduling)
					}
                }
            }
        }
    }

	protected def getSuiteAttribute(suite, attribute) {
		def res = "1"

		def file = new File(suite.getFileName())
		def documentBuilderFactory = DocumentBuilderFactory.newInstance()

		documentBuilderFactory.setValidating(false)
		documentBuilderFactory.setNamespaceAware(true)
		try {
			documentBuilderFactory.setFeature("http://xml.org/sax/features/namespaces", false)
			documentBuilderFactory.setFeature("http://xml.org/sax/features/validation", false)
			documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-dtd-grammar", false)
			documentBuilderFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)

			def documentBuilder = documentBuilderFactory.newDocumentBuilder()
			def document = documentBuilder.parse(file)

			for (int i = 0; i < document.getChildNodes().getLength(); i++) {
				def nodeMapAttributes = document.getChildNodes().item(i).getAttributes()
				if (nodeMapAttributes == null) {
					continue
				}

				// get "name" from suite element
				// <suite verbose="1" name="Carina Demo Tests - API Sample" thread-count="3" >
				Node nodeName = nodeMapAttributes.getNamedItem("name")
				if (nodeName == null) {
					continue
				}

				if (suite.getName().equals(nodeName.getNodeValue())) {
					// valid suite node detected
					Node nodeAttribute = nodeMapAttributes.getNamedItem(attribute)
					if (nodeAttribute != null) {
						res = nodeAttribute.getNodeValue()
						break
					}
				}
			}
		} catch (Exception e) {
			logger.error("Unable to get attribute '" + attribute +"' from suite: " + suite.getFileName() + "!")
			logger.error(e.getMessage())
			logger.error(printStackTrace(e))
		}

		return res
	}

	protected boolean isTestNgSuite(filePath){
		logger.debug("filePath: " + filePath)
		XmlSuite currentSuite = null
		boolean res = false
		try {
			currentSuite = parseSuite(filePath)
			res = true
		} catch (FileNotFoundException e) {
			logger.error("Unable to find suite: " + filePath)
			logger.error(printStackTrace(e))
		} catch (Exception e) {
			logger.debug("Unable to parse suite: " + filePath)
			logger.debug(printStackTrace(e))
		}
		return res
	}

    protected XmlSuite parsePipeline(filePath){
        logger.debug("filePath: " + filePath)
        XmlSuite currentSuite = null
        try {
            currentSuite = parseSuite(filePath)
        } catch (FileNotFoundException e) {
            logger.error("Unable to find suite: " + filePath)
            logger.error(printStackTrace(e))
        } catch (Exception e) {
            logger.error("Unable to parse suite: " + filePath)
            logger.error(printStackTrace(e))
        }
        return currentSuite
    }

    protected String getPipelineScript() {
        return "${getPipelineLibrary(this.library)}\nimport ${runnerClass};\nnew ${runnerClass}(this).runJob()"
    }

    protected String getCronPipelineScript() {
        return "${getPipelineLibrary(this.library)}\nimport ${runnerClass};\nnew ${runnerClass}(this).runCron()"
    }

    protected def getObjectValue(obj) {
        def value
        if (obj instanceof ExtensibleChoiceParameterDefinition){
            value = obj.choiceListProvider.getChoiceList()
        } else if (obj instanceof ChoiceParameterDefinition) {
            value = obj.choices
        }  else {
            value = obj.defaultValue
        }
        return value
    }

    protected def getParametersMap(job) {
        def parameterDefinitions = job.getProperty('hudson.model.ParametersDefinitionProperty').parameterDefinitions
        Map parameters = [:]

        // #153 do not provide any data for capabilities launcher to reporting
        /*
        for (parameterDefinition in parameterDefinitions) {
            if (parameterDefinition.name == 'capabilities') {
                def value = getObjectValue(parameterDefinition).split(';')
                for (prm in value) {
                    if (prm.split('=').size() == 2) {
                        parameters.put("capabilities." + prm.split('=')[0], prm.split('=')[1])
                    } else {
                        logger.error("Invalid capability param: ${prm}" )
                    }
                }
            }
        }
        */

        parameterDefinitions.each { parameterDefinition ->
            def value = getObjectValue(parameterDefinition)

            if (!(parameterDefinition instanceof WHideParameterDefinition) || JOB_TYPE.equals(parameterDefinition.name)) {
                if(isJobParameterValid(parameterDefinition.name)){
                    parameters.put(parameterDefinition.name, value)
                }
            }
        }
        logger.info(parameters)
        return parameters
    }

    public void runJob() {
        
        logger.info("TestNG->runJob")
        uuid = getUUID()
        logger.info("UUID: " + uuid)
        def testRun
        String nodeName = "built-in"
        context.node(nodeName) {
            nodeName = chooseNode()
        }
        context.node(nodeName) {
            // set all required integration at the beginning of build operation to use actual value and be able to override anytime later
            setSeleniumUrl()
            
            context.wrap([$class: 'BuildUser']) {
                try {
                    context.timestamps {
                        prepareBuild(currentBuild)
                        getScm().clone()

                        context.timeout(time: Integer.valueOf(Configuration.get(Configuration.Parameter.JOB_MAX_RUN_TIME)), unit: 'MINUTES') {
                            buildJob()
                        }
                    }
                } catch (Exception e) {
                    currentBuild.result = BuildResult.FAILURE // making build failure explicitly in case of any exception in build/notify block
                    logger.error(printStackTrace(e))
                } finally {
                    printDumpReports()
                    
                    //TODO: send notification via email, slack, hipchat and whatever... based on subscription rules
                    
                    testRun = zafiraUpdater.getTestRunByCiRunId(uuid)
                    if(!isParamEmpty(testRun)){
                        
                        // #127 aborted run by timeout don't execute abort call for reporting
                        if (StatusMapper.ZafiraStatus.IN_PROGRESS.name().equals(testRun.status)
                            || StatusMapper.ZafiraStatus.QUEUED.name().equals(testRun.status)) {
                            // if after finish we have intermediate status we have to call abort explicitly and mark build as fail
                            currentBuild.result = BuildResult.FAILURE
                            
                            def abortedTestRun = zafiraUpdater.abortTestRun(uuid, currentBuild)
                        }
                        
                        zafiraUpdater.sendZafiraEmail(uuid, overrideRecipients(Configuration.get("email_list")))
                        zafiraUpdater.exportZafiraReport(uuid, getWorkspace())
                        zafiraUpdater.setBuildResult(uuid, currentBuild)
                    }
                    
                    publishJenkinsReports()
                    sendCustomizedEmail()
                    
                    clean()
                    customNotify()

                    logger.debug("testRun: " + testRun)
                }
            }
        }
    }

    protected String getCurrentFolderFullName(String jobName) {
        String baseJobName = jobName
        def fullJobName = Configuration.get(Configuration.Parameter.JOB_NAME)
        def fullJobNameArray = fullJobName.split("/")
        if (fullJobNameArray.size() == 3) {
            baseJobName = fullJobNameArray[0] + "/" + baseJobName
        }
        return baseJobName
    }

    // to be able to organize custom notifications on private pipeline layer
    protected void customNotify() {
        // do nothing
    }

    // Possible to override in private pipelines
    protected def sendCustomizedEmail() {
        //Do nothing in default implementation
    }

    protected String chooseNode() {
        // reuse overriden node label assignment and return 
        def nodeLabel = Configuration.get("node_label")
        if (!isParamEmpty(nodeLabel)) {
            logger.info("overriding default node to: " + nodeLabel)
            Configuration.set("node", nodeLabel)
            return Configuration.get("node")
        }
        
        def nodeMaven = "maven"
        context.withEnv(getVariables(Configuration.VARIABLES_ENV)) { // read values from variables.env
            nodeMaven = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"
        }
        
        Configuration.set("node", nodeMaven)
        logger.info("node: " + Configuration.get("node"))
        return Configuration.get("node")
    }

    //TODO: moved almost everything into argument to be able to move this methoud outside of the current class later if necessary
    protected void prepareBuild(currentBuild) {
        Configuration.set("BUILD_USER_ID", getBuildUser(currentBuild))

        context.stage('Preparation') {
            currentBuild.displayName = getDisplayName()
            if (isMobile()) {
                //this is mobile test
                prepareForMobile()
            }
        }
    }

    protected void prepareForMobile() {
        logger.info("Runner->prepareForMobile")
        def platform = Configuration.get("job_type").toLowerCase()

        if (platform.contains("android")) {
            prepareForAndroid()
        } else if (platform.equalsIgnoreCase("ios")) {
            prepareForiOS()
        } else {
            logger.warn("Unable to identify mobile platform: ${platform}")
        }

        //general mobile capabilities

        // ATTENTION! Obligatory remove device from the params otherwise
        // hudson.remoting.Channel$CallSiteStackTrace: Remote call to JNLP4-connect connection from qpsinfra_jenkins-slave_1.qpsinfra_default/172.19.0.9:39487
        // Caused: java.io.IOException: remote file operation failed: /opt/jenkins/workspace/Automation/<JOB_NAME> at hudson.remoting.Channel@2834589:JNLP4-connect connection from
        Configuration.remove("device")
        //TODO: move it to the global jenkins variable
        
        // to fix "Session [9ed2aef1-8bd0-4151-a272-e5e869e0991a] was terminated due to TIMEOUT"
        if (isParamEmpty("capabilities.newCommandTimeout")) {
          Configuration.set("capabilities.newCommandTimeout", "120")
        }
        Configuration.set("java.awt.headless", "true")
    }

    protected void prepareForAndroid() {
        logger.info("Runner->prepareForAndroid")
        
        // #249: review pre-defined default caps and parameters and make sure user can override them
        if (isParamEmpty("mobile_app_clear_cache")) {
            Configuration.set("mobile_app_clear_cache", "true")
        }
        if (isParamEmpty("capabilities.autoGrantPermissions")) {
            Configuration.set("capabilities.autoGrantPermissions", "true")
        }
        if (isParamEmpty("capabilities.noSign")) {
            Configuration.set("capabilities.noSign", "true")
        }
        
        /* https://issuetracker.google.com/issues/170867658?pli=1
         * Multiple application reinstalls cause INSTALL_FAILED_INSUFFICIENT_STORAGE exception, which could only be fixed by device reboot 
         */
        
        /* https://github.com/zebrunner/pipeline-ce/issues/185
         * disable cache for android mobile testing by default
         */
        Configuration.set("capabilities.remoteAppsCacheLimit", "0")
        
//        Configuration.set("capabilities.appWaitDuration", "270000")
//        Configuration.set("capabilities.androidInstallTimeout", "270000")
//        Configuration.set("capabilities.adbExecTimeout", "270000")
    }

    protected void prepareForiOS() {
        logger.info("Runner->prepareForiOS")
    }

    protected void buildJob() {
        context.stage('Run Test Suite') {
            context.withEnv(getVariables(Configuration.VARIABLES_ENV)) { // read values from variables.env
                context.withEnv(getVariables(Configuration.AGENT_ENV)) { // read values from agent.env
                    //TODO" completely remove zafiraUpdater if possible to keep integration on project level only!
                    this.zafiraUpdater = new ZafiraUpdater(context)
                    
                    getAdbKeys()
                    
                    def goals = getMavenGoals()
                    def pomFile = getMavenPomFile()
                    context.mavenBuild("-U ${goals} -f ${pomFile}", getMavenSettings())
                }
            }
        }
    }

    protected void setSeleniumUrl() {
        def seleniumUrl = Configuration.get(Configuration.Parameter.SELENIUM_URL)
        logger.debug("Default seleniumUrl: ${seleniumUrl}")
        if (!isParamEmpty(seleniumUrl) && !Configuration.mustOverride.equals(seleniumUrl)) {
            logger.debug("do not override seleniumUrl from creds!")
            return
        }

        // update SELENIUM_URL parameter based on `provider`.
        def hubUrl = getProvider() + "_hub"

        if (!isParamEmpty(getToken(hubUrl))) {
            Configuration.set(Configuration.Parameter.SELENIUM_URL, getToken(hubUrl))
        } else {
            logger.debug("no custom SELENIUM_URL detected. Using default value...")
            Configuration.set(Configuration.Parameter.SELENIUM_URL, "http://selenoid:4444/wd/hub")
        }

        seleniumUrl = Configuration.get(Configuration.Parameter.SELENIUM_URL)
        logger.info("seleniumUrl: ${seleniumUrl}")
    }

    protected void getAdbKeys() {
        try {
            context.configFileProvider(
                [context.configFile(fileId: 'adbkey', targetLocation: '/root/.android/adbkey'),
                context.configFile(fileId: 'adbkey.pub', targetLocation: '/root/.android/adbkey.pub') ]
            ) {
                context.sh 'ls -la /root/.android/'
            }
        } catch (Exception e) {
            // do nothing as files optional
            logger.error(e.getMessage())
        }
    }

    protected String getMavenGoals() {
        //TODO: remove completely zebrunner/zafira goals from maven integration!
        
        // When Zebrunner is disabled use Maven TestNG build status as job status. RetryCount can't be supported correctly!
        def zebrunnerGoals = "-Dmaven.test.failure.ignore=false"
        if ("true".equalsIgnoreCase(context.env.REPORTING_ENABLED)) {
            zebrunnerGoals = "-Dmaven.test.failure.ignore=true \
                            -Dreporting.run.build=${Configuration.get('app_version')} \
                            -Dreporting.run.environment=\"${Configuration.get('env')}\""
        }
        
        def buildUserEmail = Configuration.get("BUILD_USER_EMAIL") ? Configuration.get("BUILD_USER_EMAIL") : ""
        
        //TODO: remove report_url as only it is removed from carina: https://github.com/zebrunner/carina-utils/issues/58
        def defaultBaseMavenGoals = "--no-transfer-progress \
            -Dselenium_url=${Configuration.get(Configuration.Parameter.SELENIUM_URL)} \
            ${zebrunnerGoals} \
            -Dmax_screen_history=1 \
            -Dreport_url=\"${Configuration.get(Configuration.Parameter.JOB_URL)}${Configuration.get(Configuration.Parameter.BUILD_NUMBER)}/ZebrunnerReport\" \
            -Dci_build_url=\"${Configuration.get(Configuration.Parameter.JOB_URL)}${Configuration.get(Configuration.Parameter.BUILD_NUMBER)}\" \
            -Dgit_branch=${Configuration.get("branch")} \
            -Dgit_commit=${Configuration.get("scm_commit")} \
            -Dgit_url=${Configuration.get("scm_url")} \
            -Dci_url=${Configuration.get(Configuration.Parameter.JOB_URL)} \
            -Dci_build=${Configuration.get(Configuration.Parameter.BUILD_NUMBER)} \
            -Dtestrail_enabled=${Configuration.get("testrail_enabled")} \
            -Dinclude_all=${Configuration.get("include_all")} \
            -Dmilestone=${Configuration.get("milestone")} \
            -Drun_name=\"${Configuration.get("run_name")}\" \
            -Dassignee=${Configuration.get("assignee")} \
            clean test"

        addCapability("ci_build_cause", getBuildCause((Configuration.get(Configuration.Parameter.JOB_NAME)), currentBuild))
        addCapability("suite", suiteName)
        addCapabilityIfPresent("rerun_failures", "zafira_rerun_failures")
        // [VD] getting debug host works only on specific nodes which are detecetd by chooseNode.
        // on this stage this method is not fucntion properly!
        //TODO: move 8000 port into the global var
        addOptionalCapability("debug", "Enabling remote debug on ${getDebugHost()}:${getDebugPort()}", "maven.surefire.debug",
                "-Xdebug -Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=8000 -Xnoagent -Djava.compiler=NONE")
        
        addBrowserStackCapabilities()
        addProviderCapabilities()

        def goals = Configuration.resolveVars(defaultBaseMavenGoals)

        goals += addMVNParams(Configuration.getVars())
        goals += addMVNParams(Configuration.getParams())


        goals += getOptionalCapability("deploy_to_local_repo", " install")

        logger.debug("goals: ${goals}")
        return goals
    }
    protected def addMVNParams(params) {
        // This is an array of parameters, that we need to exclude from list of transmitted parameters to maven
        def necessaryMavenParams  = [
                "capabilities",
                "JOB_MAX_RUN_TIME",
                "SELENIUM_URL",
                "job_type",
                "repoUrl",
                "sub_project",
                "BuildPriority",
                "overrideFields",
                "fork"
        ]

        def goals = ''
        for (p in params) {
            if (!(p.getKey() in necessaryMavenParams)) {
                p.getKey()
                goals += " -D${p.getKey()}=\"${p.getValue()}\""
            }
        }
        return goals
    }

    /**
     * Enables capability
     */
    protected def addCapability(capabilityName, capabilityValue) {
        Configuration.set(capabilityName, capabilityValue)
    }

    /**
     * Enables capability if its value is present in configuration and is true
     */
    protected def addOptionalCapability(parameterName, message, capabilityName, capabilityValue) {
        if (Configuration.get(parameterName)?.toBoolean()) {
            logger.info(message)
            Configuration.set(capabilityName, capabilityValue)
        }
    }

    /**
     * Enables capability if its value is present in configuration
     */
    protected def addCapabilityIfPresent(parameterName, capabilityName) {
        def capabilityValue = Configuration.get(parameterName)
        if(!isParamEmpty(capabilityValue))
            addCapability(capabilityName, capabilityValue)
    }

    /**
     * Returns capability value when it is enabled via parameterName in Configuration,
     * the other way returns empty line
     */
    protected def getOptionalCapability(parameterName, capabilityName) {
        return Configuration.get(parameterName)?.toBoolean() ? capabilityName : ""
    }
    
    protected addProviderCapabilities() {
        def provider = getProvider().toLowerCase()
        def platform = Configuration.get("job_type")
        if ("selenium".equalsIgnoreCase(provider) || "zebrunner".equalsIgnoreCase(provider) || "mcloud".equalsIgnoreCase(provider)) {
            // #190: setup default settings only if no explicit disabler via overrideFields!
            if (!"false".equalsIgnoreCase(Configuration.get("capabilities.enableVideo"))) {
                Configuration.set("capabilities.enableVideo", "true")
            }
            if (!"false".equalsIgnoreCase(Configuration.get("capabilities.enableLog"))) {
                Configuration.set("capabilities.enableLog", "true")
            }
        }
    }
    
    protected def addBrowserStackCapabilities() {
        if (isBrowserStackRunning()) {
            def uniqueBrowserInstance = "\"#${Configuration.get(Configuration.Parameter.BUILD_NUMBER)}-" + Configuration.get("suite") + "-" +
                    getBrowser() + "-" + Configuration.get("env") + "\""
            uniqueBrowserInstance = uniqueBrowserInstance.replace("/", "-").replace("#", "")
            startBrowserStackLocal(uniqueBrowserInstance)
            Configuration.set("capabilities.project", this.repo)
            Configuration.set("capabilities.build", uniqueBrowserInstance)
            Configuration.set("capabilities.browserstack.localIdentifier", uniqueBrowserInstance)
            Configuration.set("app_version", "browserStack")
        }
    }

    protected boolean isBrowserStackRunning() {
        def customCapabilities = Configuration.get("custom_capabilities") ? Configuration.get("custom_capabilities") : ""
        return customCapabilities.toLowerCase().contains("browserstack")
    }

    protected void startBrowserStackLocal(String uniqueBrowserInstance) {
        def browserStackUrl = "https://www.browserstack.com/browserstack-local/BrowserStackLocal"
        def accessKey = Configuration.get("BROWSERSTACK_ACCESS_KEY")
        if (context.isUnix()) {
            def browserStackLocation = "/var/tmp/BrowserStackLocal"
            if (!context.fileExists(browserStackLocation)) {
                context.sh "curl -sS " + browserStackUrl + "-linux-x64.zip > " + browserStackLocation + ".zip"
                context.unzip dir: "/var/tmp", glob: "", zipFile: browserStackLocation + ".zip"
                context.sh "chmod +x " + browserStackLocation
            }
            //TODO: [VD] use valid status and stderr object after develping such functionality on pipeline level: https://issues.jenkins-ci.org/browse/JENKINS-44930
            def logFile = "/var/tmp/BrowserStackLocal.log"
            def browserStackLocalStart = browserStackLocation + " --key ${accessKey} --local-identifier ${uniqueBrowserInstance} --force-local > ${logFile} 2>&1 &"
            context.sh(browserStackLocalStart)
            context.sh("sleep 3")
            logger.info("BrowserStack Local proxy statrup output:\n" + context.readFile(logFile).trim())
        } else {
            def browserStackLocation = "C:\\tmp\\BrowserStackLocal"
            if (!context.fileExists(browserStackLocation + ".exe")) {
                context.powershell(returnStdout: true, script: """[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
				Invoke-WebRequest -Uri \'${browserStackUrl}-win32.zip\' -OutFile \'${browserStackLocation}.zip\'""")
                context.unzip dir: "C:\\tmp", glob: "", zipFile: "${browserStackLocation}.zip"
            }
            context.powershell(returnStdout: true, script: "Start-Process -FilePath '${browserStackLocation}.exe' -ArgumentList '--key ${accessKey} --local-identifier ${uniqueBrowserInstance} --force-local'")
        }
    }

    protected def getSuiteName() {
        def suiteName
        if (context.isUnix()) {
            suiteName = Configuration.get("suite").replace("\\", "/")
        } else {
            suiteName = Configuration.get("suite").replace("/", "\\")
        }
        return suiteName
    }

    protected String getMavenPomFile() {
        return getSubProjectFolder() + "/pom.xml"
    }

    //Overriden in private pipeline
    protected def overrideRecipients(emailList) {
        return emailList
    }

    protected void printDumpReports() {
        // print *.dump and *.dumpstream files content into the log. 
        def files = context.findFiles(glob: '**/*.dump*')
        for (int i = 0; i < files.length; i++) {
            //currentBuild.result = BuildResult.FAILURE //explicitly mark build as fail
            logger.warn("Detected dump: " + files[i].path)
            logger.info(context.readFile(file: files[i].path))
            logger.info("") //print empty line
        }
    }
    
    protected void publishJenkinsReports() {
        context.stage('Results') {
            //publishReport('**/reports/qa/emailable-report.html', "CarinaReport")
            publishReport('**/zebrunner/report.html', "ZebrunnerReport")
            publishReport('**/cucumber-html-reports/overview-features.html', "CucumberReport")
            publishReport('**/target/surefire-reports/index.html', 'Full TestNG HTML Report')
            publishReport('**/target/surefire-reports/emailable-report.html', 'TestNG Summary HTML Report')
        }
    }

    protected void publishReport(String pattern, String reportName) {
        try {
            def reports = context.findFiles(glob: pattern)
            def name = reportName
            for (int i = 0; i < reports.length; i++) {
                def parentFile = new File(reports[i].path).getParentFile()
                if (parentFile == null) {
                    logger.error("Parent report is null! for " + reports[i].path)
                    continue
                }
                def reportDir = parentFile.getPath()
                logger.info("Report File Found, Publishing " + reports[i].path)

                if (i > 0) {
                    name = reports[i].name.toString()
                }

                if (name.contains(".mp4")) {
                    // don't publish ".mp4" artifacts
                    continue
                }

                context.publishHTML getReportParameters(reportDir, reports[i].name, name)
            }
        } catch (Exception e) {
            logger.error("Exception occurred while publishing Jenkins report.")
            logger.error(printStackTrace(e))
        }
    }

    public void runCron() {
        logger.info("TestNG->runCron")
        context.node("built-in") {
            getScm().clone()
            listPipelines = []
            def buildNumber = Configuration.get(Configuration.Parameter.BUILD_NUMBER)
            def branch = Configuration.get("branch")

            setDisplayNameTemplate("#${buildNumber}|${this.repo}|${branch}")
            currentBuild.displayName = getDisplayName()

            for(pomFile in context.getPomFiles()){
                // clear list of pipelines for each sub-project
                listPipelines.clear()
                // Ternary operation to get subproject path. "." means that no subfolder is detected
                def subProject = Paths.get(pomFile).getParent()?Paths.get(pomFile).getParent().toString():"."
                def subProjectFilter = subProject.equals(".")?"**":subProject
                generatePipeLineList(subProjectFilter)
                logger.debug("Generated Pipelines Mapping:\n" + listPipelines)
                listPipelines.each { pipeline ->
                    logger.info(pipeline.toString())
                }
                
                logger.info("Finished Pipelines Sorting:")
                listPipelines = sortPipelineList(listPipelines)
                listPipelines.each { pipeline ->
                    logger.info(pipeline.toString())
                }
                executeStages()
            }
        }
    }

    protected def generatePipeLineList(subProjectFilter){
        def files = context.findFiles glob: subProjectFilter + "/**/*.xml"
        logger.info("Number of Test Suites to Scan Through: " + files.length)
        for (file in files){
            def currentSuitePath = workspace + "/" + file.path
            if (!isTestNgSuite(currentSuitePath)) {
                logger.debug("Skip from scanner as not a TestNG suite xml file: " + currentSuitePath)
                // not under /src/test/resources or not a TestNG suite file
                continue
            }

            logger.debug("Current suite path: " + currentSuitePath)
            XmlSuite currentSuite = parsePipeline(currentSuitePath)
            if (currentSuite == null) {
                logger.error("Unable to parse suite: " + currentSuitePath)
                currentBuild.result = BuildResult.FAILURE
                continue
            }
            generatePipeline(currentSuite)
        }
    }

    protected void generatePipeline(XmlSuite currentSuite) {
        if (getBooleanParameterValue("jenkinsJobDisabled", currentSuite)) {
            return
        }

        def jobName = !isParamEmpty(currentSuite.getParameter("jenkinsJobName"))?replaceSpecialSymbols(currentSuite.getParameter("jenkinsJobName")):replaceSpecialSymbols(currentSuite.getName())
        def regressionPipelines = !isParamEmpty(currentSuite.getParameter("jenkinsRegressionPipeline"))?currentSuite.getParameter("jenkinsRegressionPipeline"):""
        def orderNum = getJobExecutionOrderNumber(currentSuite)
        def executionMode = currentSuite.getParameter("jenkinsJobExecutionMode")
        def currentEnvs = Configuration.get("env")
        def emailList = !isParamEmpty(Configuration.get("email_list"))?Configuration.get("email_list"):currentSuite.getParameter("jenkinsEmail")
        def priorityNum = !isParamEmpty(Configuration.get("BuildPriority"))?Configuration.get("BuildPriority"):"5"
        def currentBrowser = !isParamEmpty(getBrowser())?getBrowser():"NULL"
        
        // that's might be optional param
        def currentLocales = Configuration.get("locale")
        
        for (def regressionPipeline : regressionPipelines?.split(",")) {
			regressionPipeline = regressionPipeline.trim()
            if (!Configuration.get(Configuration.Parameter.JOB_BASE_NAME).equals(regressionPipeline)) {
                //launch test only if current regressionPipeline exists among regressionPipelines
                continue
            }
            
            def logLine = "jobName: ${jobName};\n" + 
                    "   regressionPipelines: ${regressionPipelines};\n" +
                    "   jobExecutionOrderNumber: ${orderNum};\n" + 
                    "   email_list: ${emailList};\n" +
                    "   currentBrowser: ${currentBrowser};"
            logger.info(logLine)

            for (def currentEnv : currentEnvs.split(",")) {
                currentEnv = currentEnv.trim()

                // organize children pipeline jobs according to the JENKINS_REGRESSION_MATRIX or execute at once with default params
                def supportedParamsMatrix = ""
                if (!isParamEmpty(currentSuite.getParameter(JENKINS_REGRESSION_MATRIX))) {
                    supportedParamsMatrix = currentSuite.getParameter(JENKINS_REGRESSION_MATRIX)
                    logger.info("Declared ${JENKINS_REGRESSION_MATRIX} detected!")
                }

                if (!isParamEmpty(currentSuite.getParameter(JENKINS_REGRESSION_MATRIX + "_" + regressionPipeline))) {
                    // override default parameters matrix using concrete cron params
                    supportedParamsMatrix = currentSuite.getParameter(JENKINS_REGRESSION_MATRIX + "_" + regressionPipeline)
                    logger.info("Declared ${JENKINS_REGRESSION_MATRIX}_${regressionPipeline} detected!")
                }

                for (def supportedParams : supportedParamsMatrix.split(";")) {
                    if (!isParamEmpty(supportedParams)) {
                        supportedParams = supportedParams.trim()
                        logger.info("supportedParams: ${supportedParams}")
                    }

                    Map supportedConfigurations = getSupportedConfigurations(supportedParams)
                    logger.info("supportedConfigurations: ${supportedConfigurations}")
                    def pipelineMap = [:]
                    // put all not NULL args into the pipelineMap for execution
                    pipelineMap.put("name", regressionPipeline)
                    pipelineMap.put("params_name", supportedParams)
                    pipelineMap.put("branch", Configuration.get("branch"))
                    pipelineMap.put("ci_parent_url", setDefaultIfEmpty("ci_parent_url", Configuration.Parameter.JOB_URL))
                    pipelineMap.put("ci_parent_build", setDefaultIfEmpty("ci_parent_build", Configuration.Parameter.BUILD_NUMBER))
                    putNotNull(pipelineMap, "thread_count", Configuration.get("thread_count"))
                    pipelineMap.put("jobName", jobName)
                    pipelineMap.put("env", currentEnv)
                    pipelineMap.put("order", orderNum)
                    pipelineMap.put("BuildPriority", priorityNum)
                    putNotNullWithSplit(pipelineMap, "email_list", emailList)
                    putNotNullWithSplit(pipelineMap, "executionMode", executionMode)
                    putNotNull(pipelineMap, "overrideFields", Configuration.get("overrideFields"))
                    // supported config matrix should be applied at the end to be able to override default args like retry_count etc
                    putMap(pipelineMap, supportedConfigurations)
                    
                    if (!isParamEmpty(currentLocales)) {
                        for (def currentLocale : currentLocales.split(",")) {
                            def pipelineLocaleMap = pipelineMap.clone() 
                            logger.debug("currentLocale: " + currentLocale)
                            currentLocale = currentLocale.trim()
                            pipelineLocaleMap.put("locale", currentLocale)
                            registerPipeline(currentSuite, pipelineLocaleMap)
                            // print resulting pipelineMap
                            logger.debug("pipelineMap: " + pipelineMap)
                        }
                    } else {
                        registerPipeline(currentSuite, pipelineMap)
                    }
                }
            }
        }
    }

    protected def getOrderNum(suite){
        def orderNum = suite.getParameter("jenkinsJobExecutionOrder").toString()
        if (orderNum.equals("null")) {
            orderNum = "0"
            logger.debug("specify by default '0' order - start asap")
        } else if (orderNum.equals("ordered")) {
            orderedJobExecNum++
            orderNum = orderedJobExecNum.toString()
        }
        return orderNum
    }

    protected def getJobExecutionOrderNumber(suite){
        def orderNum = suite.getParameter("jenkinsJobExecutionOrder")
        if (isParamEmpty(orderNum)) {
            orderNum = 0
            logger.debug("specify by default '0' order - start asap")
        } else if (orderNum.equals("ordered")) {
            orderedJobExecNum++
            orderNum = orderedJobExecNum
        }
        return orderNum.toString()
    }

    // do not remove currentSuite from this method! It is available here to be override on customer level.
    protected def registerPipeline(currentSuite, pipelineMap) {
        logger.debug("registering pipeline: " + pipelineMap)
        listPipelines.add(pipelineMap)
        logger.debug("registered pipelines: " + listPipelines)
    }

    protected getSupportedConfigurations(configDetails){
        def valuesMap = [:]
        // browser: chrome; browser: firefox;
        // browser: chrome, browser_version: 74;
        // os:Windows, os_version:10, browser:chrome, browser_version:72;
        // device:Samsung Galaxy S8, os_version:7.0
        // devicePool:Samsung Galaxy S8, platform: ANDROID, platformVersion: 9, deviceBrowser: chrome
        for (def config : configDetails.split(",")) {
            if (isParamEmpty(config)) {
                logger.warn("Supported config data is NULL!")
                continue
            }
            def nameValueArray = config.split(":");
            def name = nameValueArray[0]?.trim()
            logger.info("name: " + name)
            def value = ""
            if (nameValueArray.size() > 1) {
                // everything after 1st colon is a value
                value = config.minus("${nameValueArray[0]}:")
            }
            logger.info("value: " + value)
            valuesMap[name] = value
        }
        logger.info("valuesMap: " + valuesMap)
        return valuesMap
    }

    // do not remove unused crossBrowserSchema. It is declared for custom private pipelines to override default schemas
    @Deprecated
    protected getCrossBrowserConfigurations(configDetails) {
        return configDetails.replace(qpsInfraCrossBrowserMatrixName, qpsInfraCrossBrowserMatrixValue)
    }

    protected def executeStages() {
        def mappedStages = [:]

        boolean parallelMode = true
        //combine jobs with similar priority into the single parallel stage and after that each stage execute in parallel
        String beginOrder = "0"
        String curOrder = ""
        for (Map jobParams : listPipelines) {
            logger.info("building stage for jobParams: " + jobParams)
            def stageName = getStageName(jobParams)
            boolean propagateJob = true
            if (!isParamEmpty(jobParams.get("executionMode"))) {
                if (jobParams.get("executionMode").contains("continue")) {
                    //do not interrupt pipeline/cron if any child job failed
                    propagateJob = false
                }
                if (jobParams.get("executionMode").contains("abort")) {
                    //interrupt pipeline/cron and return fail status to piepeline if any child job failed
                    propagateJob = true
                }
            }
            curOrder = jobParams.get("order")
            logger.debug("beginOrder: ${beginOrder}; curOrder: ${curOrder}")
            // do not wait results for jobs with default order "0". For all the rest we should wait results between phases
            boolean waitJob = false
            if (curOrder.toInteger() > 0) {
                waitJob = true
            }
            if (curOrder.equals(beginOrder)) {
                logger.debug("colect into order: ${curOrder}; job: ${stageName}")
                mappedStages[stageName] = buildOutStages(jobParams, waitJob, propagateJob)
            } else {
                context.parallel mappedStages
                //reset mappedStages to empty after execution
                mappedStages = [:]
                beginOrder = curOrder
                //add existing pipeline as new one in the current stage
                mappedStages[stageName] = buildOutStages(jobParams, waitJob, propagateJob)
            }
        }
        if (!isParamEmpty(mappedStages)) {
            logger.debug("launch jobs with order: ${curOrder}")
            context.parallel mappedStages
        }

    }

    protected def getStageName(jobParams) {
        // Put into this method all unique pipeline stage params otherwise less jobs then needed are launched!
        def stageName = ""
        String jobName = jobParams.get("jobName")
        String env = jobParams.get("env")
		String paramsName = jobParams.get("params_name")

        String browser = jobParams.get("browser")
        String browser_version = jobParams.get("browser_version")
        String custom_capabilities = jobParams.get("custom_capabilities")
        String locale = jobParams.get("locale")

        if (!isParamEmpty(jobName)) {
            stageName += "Stage: ${jobName} "
        }
		if (!isParamEmpty(env)) {
			stageName += "Environment: ${env} "
		}
		if (!isParamEmpty(paramsName)) {
			stageName += "Params: ${paramsName} "
		}
		// We can't remove lower param for naming even after adding "params_name"
        if (!isParamEmpty(locale)) {
            stageName += "Locale: ${locale} "
        }
        if (!isParamEmpty(browser)) {
            stageName += "Browser: ${browser} "
        }
        if (!isParamEmpty(browser_version)) {
            stageName += "Browser version: ${browser_version} "
        }
        if (!isParamEmpty(custom_capabilities)) {
            stageName += "Custom capabilities: ${custom_capabilities} "
        }
        return stageName
    }

    protected def buildOutStages(Map entry, boolean waitJob, boolean propagateJob) {
        return {
            buildOutStage(entry, waitJob, propagateJob)
        }
    }

    protected def buildOutStage(Map entry, boolean waitJob, boolean propagateJob) {
        context.stage(getStageName(entry)) {
            logger.debug("Dynamic Stage Created For: " + entry.get("jobName"))
            logger.debug("Checking EmailList: " + entry.get("email_list"))

            List jobParams = []

            //add current build params from cron
            for (param in Configuration.getParams()) {
				if ("params_name".equals(param.getKey())) {
					//do not append params_name as it it used only for naming
					continue
				}
                
                if ("env".equalsIgnoreCase(param.getKey())) {
                    //env is parsed in different way so don't add it as dedictaed job param
                    continue
                }
                
                if ("locale".equalsIgnoreCase(param.getKey())) {
                    //locale is parsed in different way so don't add it as dedictaed job param
                    continue
                }

                if (!isParamEmpty(param.getValue())) {
                    if ("false".equalsIgnoreCase(param.getValue().toString()) || "true".equalsIgnoreCase(param.getValue().toString())) {
                        jobParams.add(context.booleanParam(name: param.getKey(), value: param.getValue()))
                    } else {
                        jobParams.add(context.string(name: param.getKey(), value: param.getValue()))
                    }
                }
            }
            for (param in entry) {
                jobParams.add(context.string(name: param.getKey(), value: param.getValue()))
            }
            
            logger.info("jobParams: " + jobParams.dump())

            try {
                context.build job: parseFolderName(getWorkspace()) + "/" + entry.get("jobName"),
                        propagate: propagateJob,
                        parameters: jobParams,
                        wait: waitJob
            } catch (Exception e) {
                logger.error(printStackTrace(e))
                def body = "Unable to start job via cron! " + e.getMessage()
                def subject = "JOBSTART FAILURE: " + entry.get("jobName")
                def to = entry.get("email_list") + "," + Configuration.get("email_list")

                context.emailext getEmailParams(body, subject, to)
            }
        }
    }

    def getSettingsFileProviderContent(fileId){
        context.configFileProvider([context.configFile(fileId: fileId, variable: "MAVEN_SETTINGS")]) {
            context.readFile context.env.MAVEN_SETTINGS
        }
    }

    // Possible to override in private pipelines
    protected def getDebugHost() {
        return context.env["INFRA_HOST"]
    }

    // Possible to override in private pipelines
    protected def getDebugPort() {
        def port = "8000"
        return port
    }
    
    protected def getProvider() {
        if (isParamEmpty(Configuration.get("provider"))) {
            // #177: setup default provider=zebrunner by default
            Configuration.set("provider", "zebrunner")
            Configuration.set("capabilities.provider", "zebrunner")
        } else {
            Configuration.set("capabilities.provider", Configuration.get("provider"))
        }
        
        return Configuration.get("provider")
    }

}

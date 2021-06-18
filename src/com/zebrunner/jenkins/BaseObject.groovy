package com.zebrunner.jenkins

import com.zebrunner.jenkins.Logger
import com.zebrunner.jenkins.pipeline.Configuration
import com.zebrunner.jenkins.pipeline.tools.scm.ISCM

import com.zebrunner.jenkins.pipeline.tools.scm.github.GitHub
import com.zebrunner.jenkins.pipeline.tools.scm.gitlab.Gitlab
import com.zebrunner.jenkins.pipeline.tools.scm.bitbucket.BitBucket

import java.nio.file.Paths

import static com.zebrunner.jenkins.Utils.replaceMultipleSymbolsToOne

/*
 * BaseObject to operate with pipeline context, loggers and runners
 */

public abstract class BaseObject {
    protected def context
    protected Logger logger
    protected FactoryRunner factoryRunner // object to be able to start JobDSL anytime we need
    protected Map dslObjects

    protected ISCM scmClient
    protected def scmUser
    protected def scmToken
    
    // organization folder name of the current job/runner
    protected String organization = ""
    
    protected def repoUrl // git repo url (https or ssh)
    protected def repo

    protected def currentBuild
    protected String displayNameTemplate = '#${BUILD_NUMBER}|${branch}'
    protected final String DISPLAY_NAME_SEPARATOR = "|"
    
    protected String zebrunnerPipeline // pipeline name and version!

    //this is very important line which should be declared only as a class member!
    protected Configuration configuration = new Configuration(context)
    
    protected static final String REPO_URL = "repoUrl"
    private static final String SCM_USER = "scmUser"
    private static final String SCM_TOKEN = "scmToken"

    public BaseObject(context) {
        this.context = context
        this.logger = new Logger(context)
        this.dslObjects = new LinkedHashMap()

        this.factoryRunner = new FactoryRunner(context)
        this.organization = initOrg()
        
        this.scmUser = Configuration.get(SCM_USER)
        this.scmToken = Configuration.get(SCM_TOKEN)        
        this.repoUrl = Configuration.get(REPO_URL)
        this.repo = initRepo(this.repoUrl)
        
        this.zebrunnerPipeline = "Zebrunner-CE@" + Configuration.get(Configuration.Parameter.ZEBRUNNER_VERSION)
        currentBuild = context.currentBuild
        
        // get scmType from build args otherwise default to github
        def String gitType = Configuration.get("scmType") ? Configuration.get("scmType") : "github"
        switch (gitType) {
            case "github":
                this.scmClient = new GitHub(context)
                break
            case "gitlab":
                this.scmClient = new Gitlab(context)
                break
            case "bitbucket":
                this.scmClient = new BitBucket(context)
                break
            default:
                throw new RuntimeException("Unsuported source control management: ${gitType}!")
        }
        
        //hotfix to init valid credentialsId object reference
        def credId = "${this.repo}"
        if (!this.organization.isEmpty()) {
            credId = "${this.organization}-${this.repo}"
        }
        this.scmClient.setCredentialsId(credId)
    }

    protected String getDisplayName() {
        def String displayName = Configuration.resolveVars(this.displayNameTemplate)
        displayName = displayName.replaceAll("(?i)null", '')
        displayName = replaceMultipleSymbolsToOne(displayName, DISPLAY_NAME_SEPARATOR)
        return displayName
    }

    @NonCPS
    protected void setDisplayNameTemplate(String template) {
        this.displayNameTemplate = template
    }

    public def getScm() {
        return this.scmClient
    }
    
    protected void registerObject(name, object) {
        if (dslObjects.containsKey(name)) {
            logger.debug("key ${name} already defined and will be replaced!")
            logger.debug("Old Item: ${dslObjects.get(name).dump()}")
            logger.debug("New Item: ${object.dump()}")
        }
        dslObjects.put(name, object)
    }

    protected void clean() {
        context.stage('Wipe out Workspace') {
            context.deleteDir()
        }
    }
    
    protected String getPipelineLibrary() {
        return getPipelineLibrary("")
    }
    
    protected String getPipelineLibrary(customPipeline) {
        if ("Zebrunner-CE".equals(customPipeline) || customPipeline.isEmpty()) {
            // no custom private pipeline detected!
            return "@Library(\'${zebrunnerPipeline}\')"
        } else {
            return "@Library(\'${zebrunnerPipeline}\')\n@Library(\'${customPipeline}\')"
        }
    }
    
    @NonCPS
    protected def initOrg() {
        String jobName = context.env.getEnvironment().get("JOB_NAME")
        context.println "jobName: ${jobName}"
        def orgFolderName = ""

        int slashIndex = jobName.lastIndexOf('/');
        if (slashIndex == -1) {
            //equals means just root folder, i.e. empty org name
            orgFolderName = ""
        } else if ((jobName.contains("RegisterRepository") || jobName.contains("launcher") || jobName.contains("qtest-updater") || jobName.contains("testrail-updater"))) {
            // cut everything after latest slash:
            // qps/RegisterRepository -> qps
            // test/qps/RegisterRepository -> test/qps
            orgFolderName = jobName.substring(0, slashIndex)
        } else {
            // cut twice everything after latest slash:
            orgFolderName = jobName.substring(0, slashIndex)
            
            slashIndex = orgFolderName.lastIndexOf('/');
            if (slashIndex != -1) {
                orgFolderName = jobName.substring(0, slashIndex)
            }
        }
        
        context.println "orgFolderName: ${orgFolderName}"
        return orgFolderName
    }

    /*       
     *  Find repo name from repository url value (https or ssh)
     * @return repository String
     */
    @NonCPS
    protected def initRepo(url) {
        if (url == null) {
            // use-case for several management jobs like registerOrganization
            return ""
        }
        /*
         * https://github.com/owner/carina-demo.git or git@github.com:owner/carina-demo.git to carina-demo
         * 
         * https://gitlab.com/zebrunner/ce/agent/java-testng.git or git@gitlab.com:zebrunner/ce/agent/java-testng.git to java-testng 
         */
         def items = url.split("/")
         if (items.length < 1) {
             throw new RuntimeException("Unable to parse repository name from '${url}' value!")
         }
         
         def repoName = items[items.length - 1].replace(".git", "")
         
         context.println "repoName: ${repoName}"
         return repoName
    }

}

package com.zebrunner.jenkins.pipeline.runner.python

import com.zebrunner.jenkins.Logger
import com.zebrunner.jenkins.pipeline.runner.AbstractRunner

//[VD] do not remove this important import!
import com.zebrunner.jenkins.pipeline.Configuration

import static com.zebrunner.jenkins.Utils.*

public class PyTest extends AbstractRunner {

    public PyTest(context) {
        super(context)
        
        setDisplayNameTemplate('#${BUILD_NUMBER}|${branch}')
    }

    //Events
    public void onPush() {
        context.node("python") {
            logger.info("PyTest->onPush")
            getScm().clonePush()
            logger.info("to be implemented!")
            
//            // [VD] don't remove -U otherwise latest dependencies are not downloaded
//            compile("-U clean compile test", false)
//
//            //TODO: test if we can execute Jenkinsfile jobdsl on maven node
//            jenkinsFileScan()
        }
    }

    public void onPullRequest() {
        context.node("python") {
            logger.info("PyTest->onPullRequest")
            getScm().clonePR()
            logger.info("to be implemented!")
//            compile("-U clean compile test", true)
        }
    }

    //Methods
    public void build() {
        context.node("python") {
            logger.info("PyTest->build")
            scmClient.clone()
            context.stage("PyTest Build") {
                def goals = Configuration.get("goals")
                if (context.fileExists("requirements.txt")) {
                    context.sh "pip install -r requirements.txt"
                }
                context.sh goals
            }
        }
    }
    
//    protected void compile(goals, isPullRequest=false) {
//        context.stage("Maven Compile") {
//            for (pomFile in context.getPomFiles()) {
//                logger.debug("pomFile: " + pomFile)
//                def sonarGoals = sc.getGoals(isPullRequest)
//                context.mavenBuild("-f ${pomFile} ${goals} ${sonarGoals}", getMavenSettings())
//            }
//        }
//    }
    
}

package com.zebrunner.jenkins.pipeline.runner.maven

import com.zebrunner.jenkins.Logger
import com.zebrunner.jenkins.pipeline.runner.AbstractRunner

//[VD] do not remove this important import!
import com.zebrunner.jenkins.pipeline.Configuration

import static com.zebrunner.jenkins.Utils.*

public class Runner extends AbstractRunner {

    public Runner(context) {
        super(context)
        
        setDisplayNameTemplate('#${BUILD_NUMBER}|${branch}')
    }

    //Events
    public void onPush() {
        def node = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"
        context.node(node) {
            logger.info("Runner->onPush")
            getScm().clonePush()
            // [VD] don't remove -U otherwise latest dependencies are not downloaded
            compile("-U clean compile test", false)
            
            //TODO: test if we can execute Jenkinsfile jobdsl on maven node 
            jenkinsFileScan()
        }
    }

    public void onPullRequest() {
        logger.info("------------> 1")
        def node = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"
        logger.info("------------> 2")
        logger.info(node)
        logger.info("------------> 3")
//        context.node(node) {
//            logger.info("Runner->onPullRequest")
//            getScm().clonePR()
//            compile("-U clean compile test", true)
//        }
    }

    //Methods
    public void build() {
        //TODO: verify if any maven nodes are available
        def node = context.env[Configuration.ZEBRUNNER_NODE_MAVEN] ? context.env[Configuration.ZEBRUNNER_NODE_MAVEN] : "maven"
        context.node(node) {
            logger.info("Runner->build")
            scmClient.clone()
            context.stage("Maven Build") {
                context.mavenBuild(Configuration.get("goals"), getMavenSettings())
            }
        }
    }
    
    protected void compile(goals, isPullRequest=false) {
        context.stage("Maven Compile") {
            for (pomFile in context.getPomFiles()) {
                logger.debug("pomFile: " + pomFile)
                def sonarGoals = sc.getGoals(isPullRequest)
                context.mavenBuild("-f ${pomFile} ${goals} ${sonarGoals}", getMavenSettings())
            }
        }
    }
    
}

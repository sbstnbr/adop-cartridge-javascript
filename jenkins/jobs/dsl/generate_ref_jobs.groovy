// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def nodeReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/joshua-gentess-digital-globe.git"

// Jobs
def codeAnalysisJob = freeStyleJob(projectFolderName + "/codeanalysis-nodeapp")
def buildAppJob = freeStyleJob(projectFolderName + "/build-nodeapp")
def deployCIJob = freeStyleJob(projectFolderName + "/deploy-nodeCIenv")
def deployPRODNodeAJob = freeStyleJob(projectFolderName + "/deploy-PROD-node_A")
def deployPRODNodeBJob = freeStyleJob(projectFolderName + "/deploy-PROD-node_B")
def functionalTestAppJob = freeStyleJob(projectFolderName + "/funtionaltest-nodeapp")
def technicalTestAppJob = freeStyleJob(projectFolderName + "/technicaltest-nodeapp")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/NodejsReferenceApplication")

pipelineView.with {
    title('ADOP Nodeapp Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/build-nodeapp")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

buildAppJob.with {
    description("Build nodejs reference app")
    wrappers {
        preBuildCleanup()
        colorizeOutput(colorMap = 'xterm')
        nodejs('ADOP NodeJS')
    }
    scm {
        git {
            remote {
                url(nodeReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    steps {
        shell('''
      |git config --global url."https://".insteadOf git://
      |echo $PATH
      |npm cache clean
      |rm -rf dist dist.zip
      |grunt build
      |cd dist
      |zip -rq dist.zip *
      |cp dist.zip $WORKSPACE
      |cd $WORKSPACE/api
      |zip -rq api.zip *
      |cp api.zip $WORKSPACE
      '''.stripMargin())
    }
    steps {
        systemGroovyCommand(readFileFromWorkspace("${JENKINS_HOME}/scriptler/scripts/pipeline_params.groovy"))
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    triggers {
        gerrit {
            events {
                refUpdated()
            }
            configure { gerritxml ->
                gerritxml / 'gerritProjects' {
                    'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject' {
                        compareType("PLAIN")
                        pattern(projectFolderName + "/node-questionapp-reference-app")
                        'branches' {
                            'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                                compareType("PLAIN")
                                pattern("master")
                            }
                        }
                    }
                }
                gerritxml / serverName("ADOP Gerrit")
            }
        }
    }
    publishers {
        archiveArtifacts("dist.zip, api.zip")
        downstreamParameterized {
            trigger(projectFolderName + "/codeanalysis-nodeapp") {
                condition("UNSTABLE_OR_BETTER")
            }
        }
    }
}

// Setup Load_Cartridge
codeAnalysisJob.with {
    description("Code quality analysis for nodejs reference application using SonarQube.")
    parameters {
        stringParam("B", '', "Parent build number")
        stringParam("PARENT_BUILD", "build-nodeapp", "Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    wrappers {
        preBuildCleanup()
    }
    scm {
        git {
            remote {
                url(nodeReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    steps {
        shell('''
      |git config --global url."https://".insteadOf git://
      |echo $PATH
      |npm cache clean
      |grunt jshint || exit 0
      |grunt plato || exit 0
      |echo "${JENKINS_URL}view/AOWP_pipeline/job/codeanalysis-nodeapp/HTML_Report/"'''.stripMargin())
    }
    configure { myProject ->
        myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
            project('sonar-project.properties')
            properties('''sonar.projectKey=org.java.reference-application
        sonar.projectName=Reference application
        sonar.projectVersion=1.0.0
        sonar.sources=src
        sonar.language=java
        sonar.sourceEncoding=UTF-8
        sonar.scm.enabled=false''')
            javaOpts()
            jdk('(Inherit From Job)')
            task()
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/deploy-nodeCIenv") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

deployCIJob.with {
    description("Deploy CI Job")
    wrappers {
        preBuildCleanup()
    }
    steps {
        shell('''set +x
      sleep 12
      echo "Deploy to CI environment completed"'''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/funtionaltest-nodeapp") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

functionalTestAppJob.with {
    description("Run functional tests for nodejs reference app")
    wrappers {
        preBuildCleanup()
    }
    scm {
        git {
            remote {
                url(nodeReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    steps {
        shell('''set +x
      git config --global url."https://".insteadOf git://
      echo $PATH
      npm cache clean
      grunt test || exit 0
      '''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/technicaltest-nodeapp") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

technicalTestAppJob.with {
    description("Run technical tests fot nodejs reference app")
    wrappers {
        preBuildCleanup()
    }
    scm {
        git {
            remote {
                url(nodeReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    steps {
        shell('''sed -i "s/http:\\/\\/nodeapp\\..*\\.xip.io/http:\\/\\/nodeapp\\.${STACK_IP}\\.xip.io/g" ${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
    echo "$STACK_IP"'''.stripMargin())
    }
    steps {
        shell('''set +x
      echo "${JENKINS_URL}view/AOWP_pipeline/job/technicaltest-nodeapp/${BUILD_NUMBER}/gatling/report/recordedsimulation/source/"
      '''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/deploy-PROD-node_A") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

deployPRODNodeAJob.with {
    description("Deploy nodejs reference app to Node A")
    wrappers {
        preBuildCleanup()
    }
    steps {
        copyArtifacts('build-nodeapp') {
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''
      git clone ssh://jenkins@gerrit.service.adop.consul:29418/chef_project
      cp $WORKSPACE/chef_project/academy_key.pem $WORKSPACE
      rm -rf chef_project
      chmod 400 academy_key.pem
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp; rm -rf /data/nodeapp/api/*;'"
      scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/api.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/api
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/api; unzip api.zip; rm -rf api.zip'"
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp/dist; rm -rf /data/nodeapp/dist/*;'"
      
      scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/dist.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/dist
      
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/dist; unzip dist.zip; rm -rf dist.zip; docker restart ADOP-NodeApp-1'"
      '''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/deploy-PROD-node_B") {
            }
        }
    }
}

deployPRODNodeBJob.with {
    description("Deploy nodejs reference app to Node B")
    wrappers {
        preBuildCleanup()
    }
    steps {
        copyArtifacts('build-nodeapp') {
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''
      git clone ssh://jenkins@gerrit.service.adop.consul:29418/chef_project
      cp $WORKSPACE/chef_project/academy_key.pem $WORKSPACE
      rm -rf chef_project
      chmod 400 academy_key.pem
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp; rm -rf /data/nodeapp/api/*;'"
      scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/api.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/api
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/api; unzip api.zip; rm -rf api.zip'"
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp/dist; rm -rf /data/nodeapp/dist/*;'"
      scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/dist.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/dist
      ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/dist; unzip dist.zip; rm -rf dist.zip; docker restart ADOP-NodeApp-1'"
      '''.stripMargin())
    }
}
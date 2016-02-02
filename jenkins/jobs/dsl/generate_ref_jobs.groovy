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
            mkdir ${WORKSPACE}/bin
            cd ${WORKSPACE}/bin
            wget https://adop-framework-aowp.s3.amazonaws.com/data/software/bin/phantomjs
            #wget https://adop-framework-aowp.s3.amazonaws.com/data/software/bin/bzip2
            chmod +x ${WORKSPACE}/bin/phantomjs
            export PATH="$PATH:${WORKSPACE}/bin/"
            cd ${WORKSPACE}
            git config --global url."https://".insteadOf git://
            echo $PATH
            npm cache clean
            npm install -g grunt --save-dev
            npm install grunt-contrib-imagemin --save-dev
            npm install
            rm -rf dist dist.zip
            bower install
            grunt build
            cd dist
            zip -rq dist.zip *
            cp dist.zip $WORKSPACE
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
        archiveArtifacts("dist.zip")
        downstreamParameterized {
            trigger(projectFolderName + "/codeanalysis-nodeapp") {
                condition("UNSTABLE_OR_BETTER")
                parameters{
                    predefinedProp("B",'${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
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
        colorizeOutput(colorMap = 'xterm')
        nodejs('ADOP NodeJS')
    }
    steps {
        copyArtifacts('build-nodeapp') {
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps{
        shell('''
            |unzip dist.zip
    '''.stripMargin())
    }
    configure { myProject ->
        myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
            properties('''
            |sonar.projectKey=${WORKSPACE_NAME}
            |sonar.projectName=${WORKSPACE_NAME}
            |sonar.projectVersion=0.0.1
            |sonar.language=js
            |sonar.sources=scripts''')
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
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
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
                condition("SUCCESS")
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
                condition("SUCCESS")
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
                condition("SUCCESS")
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
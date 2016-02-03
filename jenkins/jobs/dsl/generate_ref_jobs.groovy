// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def sonarProjectKey = projectFolderName.toLowerCase().replace("/", "-");

// Variables
def nodeReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/aowp-reference-application.git";
def gatelingReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/gatling-tests.git";

// Jobs
def buildAppJob = freeStyleJob(projectFolderName + "/Build_App")
def codeAnalysisJob = freeStyleJob(projectFolderName + "/Code_Analysis")
def deployToCIEnvJob = freeStyleJob(projectFolderName + "/Deploy_To_CI_ENV")
def deployToProdNode1Job = freeStyleJob(projectFolderName + "/Deploy_To_Prod_Node_1")
def deployToProdNode2Job = freeStyleJob(projectFolderName + "/Deploy_To_Prod_Node_2")
def functionalTestsJob = freeStyleJob(projectFolderName + "/Functional_Tests")
def securityTestsJob = freeStyleJob(projectFolderName + "/Security_Tests")
def performanceTestsJob = freeStyleJob(projectFolderName + "/Performance_Tests")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/NodejsReferenceApplication")

pipelineView.with {
    title('ADOP Nodeapp Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Build_App")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

// Setup Load_Cartridge
buildAppJob.with {
    description("Build nodejs reference app")
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
            branch("*/develop")
        }
    }
    steps {
        shell('''set +x
                |if [ ! -f "${JENKINS_HOME}/tools/docker" ]; then
                |    DOCKER_VERSION=1.6.0
                |    mkdir -p ${JENKINS_HOME}/tools
                |    wget https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION} --quiet -O "${JENKINS_HOME}/tools/docker"
                |    chmod +x "${JENKINS_HOME}/tools/docker"
                |fi
                |
                |project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |${JENKINS_HOME}/tools/docker login -u devops.training -p ztNsaJPyrSyrPdtn -e devops.training@accenture.com docker.accenture.com
                |
                |COUNT=1
                |while ! ${JENKINS_HOME}/tools/docker build -t docker.accenture.com/aowp/${project_name}:${BUILD_NUMBER} .
                |do
                |  if [ ${COUNT} -gt 10 ]; then
                |      echo "Docker build failed even after ${COUNT}. Please investigate."
                |      exit 1
                |    fi
                |    echo "Docker build failed. Retrying ..Attempt (${COUNT})"
                |  COUNT=$((COUNT+1))
                |done
                |
                |COUNT=1
                |while ! ${JENKINS_HOME}/tools/docker push docker.accenture.com/aowp/${project_name}:${BUILD_NUMBER}
                |do
                |  if [ ${COUNT} -gt 10 ]; then
                |      echo "Docker push failed even after ${COUNT}. Please investigate."
                |      exit 1
                |    fi
                |    echo "Docker push failed. Retrying ..Attempt (${COUNT})"
                |  COUNT=$((COUNT+1))
                |done
                '''.stripMargin())
    }
    steps {
        systemGroovyCommand(readFileFromWorkspace("${JENKINS_HOME}/scriptler/scripts/pipeline_params.groovy"))
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
                        pattern(projectFolderName + "/aowp-reference-application")
                        'branches' {
                            'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                                compareType("PLAIN")
                                pattern("develop")
                            }
                        }
                    }
                }
                gerritxml / serverName("ADOP Gerrit")
            }
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Code_Analysis") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B",'${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

codeAnalysisJob.with {
    description("Code quality analysis for nodejs reference application using SonarQube.")
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('SONAR_PROJECT_KEY', sonarProjectKey)
    }
    wrappers {
        preBuildCleanup()
    }
    steps {
        copyArtifacts(projectFolderName + "/Build_App") {
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    configure { myProject ->
        myProject / builders << 'hudson.plugins.sonar.SonarRunnerBuilder'(plugin: "sonar@2.2.1") {
            properties('''|sonar.projectKey=${SONAR_PROJECT_KEY}
            |sonar.projectName=${PROJECT_NAME}
            |sonar.projectVersion=0.0.${B}
            |sonar.language=js
            |sonar.sources=app/scripts
            |sonar.scm.enabled=false
            '''.stripMargin())
            javaOpts()
            jdk('(Inherit From Job)')
            task()
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Deploy_To_CI_ENV") {
                condition("UNSTABLE_OR_BETTER")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
                }
            }
        }
    }
}

deployToCIEnvJob.with {
    description("Deploy CI Environment Job")
    wrappers {
        preBuildCleanup()
    }
    steps {
        shell('''set +x
                |sleep 12
                |echo "Deploy to CI environment completed"
                '''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Functional_Tests") {
                condition("SUCCESS")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

functionalTestsJob.with {
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
                |git config --global url."https://".insteadOf git://
                |echo $PATH
                |npm cache clean
                |npm install -g grunt grunt-cli --save-dev
                |grunt test || exit 0
                '''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Security_Tests") {
                condition("SUCCESS")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

securityTestsJob.with{
    description("Tests nodejs reference app with OWASP ZAP")
    scm {
        git {
            remote {
                url(nodeReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/develop")
        }
    }
    wrappers {
        preBuildCleanup()
    }
    steps {
        shell('''echo "Running automation tests"
                |
                |ref=$( echo ${JOB_NAME} | sed 's#[ /]#_#g' )
                |owasp_zap_container=owasp_zap_nodeapp-${ref}
                |
                |if [ "${JENKINS_HOME}"/tools/docker top "$owasp_zap_container" &> /dev/null ]
                |then
                |    ${JENKINS_HOME}/tools/docker stop $owasp_zap_container
                |    ${JENKINS_HOME}/tools/docker rm $owasp_zap_container
                |fi
                |
                |echo "Starting OWASP ZAP Intercepting Proxy"
                |nohup ${JENKINS_HOME}/tools/docker run -i -v ${WORKSPACE}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ --name ${owasp_zap_container} -P docker.accenture.com/dcsc/owasp_zap_proxy /etc/init.d/zaproxy start test-${BUILD_NUMBER} &
                |
                |echo "Running Selenium tests through maven."
                |sleep 30s
                |
                |# Setting up variables for Maven
                |environment_ip=$(dig +short aowp-ci.node.adop.consul)
                |node_host_id=$(echo "${NODE_NAME}" | cut -d'-' -f1 | rev | cut -d'_' -f1 | rev)
                |
                |VAR_APPLICATION_URL=http://${environment_ip}:80/nodeapp-ci
                |VAR_ZAP_IP=$(dig +short jenkins-${node_host_id}.node.adop.consul)
                |VAR_ZAP_PORT="9090"
                |VAR_ZAP_PORT=$(${JENKINS_HOME}/tools/docker port ${owasp_zap_container} | grep "9090" | sed -rn 's#9090/tcp -> 0.0.0.0:([[:digit:]]+)$#\\1#p')
                |
                |echo "VAR_APPLICATION_URL=${VAR_APPLICATION_URL}" >> maven_variables.properties
                |echo "VAR_ZAP_IP=${VAR_ZAP_IP}" >> maven_variables.properties
                |echo "VAR_ZAP_PORT=${VAR_ZAP_PORT}" >> maven_variables.properties
                '''.stripMargin())
        environmentVariables {
            propertiesFile('maven_variables.properties')
        }
        maven {
              goals("clean")
              goals('install -B -P selenium-tests -DapplicationURL=${VAR_APPLICATION_URL} -DzapIp=${VAR_ZAP_IP} -DzapPort=${VAR_ZAP_PORT}')
              mavenInstallation("ADOP Maven")
        }
        shell('''echo "Stopping OWASP ZAP Proxy and generating report."
                |ref=$( echo ${JOB_NAME} | sed 's#[ /]#_#g' )
                |owasp_zap_container=owasp_zap_nodeapp-${ref}
                |
                |${JENKINS_HOME}/tools/docker stop ${owasp_zap_container}
                |${JENKINS_HOME}/tools/docker rm ${owasp_zap_container}
                |
                |${JENKINS_HOME}/tools/docker run -i -v ${WORKSPACE}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ docker.accenture.com/dcsc/owasp_zap_proxy /etc/init.d/zaproxy stop test-${BUILD_NUMBER}
                |
                |cp ${WORKSPACE}/owasp_zap_proxy/test-results/test-${BUILD_NUMBER}-report.html .
                '''.stripMargin())
    }
    publishers {
        archiveArtifacts("*.html")
    }
    configure{myProject ->
        myProject / 'publishers' / 'htmlpublisher.HtmlPublisher'(plugin:'htmlpublisher@1.4') / 'reportTargets' / 'htmlpublisher.HtmlPublisherTarget' {
            reportName("HTML Report")
            reportDir("nodeapp-a/target/failsafe-reports")
            reportFiles("index.html")
            alwaysLinkToLastBuild("false")
            keepAll("false")
            allowMissing("false")
            wrapperName("htmlpublisher-wrapper.html")
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Performance_Tests") {
                condition("SUCCESS")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

performanceTestsJob.with {
    description("Run technical tests fot nodejs reference app")
    wrappers {
        preBuildCleanup()
    }
    environmentVariables {
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    scm {
        git {
            remote {
                url(gatelingReferenceAppGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    steps {
        shell('''sed -i "s/http:\\/\\/nodeapp\\..*\\.xip.io/http:\\/\\/nodeapp\\.${STACK_IP}\\.xip.io/g" \${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
                |echo "$STACK_IP"
                '''.stripMargin())
    }
    steps {
        shell('''set +x
                |echo "${JENKINS_URL}view/AOWP_pipeline/job/technicaltest-nodeapp/${BUILD_NUMBER}/gatling/report/recordedsimulation/source/"
                '''.stripMargin())
    }
    steps{
        maven {
            goals("gatling:execute")
            mavenInstallation("ADOP Maven")
        }
    }
    configure{ myProject ->
        myProject / publishers << 'io.gatling.jenkins.GatlingPublisher'(plugin: "gatling@1.1.1") {
            enabled("true")
        }
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Deploy_To_Prod_Node_1") {
                condition("SUCCESS")
            }
        }
    }
}

deployToProdNode1Job.with {
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
        shell('''#git clone ssh://jenkins@gerrit.service.adop.consul:29418/chef_project
                |#cp $WORKSPACE/chef_project/academy_key.pem $WORKSPACE
                |#rm -rf chef_project
                |#chmod 400 academy_key.pem
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp; rm -rf /data/nodeapp/api/*;'"
                |#scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/api.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/api
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/api; unzip api.zip; rm -rf api.zip'"
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp/dist; rm -rf /data/nodeapp/dist/*;'"
                |#
                |#scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/dist.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/dist
                |#
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/dist; unzip dist.zip; rm -rf dist.zip; docker restart ADOP-NodeApp-1'"
                '''.stripMargin())
    }
    publishers {
        buildPipelineTrigger(projectFolderName + "/Deploy_To_Prod_Node_2")
    }
}

deployToProdNode2Job.with {
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
        shell('''#git clone ssh://jenkins@gerrit.service.adop.consul:29418/chef_project
                |#cp $WORKSPACE/chef_project/academy_key.pem $WORKSPACE
                |#rm -rf chef_project
                |#chmod 400 academy_key.pem
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp; rm -rf /data/nodeapp/api/*;'"
                |#scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/api.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/api
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/api; unzip api.zip; rm -rf api.zip'"
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'chmod -R 777 /data/nodeapp/dist; rm -rf /data/nodeapp/dist/*;'"
                |#scp -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no \$WORKSPACE/dist.zip ec2-user@aowp1.service.adop.consul:/data/nodeapp/dist
                |#ssh -i academy_key.pem -o UserKnownHostsFile=/dev/null -o StrictHostKeyChecking=no -tt ec2-user@aowp1.service.adop.consul "/usr/bin/sudo bash -c 'cd /data/nodeapp/dist; unzip dist.zip; rm -rf dist.zip; docker restart ADOP-NodeApp-1'"
                '''.stripMargin())
    }
}

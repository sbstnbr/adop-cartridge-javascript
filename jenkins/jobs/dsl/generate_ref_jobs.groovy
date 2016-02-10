// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def sonarProjectKey = projectFolderName.toLowerCase().replace("/", "-");

// Variables
def nodeReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/aowp-reference-application.git";
def gatelingReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/aowp-performance-tests.git";
def zapProxyTestGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/zap-proxy-test.git"

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
    parameters{
        stringParam("GIT_REPOSITORY","aowp-reference-application","Repository name to build the project from.")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    wrappers {
        preBuildCleanup()
    }
    steps {
        conditionalSteps {
            condition {
                shell('test ! -f "${JENKINS_HOME}/tools/docker"')
            }
            runner('Fail')
            steps {
                shell('''set +x
                        |DOCKER_VERSION=1.7.1
                        |mkdir -p ${JENKINS_HOME}/tools
                        |wget https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION} --quiet -O "${JENKINS_HOME}/tools/docker"
                        |chmod +x "${JENKINS_HOME}/tools/docker"
                        |set -x'''.stripMargin())
            }
        }
    }
    steps {
      shell ('''set +x
            |set +e
            |git ls-remote ssh://gerrit.service.adop.consul:29418/${PROJECT_NAME}/${GIT_REPOSITORY} 2> /dev/null
            |ret=$?
            |set -e
            |if [ ${ret} != 0 ]; then
            | echo "Creating gerrit project : ${PROJECT_NAME}/${GIT_REPOSITORY} "
            | ssh -p 29418 gerrit.service.adop.consul gerrit create-project ${PROJECT_NAME}/${GIT_REPOSITORY} --empty-commit
            |else
            | echo "Repository ${PROJECT_NAME}/${GIT_REPOSITORY} exists! Creating jobs..."
            |fi'''.stripMargin())
    }
    steps {
        shell('''set +x
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
        archiveArtifacts("app/scripts/**/*, docker-compose*.yml")
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
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
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
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        copyArtifacts(projectFolderName + "/Build_App") {
            includePatterns('docker-compose*.yml')
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''set +x
                |NAMESPACE=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" | tr '[:upper:]' '[:lower:]' )
                |CI_HOST="${NAMESPACE}-NodeAppCI.node.consul"
                |project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |
                |# Copy the docker-compose configuration file on CI host
                |scp -o StrictHostKeyChecking=no docker-compose.deploy.yml ec2-user@${CI_HOST}:~/docker-compose.yml
                |
                |# Run docker-compose.test.yml on CI host
                |ssh -o StrictHostKeyChecking=no ec2-user@${CI_HOST} "export project_name=${project_name}; export B=${B}; docker login -u devops.training -p ztNsaJPyrSyrPdtn -e devops.training@accenture.com docker.accenture.com; docker-compose up -d --force-recreate"
                |
                |echo "Deploy to CI environment completed"
                |echo "http://${NAMESPACE}-ci.${STACK_IP}.xip.io"
                |
                |set -x'''.stripMargin())
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
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        copyArtifacts(projectFolderName + "/Build_App") {
            includePatterns('docker-compose*.yml')
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''set +x
                |NAMESPACE=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" | tr '[:upper:]' '[:lower:]' )
                |CI_HOST="${NAMESPACE}-NodeAppCI.node.consul"
                |project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |
                |# Copy the docker-compose configuration file on CI host
                |scp -o StrictHostKeyChecking=no docker-compose.test.yml ec2-user@${CI_HOST}:~/docker-compose.test.yml
                |
                |# Run docker-compose.test.yml on CI host
                |ssh -o StrictHostKeyChecking=no ec2-user@${CI_HOST} "export project_name=${project_name}; export B=${B}; docker login -u devops.training -p ztNsaJPyrSyrPdtn -e devops.training@accenture.com docker.accenture.com; docker-compose -f docker-compose.test.yml up --force-recreate"
                |
                |echo "Functional tests completed."
                |set -x'''.stripMargin())
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
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    scm {
        git {
            remote {
                url(zapProxyTestGitUrl)
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    wrappers {
        preBuildCleanup()
        credentialsBinding {
            usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "aws-environment-provisioning")
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    steps {
        conditionalSteps {
            condition {
                shell('test ! -f "${JENKINS_HOME}/tools/docker"')
            }
            runner('Fail')
            steps {
                shell('''set +x
                        |DOCKER_VERSION=1.7.1
                        |mkdir -p ${JENKINS_HOME}/tools
                        |wget https://get.docker.com/builds/Linux/x86_64/docker-${DOCKER_VERSION} --quiet -O "${JENKINS_HOME}/tools/docker"
                        |chmod +x "${JENKINS_HOME}/tools/docker"
                        |set -x'''.stripMargin())
            }
        }
    }
    steps {
        conditionalSteps{
            condition{
                shell('test ! -f "${JENKINS_HOME}/tools/.aws/bin/aws"')
            }
            runner("Fail")
            steps{
                shell('''set +x
                        |wget https://s3.amazonaws.com/aws-cli/awscli-bundle.zip --quiet -O "${JENKINS_HOME}/tools/awscli-bundle.zip"
                        |cd ${JENKINS_HOME}/tools && unzip -q awscli-bundle.zip
                        |${JENKINS_HOME}/tools/awscli-bundle/install -i ${JENKINS_HOME}/tools/.aws
                        |rm -rf ${JENKINS_HOME}/tools/awscli-bundle ${JENKINS_HOME}/tools/awscli-bundle.zip
                        |set -x
                        '''.stripMargin())
            }
        }
    }
    steps{
        conditionalSteps{
            condition{
                shell('test ! -f "${JENKINS_HOME}/tools/jq"')
            }
            runner('Fail')
            steps{
                shell('''wget -q https://s3-eu-west-1.amazonaws.com/adop-core/data-deployment/bin/jq-1.4 -O "${JENKINS_HOME}/tools/jq"
                        |chmod +x "${JENKINS_HOME}/tools/jq"
                        '''.stripMargin())
            }
        }
    }
    steps{
        shell('''echo "Setting values for container, project and app names"
                |CONTAINER_NAME="owasp_zap-"$( echo ${PROJECT_NAME} | sed 's#[ /]#_#g' )${BUILD_NUMBER}
                |PROJECT_NAME_TO_LOWER=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" | tr '[:upper:]' '[:lower:]');
                |APP_NAME=${PROJECT_NAME_TO_LOWER}"-ci"
                |APP_URL=http://${APP_NAME}.${STACK_IP}.xip.io
                |ZAP_IP=$(${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --query 'Stacks[?contains(StackName,`CORE`)].Outputs[*]' | \\
                |   ${JENKINS_HOME}/tools/jq -r '.[0]|.[]| select(.OutputKey=="SonarJenkinsPrivateIP")|.OutputValue');
                |
                |echo CONTAINER_NAME=$CONTAINER_NAME >> app.properties
                |echo PROJECT_NAME_TO_LOWER=$PROJECT_NAME_TO_LOWER >> app.properties
                |echo APP_NAME=$APP_NAME >> app.properties
                |echo APP_URL=$APP_URL >> app.properties
                |echo ZAP_IP=$ZAP_IP >> app.properties
                '''.stripMargin())
        environmentVariables {
            propertiesFile('app.properties')
        }
    }
    steps{
        shell('''echo "Running automation tests"
                |
                |echo "Starting OWASP ZAP Intercepting Proxy"
                |nohup ${JENKINS_HOME}/tools/docker run -i -v ${WORKSPACE}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ \\
                |   --name ${CONTAINER_NAME} -P docker.accenture.com/dcsc/owasp_zap_proxy \\
                |   /etc/init.d/zaproxy start test-${BUILD_NUMBER} &
                |
                |echo "Running Selenium tests through maven."
                |sleep 30s
                |
                |# Setting up variables for Maven
                |ZAP_PORT="9090"
                |ZAP_PORT=$(${JENKINS_HOME}/tools/docker port ${CONTAINER_NAME} | grep "9090" | sed -rn 's#9090/tcp -> 0.0.0.0:([[:digit:]]+)$#\\1#p')
                |echo "ZAP_PORT=${ZAP_PORT}" >> app.properties
                '''.stripMargin())
        environmentVariables {
            propertiesFile('app.properties')
        }
        maven {
              goals("clean")
              goals('install -B -P selenium-tests -DapplicationURL=${APP_URL} -DzapIp=${ZAP_IP} -DzapPort=${ZAP_PORT}')
              mavenInstallation("ADOP Maven")
        }
        shell('''echo "Stopping OWASP ZAP Proxy and generating report."
                |${JENKINS_HOME}/tools/docker stop ${CONTAINER_NAME}
                |${JENKINS_HOME}/tools/docker rm ${CONTAINER_NAME}
                |
                |${JENKINS_HOME}/tools/docker run -i -v ${WORKSPACE}/owasp_zap_proxy/test-results/:/opt/zaproxy/test-results/ \\
                |   --name ${CONTAINER_NAME} -P docker.accenture.com/dcsc/owasp_zap_proxy \\
                |   /etc/init.d/zaproxy stop test-${BUILD_NUMBER}
                |
                |${JENKINS_HOME}/tools/docker cp ${CONTAINER_NAME}:/opt/zaproxy/test-results/test-${BUILD_NUMBER}-report.html .
                |${JENKINS_HOME}/tools/docker stop ${CONTAINER_NAME}
                |${JENKINS_HOME}/tools/docker rm ${CONTAINER_NAME}
                '''.stripMargin())
    }
    publishers {
        archiveArtifacts("*.html")
    }
    configure{myProject ->
        myProject / 'publishers' / 'htmlpublisher.HtmlPublisher'(plugin:'htmlpublisher@1.4') / 'reportTargets' / 'htmlpublisher.HtmlPublisherTarget' {
            reportName("HTML Report")
            reportDir('${WORKSPACE}')
            reportFiles('test-${BUILD_NUMBER}-report.html')
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
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    wrappers {
        preBuildCleanup()
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
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
        shell('''project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |sed -i "s/http:\\/\\/nodeapp\\..*\\.xip.io/http:\\/\\/${project_name}-ci\\.${STACK_IP}\\.xip.io/g" \${WORKSPACE}/src/test/scala/default/RecordedSimulation.scala
                |echo "$STACK_IP"
                '''.stripMargin())
    }
    steps {
        shell('''echo "${JENKINS_URL}view/AOWP_pipeline/job/technicaltest-nodeapp/${BUILD_NUMBER}/gatling/report/recordedsimulation/source/"'''.stripMargin())
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
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }
    }
}

deployToProdNode1Job.with {
    description("Deploy nodejs reference app to Node A")
    parameters{
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        copyArtifacts(projectFolderName + "/Build_App") {
            includePatterns('docker-compose*.yml')
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''set +x
                |NAMESPACE=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" | tr '[:upper:]' '[:lower:]' )
                |AOWP1_HOST="${NAMESPACE}-NodeApp1.node.consul"
                |project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |
                |# Copy the docker-compose configuration file on AOWP1 host
                |scp -o StrictHostKeyChecking=no docker-compose.deploy.yml ec2-user@${AOWP1_HOST}:~/docker-compose.yml
                |
                |# Run docker-compose.yml on PROD1 host
                |ssh -o StrictHostKeyChecking=no ec2-user@${AOWP1_HOST} "export project_name=${project_name}; export B=${B}; docker login -u devops.training -p ztNsaJPyrSyrPdtn -e devops.training@accenture.com docker.accenture.com; docker-compose up -d --force-recreate"
                |
                |echo "Deploy to PROD1 environment completed"
                |echo "http://${NAMESPACE}-1.${STACK_IP}.xip.io"
                |
                |set -x'''.stripMargin())
    }
    publishers {
        downstreamParameterized {
            trigger(projectFolderName + "/Deploy_To_Prod_Node_2") {
                condition("SUCCESS")
                parameters {
                    predefinedProp("B", '${BUILD_NUMBER}')
                    predefinedProp("PARENT_BUILD", '${JOB_NAME}')
                }
            }
        }

    }
}

deployToProdNode2Job.with {
    description("Deploy nodejs reference app to Node B")
    parameters {
        stringParam("B",'',"Parent build number")
        stringParam("PARENT_BUILD",'',"Parent build name")
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        copyArtifacts(projectFolderName + "/Build_App") {
            includePatterns('docker-compose*.yml')
            buildSelector {
                buildNumber('${B}')
            }
        }
    }
    steps {
        shell('''set +x
                |NAMESPACE=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" | tr '[:upper:]' '[:lower:]' )
                |AOWP2_HOST="${NAMESPACE}-NodeApp2.node.consul"
                |project_name=$(echo ${PROJECT_NAME} | tr '[:upper:]' '[:lower:]' | tr '//' '-')
                |
                |# Copy the docker-compose configuration file on AOWP2 host
                |scp -o StrictHostKeyChecking=no docker-compose.deploy.yml ec2-user@${AOWP2_HOST}:~/docker-compose.yml
                |
                |# Run docker-compose.yml on PROD2 host
                |ssh -o StrictHostKeyChecking=no ec2-user@${AOWP2_HOST} "export project_name=${project_name}; export B=${B}; docker login -u devops.training -p ztNsaJPyrSyrPdtn -e devops.training@accenture.com docker.accenture.com; docker-compose up -d --force-recreate"
                |
                |echo "Deploy to PROD2 environment completed"
                |echo "http://${NAMESPACE}-2.${STACK_IP}.xip.io"
                |
                |set -x'''.stripMargin())
    }
}

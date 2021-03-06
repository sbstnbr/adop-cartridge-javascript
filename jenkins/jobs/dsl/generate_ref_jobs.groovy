// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-");

// Jobs reference
def generateNodeReferenceAppJobs = freeStyleJob(projectFolderName + "/Generate_Reference_App_Jobs")

generateNodeReferenceAppJobs.with {
    parameters {
        stringParam("GIT_REPOSITORY_URL", "https://github.com/sbstnbr/askme-backbone", "Git Repository URL to build the project from.")
        stringParam("GIT_REPOSITORY_BRANCH", "master", "Git Repository URL to build the project from.")
        stringParam("DOCKER_REGISTRY_USERNAME", "devops.training", "Docker registry username. If no username is provided, Jenkins jobs will not use authentification when conencting to registry")
        stringParam("DOCKER_REGISTRY_URL", "docker.accenture.com", "Docker registry URL where the built images will be stored")
        stringParam("DOCKER_REGISTRY_EMAIL", "devops.training@accenture.com", "Docker registry e-mail address")
        stringParam("DOCKER_REGISTRY_REPO", "aowp", "Docker registry repository, where you wish to push your images")
        configure { project ->
            project / 'properties' / 'hudson.model.ParametersDefinitionProperty'/ 'parameterDefinitions' << 'hudson.model.PasswordParameterDefinition' {
                name("DOCKER_REGISTRY_PASSWORD")
                description("Docker registry password")
                defaultValue('ztNsaJPyrSyrPdtn')
            }
        }
    }
    environmentVariables {
        env('WORKSPACE_NAME', workspaceFolderName)
        env('PROJECT_NAME', projectFolderName)
        env('PROJECT_NAME_KEY', projectNameKey)
        groovy("matcher = JENKINS_URL =~ /http:\\/\\/(.*?)\\/jenkins.*/; def map = [STACK_IP: matcher[0][1]]; return map;")
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''set +x
                |set +e
                |GIT_REPOSITORY=$(git ls-remote --get-url ${GIT_REPOSITORY_URL} | sed -n 's#.*/\\([^.]*\\)\\.git#\\1#p')
                |git ls-remote ssh://jenkins@gerrit:29418/${PROJECT_NAME}/${GIT_REPOSITORY} 2> /dev/null
                |ret=$?
                |set -e
                |if [ ${ret} != 0 ]; then
                |    echo "Creating gerrit project : ${PROJECT_NAME}/${GIT_REPOSITORY} "
                |    ssh -p 29418 jenkins@gerrit gerrit create-project ${PROJECT_NAME}/${GIT_REPOSITORY} --empty-commit
                |    # Populate repository
                |    git clone ssh://jenkins@gerrit:29418/${PROJECT_NAME}/${GIT_REPOSITORY} .
                |    git remote add source "${GIT_REPOSITORY_URL}"
                |    git fetch source
                |    git push origin +refs/remotes/source/*:refs/heads/*
                |else
                |    echo "Repository ${PROJECT_NAME}/${GIT_REPOSITORY} exists! Creating jobs..."
                |fi
                |
                |echo "GIT_REPOSITORY_NAME=$GIT_REPOSITORY" > env.properties
                |echo "GIT_REPOSITORY_URL=$GIT_REPOSITORY_URL" >> env.properties
                |echo "GIT_REPOSITORY_BRANCH=$GIT_REPOSITORY_BRANCH" >> env.properties
                |
                '''.stripMargin())
        environmentVariables {
            propertiesFile('env.properties')
        }
        dsl {
            text(readFileFromWorkspace('cartridge/jenkins/jobs/dsl/reference_jobs.template'))
        }
    }
}

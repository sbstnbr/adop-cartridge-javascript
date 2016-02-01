// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def javaReferenceAppGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/java-reference-app"

// Jobs
def codeAnalysisJob = freeStyleJob(projectFolderName + "/Code_Analysis")
def buildAppJob = freeStyleJob(projectFolderName + "/Build_App")
def buildDockerJob = freeStyleJob(projectFolderName + "/Build_Docker")
def deployJob = freeStyleJob(projectFolderName + "/Deploy")
def testAutomationJob = freeStyleJob(projectFolderName + "/Test_Automation")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/JavaReferenceApplication")

pipelineView.with{
    title('JavaReferenceApplication Pipeline')
    displayedBuilds(5)
    selectedJob("Code_Analysis")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

// Setup Load_Cartridge
codeAnalysisJob.with{
  description("Code quality analysis for Java reference application using SonarQube.")
  scm{
    git{
      remote{
        url(javaReferenceAppGitUrl)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  triggers{
    gerrit{
      events{
        refUpdated()
      }
      configure { gerritxml ->
        gerritxml / 'gerritProjects' {
          'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject' {
              compareType("PLAIN")
              pattern(projectFolderName + "/java-reference-app")
              'branches' {
                'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                  compareType("PLAIN")
                  pattern("dev")
                }
              }
          }
        }
        gerritxml / serverName("ADOP Gerrit")
      }
    }
  }
  wrappers {
    preBuildCleanup()
  }
}

buildAppJob.with{
  description("Build Java reference app")
  wrappers {
    preBuildCleanup()
  }
}

buildDockerJob.with{
  description("Build Dockerfile")
  wrappers {
    preBuildCleanup()
  }
}

deployJob.with{
  description("Deploy app")
  wrappers {
    preBuildCleanup()
  }
}

testAutomationJob.with{
  description("Test automation")
  wrappers {
    preBuildCleanup()
  }
}

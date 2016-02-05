import jenkins.model.*
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*
import com.cloudbees.jenkins.plugins.sshcredentials.impl.*
import hudson.plugins.sshslaves.*;

// Variables
def nodeAppCI = build.getEnvironment(listener).get('NodeAppCI')
def nodeApp1 = build.getEnvironment(listener).get('NodeApp1')
def nodeApp2 = build.getEnvironment(listener).get('NodeApp2')

// Constants
def instance = Jenkins.getInstance()

// Global Environment Variables
globalNodeProperties = instance.getGlobalNodeProperties()
envVarsNodePropertyList = globalNodeProperties.getAll(hudson.slaves.EnvironmentVariablesNodeProperty.class)

newEnvVarsNodeProperty = null
envVars = null

if ( envVarsNodePropertyList == null || envVarsNodePropertyList.size() == 0 ) {
  newEnvVarsNodeProperty = new hudson.slaves.EnvironmentVariablesNodeProperty();
  globalNodeProperties.add(newEnvVarsNodeProperty)
  envVars = newEnvVarsNodeProperty.getEnvVars()
} else {
  envVars = envVarsNodePropertyList.get(0).getEnvVars()
}

envVars.put("NodeAppCI", nodeAppCI)
envVars.put("NodeApp1", nodeApp1)
envVars.put("NodeApp2", nodeApp2)

// Save the state
instance.save()

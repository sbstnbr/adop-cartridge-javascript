// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def environmentTemplateGitUrl = "ssh://git@uat.alm.accenture.com/ado7/cartridge-aowp-v5.git"

// Jobs
def environmentProvisioningPipelineView = buildPipelineView(projectFolderName + "/Environment_Provisioning")
def createEnvironmentJob = freeStyleJob(projectFolderName + "/Create_Environment")
def destroyEnvironmentJob = freeStyleJob(projectFolderName + "/Destroy_Environment")
def createIrisFrontEndJob = freeStyleJob(projectFolderName + "/Create_Iris_Frontend")

// Pipeline
environmentProvisioningPipelineView.with{
    title('Environment Provisioning Pipeline')
    displayedBuilds(5)
    selectedJob("Create_Environment")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

// Create Environment
createEnvironmentJob.with{
    label("docker")
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    steps {
        shell('''#!/bin/bash -e

# Token constants
TOKEN_NAMESPACE="###TOKEN_NAMESPACE###"
TOKEN_IP="###TOKEN_IP###"
TOKEN_PORT="###TOKEN_PORT###"

# Define required variables
FULL_ENVIRONMENT_NAME=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" )
FULL_ENVIRONMENT_NAME_LOWERCASE=$(echo ${FULL_ENVIRONMENT_NAME} | tr '[:upper:]' '[:lower:]')

node_names_list=(NodeAppCI NodeApp1 NodeApp2)

echo "FULL_ENVIRONMENT_NAME=$FULL_ENVIRONMENT_NAME" > endpoints.txt

# Copy main NGINX config
nginx_main_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}.conf"
cp environment/nginx/nodeapp.conf ${nginx_main_env_conf}

# Copy public NGINX config
nginx_public_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}-public.conf"
cp environment/nginx/nodeapp-public.conf ${nginx_public_env_conf}

# Loop trough the node list starting containers and generating nginx configuration
for node_name in ${node_names_list[@]}; do
  # Define all the variables
  node_name_lowercase=$(echo ${node_name} | tr '[:upper:]' '[:lower:]')
  full_site_name="${FULL_ENVIRONMENT_NAME_LOWERCASE}-${node_name_lowercase}"
  nginx_sites_enabled_file="${full_site_name}.conf"

  SITE_NAME=$(echo ${node_name} | sed "s/NodeApp//g")
  SERVICE_NAME="${FULL_ENVIRONMENT_NAME}-${node_name}"
    
  echo "${node_name}=${full_site_name}" >> endpoints.txt
    
    if [ "${SITE_NAME}" != "CI" ]
    then
      sed -i "s/${TOKEN_NAMESPACE}/${FULL_ENVIRONMENT_NAME}/g" ${nginx_main_env_conf} ${nginx_public_env_conf}
      sed -i "s/###TOKEN_NODEAPP_${SITE_NAME}_IP###/${SERVICE_NAME}/g" ${nginx_main_env_conf} ${nginx_public_env_conf}
      sed -i "s/###TOKEN_NODEAPP_${SITE_NAME}_PORT###/8080/g" ${nginx_main_env_conf} ${nginx_public_env_conf}

      # Upload new config on ADOP-NGINX server
      docker cp ${nginx_main_env_conf} proxy:/etc/nginx/sites-enabled/${nginx_main_env_conf}
      docker cp ${nginx_public_env_conf} proxy:/etc/nginx/sites-enabled/${nginx_public_env_conf}
    fi

    # Run the docker container for the current node
    environment_configs_mask="${FULL_ENVIRONMENT_NAME_LOWERCASE}*"

    # Genrate nginx configuration
    cp environment/nginx/nodeapp-env.conf ${nginx_sites_enabled_file}
    sed -i "s/${TOKEN_NAMESPACE}/${SERVICE_NAME}/g" ${nginx_sites_enabled_file}
    sed -i "s/${TOKEN_IP}/${SERVICE_NAME}/g" ${nginx_sites_enabled_file}
    sed -i "s/${TOKEN_PORT}/8080/g" ${nginx_sites_enabled_file}

    echo "Address for ${node_name}: http://${SERVICE_NAME}.<INSTANCE_IP>.xip.io/"
    # Copy the generated configuration file to nginx container
    docker cp ${nginx_sites_enabled_file} proxy:/etc/nginx/sites-enabled/${nginx_sites_enabled_file}
done

# Reload Nginx configuration
docker exec proxy /usr/sbin/nginx -s reload
''')
        environmentVariables {
            propertiesFile('endpoints.txt')
        }
	systemGroovyCommand(readFileFromWorkspace('cartridge/jenkins/scripts/jenkins_global_envs.groovy'))
    }
    scm {
        git {
            remote {
                name("origin")
                url("${environmentTemplateGitUrl}")
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
    publishers {
        buildPipelineTrigger("${PROJECT_NAME}/Destroy_Environment") {
            parameters {
                currentBuild()
                predefinedProp("FULL_ENVIRONMENT_NAME", '$FULL_ENVIRONMENT_NAME')
            }
        }
    }
}

// Destroy Environment
destroyEnvironmentJob.with{
    label("docker")
    parameters{
        stringParam("FULL_ENVIRONMENT_NAME","","The name of the environment to be created along with its namespace, e.g. Workspace-Project-Name.")
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    steps {
        shell('''#!/bin/bash -e

# Define variables
node_names_list=(NodeAppCI NodeApp1 NodeApp2)

FULL_ENVIRONMENT_NAME_LOWERCASE=$(echo ${FULL_ENVIRONMENT_NAME} | tr '[:upper:]' '[:lower:]')
nginx_main_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}.conf"
nginx_public_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}-public.conf"

# Remove entry from Nginx
echo "Deleting main Nginx configuration"
docker exec -i proxy rm /etc/nginx/sites-enabled/${nginx_main_env_conf}
docker exec -i proxy rm /etc/nginx/sites-enabled/${nginx_public_env_conf}

for node_name in ${node_names_list[@]}; do
    echo "Deleting Nginx configuation and removing Docker container for ${node_name}"
    nginx_sites_enabled_file="${FULL_ENVIRONMENT_NAME_LOWERCASE}-$(echo ${node_name} | tr '[:upper:]' '[:lower:]').conf"
    full_node_name="${FULL_ENVIRONMENT_NAME}-${node_name}"
    docker exec -i proxy rm /etc/nginx/sites-enabled/${nginx_sites_enabled_file}
done

# Reload Nginx configuration
echo "Reloading Nginx configuration"
docker exec proxy /usr/sbin/nginx -s reload
''')
    }
}
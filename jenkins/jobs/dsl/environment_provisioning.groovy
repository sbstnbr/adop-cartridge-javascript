// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def environmentTemplateGitUrl = "ssh://jenkins@gerrit.service.adop.consul:29418/${PROJECT_NAME}/environment_template"

// Jobs
def environmentProvisioningPipelineView = buildPipelineView(projectFolderName + "/Environment_Provisioning")
def createEnvironmentJob = freeStyleJob(projectFolderName + "/Create_Environment")
def destroyEnvironmentJob = freeStyleJob(projectFolderName + "/Destroy_Environment")

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
    parameters{
        stringParam("KEY_NAME","","The name of the key pair to create the environment with")
        stringParam("SUBNET_ID","","The ID of the AWS subnet to create the environment in, e.g. subnet-a123b456")
        stringParam("VPC_ID","","The ID of the AWS VPC to create the environment in, e.g. vpc-a123b456")
        stringParam("DEFAULT_APP_SECURITY_GROUP_ID","","The ID of the AWS default application SG to attach the environment to (used for Consul etc.), e.g. sg-a123b456")
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "aws-environment-provisioning")
        }
    }
    steps {
        conditionalSteps {
            condition {
                shell('test ! -f "${JENKINS_HOME}/tools/.aws/bin/aws"')
            }
            runner('Fail')
            steps {
                shell('''set +x
                        |mkdir -p ${JENKINS_HOME}/tools
                        |wget https://s3.amazonaws.com/aws-cli/awscli-bundle.zip --quiet -O "${JENKINS_HOME}/tools/awscli-bundle.zip"
                        |cd ${JENKINS_HOME}/tools && unzip -q awscli-bundle.zip
                        |${JENKINS_HOME}/tools/awscli-bundle/install -i ${JENKINS_HOME}/tools/.aws
                        |rm -rf ${JENKINS_HOME}/tools/awscli-bundle ${JENKINS_HOME}/tools/awscli-bundle.zip
                        |set -x'''.stripMargin())
            }
        }
        conditionalSteps {
            condition {
                shell('test ! -f "${JENKINS_HOME}/tools/jq"')
            }
            runner('Fail')
            steps {
                shell('''set +x
                        |mkdir -p ${JENKINS_HOME}/tools
                        |# Retrieve JQ to parse AWS responses
                        |wget -q https://s3-eu-west-1.amazonaws.com/adop-core/data-deployment/bin/jq-1.4 -O ${JENKINS_HOME}/tools/jq
                        |chmod +x ${JENKINS_HOME}/tools/jq
                        |set -x'''.stripMargin())
            }
        }
    }
    steps {
        shell('''#!/bin/bash -ex

#HACK NOT ENTIRELY HAPPY ABOUT THIS BEING HARDCODED
#TODO: FIX THIS
export AWS_DEFAULT_REGION="eu-west-1"

# Token constants
TOKEN_NAMESPACE="###TOKEN_NAMESPACE###"
TOKEN_IP="###TOKEN_IP###"
TOKEN_PORT="###TOKEN_PORT###"

# Variables
NAMESPACE=$( echo "${PROJECT_NAME}" | sed "s#[\\/_ ]#-#g" )
FULL_ENVIRONMENT_NAME="${NAMESPACE}"

echo "FULL_ENVIRONMENT_NAME=$FULL_ENVIRONMENT_NAME" > full_environment_name.txt

# Create the stack
environment_stack_name="${VPC_ID}-${FULL_ENVIRONMENT_NAME}"
${JENKINS_HOME}/tools/.aws/bin/aws cloudformation create-stack --stack-name ${environment_stack_name} --tags "Key=createdby,Value=ADOP-Jenkins,Key=createdfor,Value=${NAMESPACE}" --template-body file://aws/environment_template.json \
	--parameters \
    	ParameterKey=Namespace,ParameterValue=${NAMESPACE} \
        ParameterKey=EnvironmentSubnet,ParameterValue=${SUBNET_ID} \
        ParameterKey=KeyName,ParameterValue=${KEY_NAME} \
        ParameterKey=VPCId,ParameterValue=${VPC_ID} \
        ParameterKey=DefaultAppSGID,ParameterValue=${DEFAULT_APP_SECURITY_GROUP_ID}

# Keep looping whilst the stack is being created
SLEEP_TIME=60
COUNT=0
TIME_SPENT=0
while ${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --stack-name ${environment_stack_name} | grep -q "CREATE_IN_PROGRESS" > /dev/null
do
	TIME_SPENT=$(($COUNT * $SLEEP_TIME))
    echo "Attempt ${COUNT} : Stack creation in progress (Time spent : ${TIME_SPENT} seconds)"
    sleep "${SLEEP_TIME}"
    COUNT=$((COUNT+1))
done

# Check that the stack created
TIME_SPENT=$(($COUNT * $SLEEP_TIME))
if $(${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --stack-name ${environment_stack_name} | grep -q "CREATE_COMPLETE")
then
	echo "Stack has been created in approximately ${TIME_SPENT} seconds."
else
	echo "ERROR : Stack creation failed after ${TIME_SPENT} seconds. Please check the AWS console for more information."
    exit 1
fi

# Retrieve details required for Nginx config
aws_response=$(${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --stack-name ${environment_stack_name})
node_names_list=(NodeAppCI NodeApp1 NodeApp2)

FULL_ENVIRONMENT_NAME_LOWERCASE=$(echo ${FULL_ENVIRONMENT_NAME} | tr '[:upper:]' '[:lower:]')

# Copy main NGINX config
nginx_main_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}.conf"
cp nginx/nodeapp.conf ${nginx_main_env_conf}

# Copy public NGINX config
nginx_public_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}-public.conf"
cp nginx/nodeapp-public.conf ${nginx_public_env_conf}

# Loop to handle all web sites configuration
for node_name in ${node_names_list[@]}; do
  OUTPUT_PRIVATE_IP_KEY="${node_name}PrivateIp"
  ENVIRONMENT_IP=$(echo "${aws_response}" | ${JENKINS_HOME}/tools/jq -r ".Stacks[0]|.Outputs[]|select(.OutputKey|contains(\\"${OUTPUT_PRIVATE_IP_KEY}\\"))|.OutputValue")

  SITE_NAME=$(echo ${node_name} | sed "s/NodeApp//g")
  if [ "${SITE_NAME}" != "CI" ]
  then
    sed -i "s/${TOKEN_NAMESPACE}/${FULL_ENVIRONMENT_NAME_LOWERCASE}/g" ${nginx_main_env_conf} ${nginx_public_env_conf}
    sed -i "s/###TOKEN_NODEAPP_${SITE_NAME}_IP###/${ENVIRONMENT_IP}/g" ${nginx_main_env_conf} ${nginx_public_env_conf}
    sed -i "s/###TOKEN_NODEAPP_${SITE_NAME}_PORT###/80/g" ${nginx_main_env_conf} ${nginx_public_env_conf}

  	# Upload new config on ADOP-NGINX server
  	scp -o StrictHostKeyChecking=no ${nginx_main_env_conf} ${nginx_public_env_conf} ec2-user@nginx.service.adop.consul:~
  fi

  FULL_SITE_NAME="${FULL_ENVIRONMENT_NAME_LOWERCASE}-${SITE_NAME}"

  # Generate Nginx configuration, replace values on real site name, ip and port
  nginx_sites_enabled_file="${FULL_ENVIRONMENT_NAME_LOWERCASE}-$(echo ${node_name} | tr '[:upper:]' '[:lower:]').conf"
  cp nginx/nodeapp-env.conf ${nginx_sites_enabled_file}
  sed -i "s/${TOKEN_NAMESPACE}/${FULL_SITE_NAME}/g" ${nginx_sites_enabled_file}
  sed -i "s/${TOKEN_IP}/${ENVIRONMENT_IP}/g" ${nginx_sites_enabled_file}
  sed -i "s/${TOKEN_PORT}/80/g" ${nginx_sites_enabled_file}

  # Upload new config on ADOP-NGINX server
  scp -o StrictHostKeyChecking=no ${nginx_sites_enabled_file} ec2-user@nginx.service.adop.consul:${nginx_sites_enabled_file}
done

# Copy config files to NGINX configuration folder and reload ADOP-NGINX
environment_configs_mask="${FULL_ENVIRONMENT_NAME_LOWERCASE}*"
ssh -o StrictHostKeyChecking=no -t -t -y ec2-user@nginx.service.adop.consul "sudo mv ${environment_configs_mask} /data/nginx/configuration/sites-enabled/; sudo docker exec ADOP-NGINX /usr/sbin/nginx -s reload;"
''')
        environmentVariables {
            propertiesFile('full_environment_name.txt')
        }
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
    parameters{
        stringParam("FULL_ENVIRONMENT_NAME","","The name of the environment to be created along with its namespace, e.g. Workspace-Project-Name.")
        stringParam("VPC_ID","","The ID of the AWS VPC that contains the environment, e.g. vpc-a123b456")
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
        credentialsBinding {
            usernamePassword("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "aws-environment-provisioning")
        }
    }
    steps {
        conditionalSteps {
            condition {
                shell('test ! -f "${JENKINS_HOME}/tools/.aws/bin/aws"')
            }
            runner('Fail')
            steps {
                shell('''set +x
                        |mkdir -p ${JENKINS_HOME}/tools
                        |wget https://s3.amazonaws.com/aws-cli/awscli-bundle.zip --quiet -O "${JENKINS_HOME}/tools/awscli-bundle.zip"
                        |cd ${JENKINS_HOME}/tools && unzip -q awscli-bundle.zip
                        |${JENKINS_HOME}/tools/awscli-bundle/install -i ${JENKINS_HOME}/tools/.aws
                        |export AWS_DEFAULT_REGION="eu-west-1"
                        |rm -rf ${JENKINS_HOME}/tools/awscli-bundle ${JENKINS_HOME}/tools/awscli-bundle.zip
                        |set -x'''.stripMargin())
            }
        }
    }
    steps {
        shell('''#!/bin/bash -e

node_names_list=(NodeAppCI NodeApp1 NodeApp2)

# Unregister Consul
echo "Unregistering consul"
for node_name in ${node_names_list[@]}; do
    consul_instance_name=$(echo ${FULL_ENVIRONMENT_NAME}-${node_name} | tr '[:upper:]' '[:lower:]')
    echo "Removing container for ${consul_instance_name}"
    ssh-keygen -R "${consul_instance_name}.node.consul"
    id=$(ssh -o StrictHostKeyChecking=no -t -t ec2-user@${consul_instance_name}.node.consul "docker ps --format \\"{{.ID}}: {{.Image}}\\" | grep 'progrium/consul' | cut -f1 -d\\":\\"")
    ssh -o StrictHostKeyChecking=no -t -t ec2-user@${consul_instance_name}.node.consul "docker exec -it ${id%?} bash -c \\"consul leave\\" && docker stop ${id%?}"
done

# Delete the stack
environment_stack_name="${VPC_ID}-${FULL_ENVIRONMENT_NAME}"
${JENKINS_HOME}/tools/.aws/bin/aws cloudformation delete-stack --stack-name ${environment_stack_name}

# Keep looping whilst the stack is being deleted
SLEEP_TIME=60
COUNT=0
TIME_SPENT=0
while ${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --stack-name ${environment_stack_name} | grep -q "DELETE_IN_PROGRESS" > /dev/null
do
    TIME_SPENT=$(($COUNT * $SLEEP_TIME))
    echo "Attempt ${COUNT} : Stack deletion in progress (Time spent : ${TIME_SPENT} seconds)"
    sleep "${SLEEP_TIME}"
    COUNT=$((COUNT+1))
done

# Check that the stack deleted
TIME_SPENT=$(($COUNT * $SLEEP_TIME))
if $(${JENKINS_HOME}/tools/.aws/bin/aws cloudformation describe-stacks --stack-name ${environment_stack_name})
then
    echo "ERROR : Stack deletion failed after ${TIME_SPENT} seconds. Please check the AWS console for more information."
    exit 1
else
    echo "Stack has been deleted in approximately ${TIME_SPENT} seconds."
fi

# Remove entry from Nginx
echo "Deleting Nginx configuration"
FULL_ENVIRONMENT_NAME_LOWERCASE=$(echo ${FULL_ENVIRONMENT_NAME} | tr '[:upper:]' '[:lower:]')
nginx_main_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}.conf"
nginx_public_env_conf="${FULL_ENVIRONMENT_NAME_LOWERCASE}-public.conf"

for node_name in ${node_names_list[@]}; do
    nginx_sites_enabled_file="${FULL_ENVIRONMENT_NAME_LOWERCASE}-$(echo ${node_name} | tr '[:upper:]' '[:lower:]').conf"
    ssh -o StrictHostKeyChecking=no -t -t -y ec2-user@nginx.service.adop.consul "sudo rm /data/nginx/configuration/sites-enabled/${nginx_sites_enabled_file}"
done

ssh -o StrictHostKeyChecking=no -t -t -y ec2-user@nginx.service.adop.consul "\
sudo rm /data/nginx/configuration/sites-enabled/${nginx_main_env_conf} \
&& sudo rm /data/nginx/configuration/sites-enabled/${nginx_public_env_conf}; \
sudo docker exec ADOP-NGINX /usr/sbin/nginx -s reload;"
''')
    }
}

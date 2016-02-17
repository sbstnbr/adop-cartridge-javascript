// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def environmentTemplateGitUrl = "ssh://jenkins@gerrit:29418/cartridges/adop-cartridge-aowp.git"
def projectNameKey = projectFolderName.toLowerCase().replace("/", "-");
def fullEnvironmentName = projectFolderName.replace("/", "-");

// Jobs
def destroyEnvironmentJob = freeStyleJob(projectFolderName + "/Destroy_Environment")

// Destroy Environment
destroyEnvironmentJob.with{
    label("docker")
    parameters{
        stringParam("FULL_ENVIRONMENT_NAME","","The name of the environment to be destroyed along with its namespace, e.g. Workspace-Project-Name.")
        stringParam("NODE_CI","CI","Name of CI node")
        stringParam("NODE_PROD1","PROD1","Name of PROD1 node")
        stringParam("NODE_PROD2","PROD2","Name of PROD2 node")
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
        env('PROJECT_NAME_KEY',projectNameKey)
    }
    steps {
        shell('''#!/bin/bash -e

# Define variables
node_names_list=($NODE_CI $NODE_PROD1 $NODE_PROD2)

nginx_main_env_conf="${PROJECT_NAME_KEY}.conf"
nginx_public_env_conf="${PROJECT_NAME_KEY}-public.conf"

# Remove entry from Nginx
echo "Deleting main Nginx configuration"
docker exec -i proxy bash -c "if test -f /etc/nginx/sites-enabled/${nginx_main_env_conf}; then rm /etc/nginx/sites-enabled/${nginx_main_env_conf}; fi"
docker exec -i proxy bash -c "if test -f /etc/nginx/sites-enabled/${nginx_public_env_conf}; then rm /etc/nginx/sites-enabled/${nginx_public_env_conf}; fi"

for node_name in ${node_names_list[@]}; do
    SITE_NAME="${PROJECT_NAME_KEY}-${node_name}"

    echo "Deleting Nginx configuation and removing Docker container for ${node_name}"
    nginx_sites_enabled_file="${SITE_NAME}.conf"
    docker exec -i proxy bash -c "if test -f /etc/nginx/sites-enabled/${nginx_sites_enabled_file}; then rm /etc/nginx/sites-enabled/${nginx_sites_enabled_file}; fi"

    container_id=$(docker ps --format "{{.ID}}: {{.Names}}" | grep ${SITE_NAME} | cut -f1 -d":")

    if [ ! -z "$container_id" ]; then
        docker stop ${container_id%?}
        docker rm -f ${container_id%?}
    fi
done

# Reload Nginx configuration
echo "Reloading Nginx configuration"
docker exec proxy /usr/sbin/nginx -s reload
''')
    }
}

#!/usr/bin/env groovy

def checkKeyInMap(Map map, String key) {
    if (!map.containsKey(key)) {
        throw new RuntimeException("'${key}' parameter is not defined when calling the KeycloakBackupAndUploadToS3 fn.")
    }
    return map[key]
}

def call(Map options){
    // check if the required options are defined
    def kubeconfig_credentials_id = checkKeyInMap(options, "kubeconfig_credentials_id") 
    env."CLUSTER_NAME" = checkKeyInMap(options, "cluster_name")   
    env."S3_BUCKET_NAME" = checkKeyInMap(options, "s3_bucket_name")    
    env."LABEL_APP_KUBERNETES_IO_INSTANCE" = checkKeyInMap(options, "label_app_kubernetes_io_instance") 
    env."KC_NAMESPACE" = checkKeyInMap(options, "keycloak-namespace") 
    def aws_access_key_id_credential_id = checkKeyInMap(options, "aws_access_key_id_credential_id") 
    def aws_secret_access_key_credential_id = checkKeyInMap(options, "aws_secret_access_key_credential_id") 

    if (options.containsKey('aws_endpoint_url_s3') && !options.aws_endpoint_url_s3.isEmpty()) {
        env."AWS_ENDPOINT_URL_S3" = options.aws_endpoint_url_s3
    }

    stage('Prepare Keycloak Env Vars') {
        // Find the currently used keycloak image:tag
        // And use it as Jenkins Agent Docker Image in the next stages
        // Ensuring that the same image is used for backup -- no version hassle
        script { 
            withCredentials([file(credentialsId: "${kubeconfig_credentials_id}", variable: 'JKUBECONF')]) {
                echo "Querying the Keycloak image from the cluster..."
                def keycloak_image = sh(returnStdout: true, script: """
                    #!/bin/bash -l
                    # find the container named keycloak in the statefulset -> get image
                    kubectl --kubeconfig "\$JKUBECONF" -n "\$KC_NAMESPACE" \
                        get statefulset -l "app.kubernetes.io/instance=\$LABEL_APP_KUBERNETES_IO_INSTANCE" -o json \
                            | jq -r '.items[0] | .spec.template.spec.containers[] | select(.name == "keycloak") | .image'
                """).trim()
                if (keycloak_image == null || keycloak_image.isEmpty()) {
                    error "Failed to retrieve Keycloak image from statefulset. Do you have enough permissions with this pipelines Service Account Kubeconfig?"
                }
                echo "Found Keycloak Image: ${keycloak_image} -- the same image will be used for backup."
                // Save to an env variable to be used in the next stage
                env."KEYCLOAK_IMAGE" = keycloak_image
            }
        }

        script {  
            withCredentials([file(credentialsId: "${kubeconfig_credentials_id}", variable: 'JKUBECONF')]) {
                // read the config file from the running keycloak container
                def _keycloak_container_config_file = sh(returnStdout: true, script: """
                    #!/bin/bash -l

                    # get the first pod name
                    POD_NAME=\$(kubectl --kubeconfig "\$JKUBECONF" -n "\$KC_NAMESPACE" \
                        get pod -l "app.kubernetes.io/instance=\$LABEL_APP_KUBERNETES_IO_INSTANCE" -o json \
                            | jq -r '.items[0] | .metadata.name') 
                
                    # output the keycloak config file
                    kubectl --kubeconfig "\$JKUBECONF" -n "\$KC_NAMESPACE" \
                        exec --stdin "\$POD_NAME" -- \
                            bash -c 'cat /opt/bitnami/keycloak/conf/keycloak.conf'
                """).trim()
                if (_keycloak_container_config_file == null || _keycloak_container_config_file.isEmpty()) {
                    error "CONFIG_FILE_FOR_KEYCLOAK is empty, failed to read it from running keycloak container. Aborting."
                }
                env."CONFIG_FILE_FOR_KEYCLOAK" = _keycloak_container_config_file
                echo "Successfully read the keycloak.conf file from the running container."
            }
        }
    }
    // TODO: ersin
    docker.image("${env.KEYCLOAK_IMAGE}").inside('--entrypoint "" -u root --privileged') {
        stage('Keycloak Backup with Container') {
            script {
                withCredentials([
                    string(credentialsId: aws_access_key_id_credential_id, variable: 'AWS_ACCESS_KEY_ID'),
                    string(credentialsId: aws_secret_access_key_credential_id, variable: 'AWS_SECRET_ACCESS_KEY')
                ]) {
                    sh '''
                        #!/bin/bash
                        echo "----------------- START of Keycloak Backup Script (in container) ----------------------"
                        export _KEYCLOAK_HOME="/opt/bitnami/keycloak"
                        export _KEYCLOAK_BACKUP_DIR="keycloak-auto-backups"
                        export _KEYCLOAK_BACKUP_ZIP_FILENAME="keycloak-auto-backups.tar"


                        {
                            echo "Copying the keycloak.conf file..."
                            set +x;  # disable printing commands

                            # dont print the config file to the console
                            echo "\$CONFIG_FILE_FOR_KEYCLOAK" > /opt/bitnami/keycloak/conf/keycloak.conf 
                            
                            set -x;  # enable printing commands
                            
                            # Manually configure the cache to be local
                            sed -i '/^cache-config-file/d' /opt/bitnami/keycloak/conf/keycloak.conf
                            sed -i '/^cache-stack/d' /opt/bitnami/keycloak/conf/keycloak.conf
                            sed -i '/^cache =/s/.*/cache = local/' /opt/bitnami/keycloak/conf/keycloak.conf
                        }

                        # \$_KEYCLOAK_HOME/bin/kc.sh show-config

                        echo "Running keycloak export command..."
                        \$_KEYCLOAK_HOME/bin/kc.sh export \
                            --users=different_files \
                            --dir=\$_KEYCLOAK_BACKUP_DIR \
                            --users-per-file=200 
                        
                        exportStatus=$?
                        if [ $exportStatus -eq 0 ]; then
                            echo "Keycloak Export finished successfully"
                        else
                            echo "Keycloak Export failed with status code: $exportStatus. Aborting..."
                            exit 1
                        fi

                        echo "Listing backup dir: \$_KEYCLOAK_BACKUP_DIR"
                        ls -alh \$_KEYCLOAK_BACKUP_DIR
                        echo "Zipping backup directory..." 
                        {
                            # using a block as we change the working directory
                            cd \$_KEYCLOAK_BACKUP_DIR || { echo "Can't cd into \$_KEYCLOAK_BACKUP_DIR. Exiting."; exit 1; };
                            pwd
                            ls -alh
                            tar -czvf ../\$_KEYCLOAK_BACKUP_ZIP_FILENAME . || { echo "Can't tar archive \$_KEYCLOAK_BACKUP_DIR. Exiting."; exit 1; };
                            cd ..
                        }
                        
                        echo "Succesfully zipped backup directory to \$_KEYCLOAK_BACKUP_ZIP_FILENAME"

                        {
                            if ! command -v aws &> /dev/null
                            then
                                echo "AWS CLI not found. Installing..."
                                apt update -y  > /dev/null 2>&1
                                apt install -y unzip > /dev/null 2>&1
                                curl --silent "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
                                unzip -q awscliv2.zip
                                ./aws/install > /dev/null 2>&1
                                rm -rf awscliv2.zip aws
                                echo "AWS CLI installed successfully."
                            else
                                echo "AWS CLI is already installed."
                            fi
                        }

                        echo "AWS CLI is configured to upload the endpoint: \${AWS_ENDPOINT_URL_S3:-'https://s3.amazonaws.com'}"
                        ls -alh keycloak-auto-backups.tar

                        echo "Trying to upload to S3 at folder: s3://\$S3_BUCKET_NAME/\$CLUSTER_NAME/\$KC_NAMESPACE/"
                        aws s3 cp keycloak-auto-backups.tar s3://\$S3_BUCKET_NAME/\$CLUSTER_NAME/\$KC_NAMESPACE/keycloak-backup--\$LABEL_APP_KUBERNETES_IO_INSTANCE-$(date +%Y-%m-%d--%H-%M).tar

                        echo "S3 Upload is complete, heres the current content of s3://\$S3_BUCKET_NAME/\$CLUSTER_NAME/\$KC_NAMESPACE/ :"
                        aws s3 ls s3://\$S3_BUCKET_NAME/\$CLUSTER_NAME/\$KC_NAMESPACE/

                        echo "Backup directory zipped to \$_KEYCLOAK_BACKUP_ZIP_FILENAME"
                        echo "Backup completed successfully. Exiting."
                        echo "----------------- END of Keycloak Backup Script (in container) ----------------------"
                    '''
                }
            }
        }
    } 
}
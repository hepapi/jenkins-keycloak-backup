

## Overview
- Jenkinsfile.template
    - calls the shared function: KeycloakBackupAndUploadToS3



## TO-DO
- [ ] Install Keycloak bitnami on a Kubernetes cluster
    - https://artifacthub.io/packages/helm/bitnami/keycloak
- [ ] Create a shared library
    - create vars/KeycloakBackupAndUploadToS3.groovy
- [ ] Jenkins settings -> define the shared library
- [ ] create aws s3 bucket + s3 user + access credentials
- [ ] Jenkins settings -> define the AWS credentials
- [ ] 


Keycloak 
    - postgresql database
    - backup
        - pod'un içinde kc.sh backup
        - container run:: keycloak db bilgilerini -> 

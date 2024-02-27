# Jenkins Job for Keycloak Backups

This Jenkins job can be used to backup a **Bitnami distribution of Keycloak** instance **running in a Kubernetes cluster**.

Capable of uploading the backup to any S3 compatible storage.

### Features

- 🔒 Creates a separate Keycloak container and connects to same DB to create a backup
  - So that the keycloak instance is unaffected
- 🔍 Finds the Keycloak `image:tag` from the running pods and uses the same image for the backup
  - _Backups should be made with the same version of Keycloak to avoid compatibility issues_
  - You can use this pipeline for any version of Keycloak
- 💾 Accepts any S3 compatible storage endpoint
- 🚀 Backups container runs on the Kubernetes cluster, not on the Jenkins server
  - Connects to the DB from the same Kubernetes Node, so no need to expose the DB

### Index

**Documentation**

- [Implementation Steps](./docs/implementation-steps.md)
- [How to create service account and kubeconfig](./docs/how-to-create-sa-and-kubeconfig.md)

**Files**

- [Templated Jenkinsfile](./jenkins-job/Jenkinsfile.template)
- Shared Lib function: [KeycloakBackupAndUploadToS3.groovy](./jenkins-shared-library/vars/KeycloakBackupAndUploadToS3.groovy)



## Next Step

Follow the [Implementation Steps](./docs/implementation-steps.md) page to implement the Keycloak Backups.

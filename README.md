# jenkins-keycloak-backup
A jenkins job to backup running keycloak container in a K8s cluster


# Create `kubeconfig` for Keycloak Backup Service Accounts

This document outlines the following operations:

- 1. Create ClusterRole, ClusterRoleBinding, ServiceAccount, and a Secret for the ServiceAccount Token
- 2. Using the ServiceAccount Token, create a `kubeconfig` file.
- 3. Upload `kubeconfig` file to Jenkins Credentials

## Create Service Account, Role and RoleBinding for Keycloak Backup cluster access

### Export your variables

- `KUBEAPI_SERVER`: Kubernetes API Server URL

  - **This value should be one of the control plane node IPs.**
  - The `kubeconfig` you'll get from Rancher doesn't work with `kubectl exec` command.
  - (reason: Rancher's ELB is Layer 7 but `kubectl exec` needs Layer 4 for webhooks.)
  - Because of that, you need to use one of the control plane node IPs.


```bash
# 'https://<control-plane-node-ip>:6443' formatında olmalı
export KUBEAPI_SERVER="https://X.Y.Z.T:6443"  
# keycloak hangi namespace'de ise

# you can leave below variables as is
export SA_NAME="keycloak-backup-sa"
export SA_TOKEN_SECRET_NAME="keycloak-backup-sa-token"

# confirm your variables
echo "SERVICE ACCOUNT NAME: $SA_NAME" 
echo "SA TOKEN SECRET NAME: $SA_TOKEN_SECRET_NAME, KUBEAPI SERVER: $KUBEAPI_SERVER"
```

### Create K8s Objects

```bash
cat <<EOF | kubectl apply -f -
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: keycloak-backup-cluster-role
rules:
- apiGroups: [""]
  resources: ["pods", "pods/log", "secrets", "configmaps"]
  verbs: ["get", "list"]
- apiGroups: ["apps"]
  resources: ["statefulsets", "deployments"]
  verbs: ["get", "list"]
- apiGroups: [""]
  resources: ["pods/exec"]
  verbs: ["create"]
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${SA_NAME}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: keycloak-backup-crb
subjects:
- kind: ServiceAccount
  name: ${SA_NAME}
  namespace: default
roleRef:
  kind: ClusterRole
  name: keycloak-backup-cluster-role
  apiGroup: rbac.authorization.k8s.io
---
apiVersion: v1
kind: Secret
metadata:
  name: "${SA_TOKEN_SECRET_NAME}"
  namespace: default
  annotations:
    kubernetes.io/service-account.name: ${SA_NAME}
type: kubernetes.io/service-account-token
EOF


kubectl get sa ${SA_NAME}  -o yaml

# make sure Service Account Token is filled with a value by api-server
kubectl get secret ${SA_TOKEN_SECRET_NAME}  -o yaml 
```

## Create `kubeconfig` file for the Service Account

```bash
# read necessary values
cluster=$(kubectl config view --minify --output 'jsonpath={..context.cluster}')
ca=$(kubectl get secret ${SA_TOKEN_SECRET_NAME} -n default -o jsonpath='{.data.ca\.crt}')
token=$(kubectl get secret ${SA_TOKEN_SECRET_NAME} -n default -o jsonpath='{.data.token}' | base64 --decode)


# create kubeconfig file

echo "\
apiVersion: v1
kind: Config
clusters:
- name: ${cluster}
  cluster:
    certificate-authority-data: ${ca}
    server: ${KUBEAPI_SERVER}
contexts:
- name: default-context
  context:
    cluster: ${cluster}
    namespace: ${namespace}
    user: default-user
current-context: default-context
users:
- name: default-user
  user:
    token: ${token}
" > keycloak-backup-cluster-sa.kubeconfig

```

We've created our `kubeconfig` file.

```bash
# see if the values are correct
cat -n keycloak-backup-cluster-sa.kubeconfig


# You must check listing/getting pods and exec'ing into pods
kubectl --kubeconfig=keycloak-backup-cluster-sa.kubeconfig get pods

# try exec'ing into a pod
kubectl --kubeconfig=keycloak-backup-cluster-sa.kubeconfig exec -ti keycloak-0 -- ls -l /opt/bitnami/keycloak/

```

## Upload the generated `kubeconfig` to Jenkins Credentials

Create a Secret File type credential and upload our kubeconfig file.
Use the following naming format.

- `keycloak-backup-sa-kubeconfig--<cluster-name>` (for example: `keycloak-backup-sa-kubeconfig--atros-dev-2`)

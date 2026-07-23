podTemplate(yaml: '''
apiVersion: v1
kind: Pod
spec:
  containers:
  - name: jnlp
    image: jenkins/inbound-agent:windowsservercore-ltsc2022
  nodeSelector:
    kubernetes.io/os: windows
    node.kubernetes.io/windows-build: 10.0.20348
'''
) {
    node(POD_LABEL) {
        bat 'dir'
        powershell 'Get-ChildItem Env: | Sort Name'
    }
}

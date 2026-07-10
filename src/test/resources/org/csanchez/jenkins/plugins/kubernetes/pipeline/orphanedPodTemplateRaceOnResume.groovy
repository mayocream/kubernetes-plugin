podTemplate(label: 'orphan-race', yaml: '''
apiVersion: v1
kind: Pod
spec:
  nodeSelector:
    disktype: special
''', containers: [
    containerTemplate(name: 'busybox', image: 'busybox', command: 'cat', ttyEnabled: true)
]) {
    node('orphan-race') {
        container('busybox') {
            sh 'echo finished the test!'
        }
    }
}

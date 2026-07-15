pipeline {
    agent {
        kubernetes {
            defaultContainer 'shell'
            yaml '''
spec:
  containers:
  - name: shell
    image: ubuntu
    workingDir: /elsewhere
    command:
    - sleep
    args:
    - infinity
'''
        }
    }
    stages {
        stage('run') {
            steps {
                sh 'echo running in container in $PWD'
            }
        }
    }
}

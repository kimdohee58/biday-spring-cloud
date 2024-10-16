pipeline {
    agent any

    parameters {
        string(name: 'MODULE', defaultValue: 'all', description: 'Specify module to build (or all)')
    }

    environment {
        JAVA_HOME = '/opt/java/openjdk'
        GRADLE_HOME = '/opt/gradle'
        PATH = "${GRADLE_HOME}/bin:${env.PATH}"
        DOCKER_HUB_REPO = "hwijae/biday"
        SLACK_CHANNEL = '#jenkins-log'
    }

    stages {
        stage('Build') {
            steps {
                script {
                    sh '''
                        #!/bin/bash
                        set -e
                        export JAVA_HOME="$JAVA_HOME"

                        cd backend || { echo "Backend directory not found!"; exit 1; }

                        echo "Current directory contents:"
                        ls -la

                        if [ -f "./gradlew" ]; then
                            echo "Found gradlew. Making it executable..."
                            chmod +x ./gradlew
                        else
                            echo "gradlew not found!"
                            exit 1
                        fi

                        all_modules="server:eureka-server server:config-server server:gateway-server service:admin-service service:auction-service service:ftp-service service:order-service service:product-service service:sms-service service:user-service"

                        echo "Cleaning..."
                        ./gradlew clean

                        for module in $all_modules; do
                            echo "Building BootJar for $module"
                            ./gradlew :$module:bootJar || { echo "Failed to build $module"; exit 1; }
                        done
                    '''
                }
            }
            post {
                success {
                    archiveArtifacts artifacts: '**/build/libs/*.jar', allowEmptyArchive: true
                }
                failure {
                    echo "Build stage failed!"
                    error 'Build failed'
                }
            }
        }

        stage('Docker Login') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'DockerHub_IdPw', usernameVariable: 'DOCKER_USERNAME', passwordVariable: 'DOCKER_PASSWORD')]) {
                        sh "echo ${DOCKER_PASSWORD} | docker login -u ${DOCKER_USERNAME} --password-stdin"
                    }
                }
            }
        }

        stage('Docker build') {
            steps {
                script {
                    sh 'pwd'
                    dir('backend') {
                        sh 'ls -la'
                        sh 'docker-compose build || { echo "Docker build failed!"; exit 1; }'
                    }
                }
            }
        }

        stage('Docker Push') {
            steps {
                script {
                    def services = [
                    "config-server",
                    "eureka-server",
                    "gateway-server",
                    "admin-service",
                    "auction-service",
                    "ftp-service",
                    "order-service",
                    "product-service",
                    "sms-service",
                    "user-service"
                    ]

                    def version = '1.0'

                    for (service in services) {
                        echo "Tagging and pushing ${service}..."
                        sh """
                            docker tag biday/${service}:latest ${DOCKER_HUB_REPO}/${service}:${version} || { echo 'Docker tag failed for ${service}!'; exit 1; }
                            docker push ${DOCKER_HUB_REPO}/${service}:${version} || { echo 'Docker push failed for ${service}!'; exit 1; }
                        """
                    }
                }
            }
            post {
                failure {
                    echo "Docker push failed!"
                    error 'Docker push failed'
                }
            }
        }
    }

    post {
        always {
            echo 'Cleaning up...'
        }
        success {
            script {
                def testSummary = "모든 테스트가 성공했습니다."
                def blocks = [
                    [
                        "type": "section",
                        "text": [
                            "type": "mrkdwn",
                            "text": "Build success - integration-test (<${env.BUILD_URL}|Open>)\n${testSummary}"
                        ]
                    ]
                ]

                def attachments = [
                    [
                        "color": "#000000",
                        "blocks": blocks
                    ]
                ]

                // 슬랙 메세지 보내기
                slackSend(
                    channel: env.SLACK_CHANNEL,
                    attachments: attachments,
                    tokenCredentialId: 'Slack_webhook' // 자격 증명 ID 입력
                )

                // Discord 메세지 보내기
                notifyDiscord("Build succeeded!", "good")
            }
        }
        failure {
            script {
                def testSummary = "테스트 중 오류가 발생했습니다."
                def blocks = [
                    [
                        "type": "section",
                        "text": [
                            "type": "mrkdwn",
                            "text": "Build failure - integration-test (<${env.BUILD_URL}|Open>)\n${testSummary}"
                        ]
                    ]
                ]

                def attachments = [
                    [
                        "color": "#b3312d",
                        "blocks": blocks
                    ]
                ]

                // 슬랙 메세지 보내기
                slackSend(
                    channel: env.SLACK_CHANNEL,
                    attachments: attachments,
                    tokenCredentialId: 'Slack_webhook' // 자격 증명 ID 입력
                )

                // Discord 메세지 보내기
                notifyDiscord("Build failed!", "danger")
            }
        }
    }
}

def notifyDiscord(String message, String result) {
    withCredentials([string(credentialsId: 'Discord-Webhook', variable: 'DISCORD')]) {
        discordSend description: """
        제목 : ${currentBuild.displayName}
        결과 : ${currentBuild.result}
        실행 시간 : ${currentBuild.duration / 1000}s
        """,
        link: env.BUILD_URL, result: result,
        title: "${env.JOB_NAME} : ${currentBuild.displayName} ${message.contains("failed") ? "실패" : "성공"}",
        webhookURL: DISCORD
    }
}
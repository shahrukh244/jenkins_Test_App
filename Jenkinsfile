pipeline {
    agent { label "Jenkins_Worker_Node-01" }

    environment {
        DOCKER_HUB_USER = 'sayedshahrukh93'
        IMAGE_NAME     = 'auth-app'
    }

    stages {

        stage("Code") {
            steps {
                echo "========== Cloning Code =========="
                git url: "https://github.com/shahrukh244/jenkins_Test_App.git",
                    branch: "main"

                sh 'ls -la'
                sh 'git log --oneline -5'
                echo "✓ Cloned Successfully"
            }
        }

        stage("Build") {
            steps {
                echo "========== Building Docker Image =========="
                sh """
                    docker build -t ${IMAGE_NAME}:${BUILD_NUMBER} .
                    docker images ${IMAGE_NAME}:${BUILD_NUMBER} \
                      --format "Image: {{.Repository}}:{{.Tag}} ({{.ID}})"
                """
                echo "✓ Docker Build Successful - ${IMAGE_NAME}:${BUILD_NUMBER}"
            }
        }

        stage("Test") {
            steps {
                echo "========== Testing Application =========="
                sh '''
                    docker stop test-app || true
                    docker rm test-app || true

                    docker run -d --name test-app -p 5001:5000 auth-app:${BUILD_NUMBER}
                    echo "Waiting for app to start..."
                    sleep 15

                    curl -f http://localhost:5001/ || (
                        echo "✗ App health check failed!"
                        docker logs test-app
                        exit 1
                    )

                    docker stop test-app
                    docker rm test-app
                    echo "✓ Application test PASSED on port 5001"
                '''
            }
        }

        stage("Push") {
            steps {
                echo "========== Pushing to Docker Hub =========="

                withCredentials([usernamePassword(
                    credentialsId: 'DockerHubCredential',
                    usernameVariable: 'dockerHubUser',
                    passwordVariable: 'dockerHubPass'
                )]) {
                    sh '''
                        echo "$dockerHubPass" | docker login \
                          -u "$dockerHubUser" --password-stdin

                        docker tag auth-app:${BUILD_NUMBER} \
                          $dockerHubUser/auth-app:${BUILD_NUMBER}
                        docker tag auth-app:${BUILD_NUMBER} \
                          $dockerHubUser/auth-app:latest

                        docker push $dockerHubUser/auth-app:${BUILD_NUMBER}
                        docker push $dockerHubUser/auth-app:latest

                        docker logout
                        echo "✓ Docker Hub push successful"
                    '''
                }
            }
        }

        /* ================= FIXED CLEANUP ================= */

        stage("Cleanup Remote Images") {
            steps {
                echo "========== Cleaning old Docker Hub tags =========="

                withCredentials([
                    string(credentialsId: 'DockerHubToken', variable: 'DOCKER_PAT')
                ]) {
                    sh '''
                        set -e

                        DOCKER_USER="${DOCKER_HUB_USER}"
                        REPO="${DOCKER_USER}/auth-app"

                        echo "Requesting JWT token from Docker Hub..."

                        JSON_PAYLOAD=$(cat <<EOF
{
  "username": "${DOCKER_USER}",
  "password": "${DOCKER_PAT}"
}
EOF
                        )

                        HUB_TOKEN=$(curl -s \
                          -H "Content-Type: application/json" \
                          -X POST \
                          -d "$JSON_PAYLOAD" \
                          https://hub.docker.com/v2/users/login/ | jq -r '.token')

                        if [ -z "$HUB_TOKEN" ] || [ "$HUB_TOKEN" = "null" ]; then
                            echo "⚠️  Unable to get JWT token – skipping cleanup"
                            exit 0
                        fi

                        echo "✓ JWT token obtained"

                        TAGS=$(curl -s \
                          -H "Authorization: JWT $HUB_TOKEN" \
                          https://hub.docker.com/v2/repositories/$REPO/tags/?page_size=100 \
                          | jq -r '.results[].name')

                        NUMERIC=$(echo "$TAGS" | grep -E '^[0-9]+$' || true)
                        KEEP_NUMERIC=$(echo "$NUMERIC" | sort -n | tail -5 || true)

                        KEEP_TAGS=$(printf "%s\nlatest\n" "$KEEP_NUMERIC" | sort -u)

                        echo "Keeping tags:"
                        echo "$KEEP_TAGS"

                        for TAG in $TAGS; do
                            if ! echo "$KEEP_TAGS" | grep -qx "$TAG"; then
                                echo "Deleting tag: $TAG"
                                curl -s -X DELETE \
                                  -H "Authorization: JWT $HUB_TOKEN" \
                                  https://hub.docker.com/v2/repositories/$REPO/tags/$TAG/ \
                                  -o /dev/null
                            fi
                        done

                        echo "✓ Docker Hub cleanup completed"
                    '''
                }
            }
        }

        stage("Deploy") {
            steps {
                echo "========== Deploying Application =========="
                dir('AuthApp') {
                    sh 'docker-compose down || true'
                    sh "IMAGE_NAME=auth-app IMAGE_TAG=${BUILD_NUMBER} docker-compose up -d"
                    sh 'docker-compose ps'
                }
                echo "✓ Deployment Successful"
            }
        }

        stage("Verify Deployment") {
            steps {
                echo "========== Verifying Deployment =========="
                sh '''
                    sleep 15
                    curl -f http://localhost:5000/ \
                      || (echo "✗ App not responding"; exit 1)
                    echo "✓ App is live at http://localhost:5000"
                '''
            }
        }
    }

    post {
        always {
            echo "========== Cleanup Local Docker =========="
            sh '''
                docker images auth-app --format "{{.Repository}}:{{.Tag}}" \
                  | xargs -r docker rmi -f

                docker image prune -f
                docker stop test-app || true
                docker rm test-app || true
            '''
        }

        success {
            echo "✓ PIPELINE PASSED"
            echo "✓ Image: ${DOCKER_HUB_USER}/auth-app:${BUILD_NUMBER}"
            echo "✓ App URL: http://localhost:5000"
        }

        failure {
            echo "✗ PIPELINE FAILED"
            sh 'docker logs test-app || true'
        }
    }
}

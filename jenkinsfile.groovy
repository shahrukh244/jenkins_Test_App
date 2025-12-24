pipeline {
    agent { label "Jenkins_Worker_Node-01" }

    environment {
        DOCKER_HUB_USER = 'sayedshahrukh93'
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
                    docker build -t auth-app:${BUILD_NUMBER} .
                    docker images auth-app:${BUILD_NUMBER} --format "Image: {{.Repository}}:{{.Tag}} ({{.ID}})"
                """
                echo "✓ Docker Build Successful - auth-app:${BUILD_NUMBER}"
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
                    curl -f http://localhost:5001/ || (echo "✗ App health check failed!"; docker logs test-app; exit 1)
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
                    passwordVariable: 'dockerHubPass',
                    usernameVariable: 'dockerHubUser'
                )]) {
                    sh '''
                        echo "Logging into Docker Hub..."
                        echo $dockerHubPass | docker login -u $dockerHubUser --password-stdin

                        docker tag auth-app:${BUILD_NUMBER} $dockerHubUser/auth-app:${BUILD_NUMBER}
                        docker tag auth-app:${BUILD_NUMBER} $dockerHubUser/auth-app:latest

                        docker push $dockerHubUser/auth-app:${BUILD_NUMBER}
                        docker push $dockerHubUser/auth-app:latest

                        docker logout
                    '''
                }
            }
        }

        stage("Cleanup Remote Images") {
            steps {
                echo "========== Cleaning old Docker Hub tags (keep 5 latest + latest) =========="
                withCredentials([
                    string(credentialsId: 'DockerHubToken', variable: 'DOCKER_PAT')
                ]) {
                    sh '''
                        set -e

                        DOCKER_USER="sayedshahrukh93"
                        REPO="auth-app"

                        echo "Getting Docker Hub token..."

                        JSON_PAYLOAD=$(printf '{"username":"%s","password":"%s"}' "$DOCKER_USER" "$DOCKER_PAT")
                        TOKEN=$(curl -s -X POST \
                          -H "Content-Type: application/json" \
                          -d "$JSON_PAYLOAD" \
                          https://hub.docker.com/v2/users/login/ | jq -r .token)

                        if [ -z "$TOKEN" ] || [ "$TOKEN" = "null" ]; then
                            echo "⚠️ Failed to obtain token, skipping cleanup"
                            exit 0
                        fi

                        AUTH="Authorization: Bearer $TOKEN"

                        TAGS_JSON=$(curl -s -H "$AUTH" \
                          "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO/tags/?page_size=100")

                        ALL_TAGS=$(echo "$TAGS_JSON" | jq -r '.results[].name')
                        NUMERIC_TAGS=$(echo "$ALL_TAGS" | grep -E '^[0-9]+$' || true)
                        KEEP_NUMERIC=$(echo "$NUMERIC_TAGS" | sort -n | tail -5)

                        echo "Keeping numeric tags:"
                        echo "$KEEP_NUMERIC"
                        echo "Keeping tag: latest"

                        for TAG in $NUMERIC_TAGS; do
                            echo "$KEEP_NUMERIC" | grep -qx "$TAG" && continue
                            echo "Deleting remote tag: $TAG"
                            curl -s -X DELETE \
                              -H "$AUTH" \
                              "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO/tags/$TAG/" \
                              -o /dev/null -w "HTTP %{http_code}\\n"
                        done

                        echo "✓ Remote Docker Hub cleanup completed"
                    '''
                }
            }
        }

        stage("Deploy") {
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
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
            when {
                expression { currentBuild.currentResult == 'SUCCESS' }
            }
            steps {
                echo "========== Verifying Deployment =========="
                sh '''
                    echo "Waiting for app to fully start..."
                    sleep 15
                    curl -f http://localhost:5000/ || (echo "App not responding!"; exit 1)
                    echo "✓ App is responding correctly at http://localhost:5000"
                '''
            }
        }
    }

    post {
        always {
            echo "Pipeline finished!"
            sh '''
                echo "Removing local build tags auth-app:*"
                docker images auth-app --format "{{.Repository}}:{{.Tag}}" | xargs -r docker rmi -f

                NUM_TAGS=$(docker images "sayedshahrukh93/auth-app" --format "{{.Tag}}" \
                    | grep -E "^[0-9]+$" | sort -n || true)

                if [ -n "$NUM_TAGS" ]; then
                    LATEST_NUMERIC=$(echo "$NUM_TAGS" | tail -3 | tr "\\n" "|" | sed "s/|$//")
                    KEEP_REGEX="($LATEST_NUMERIC|latest)"
                    docker images "sayedshahrukh93/auth-app" --format "{{.Repository}}:{{.Tag}}" \
                      | grep -v -E "$KEEP_REGEX" | xargs -r docker rmi -f
                fi

                docker image prune -f
                docker stop test-app || true
                docker rm test-app || true
            '''
        }
        success {
            echo "✓ PIPELINE PASSED - Full CI/CD Complete!"
        }
        failure {
            echo "✗ PIPELINE FAILED - Check logs"
            sh 'docker logs test-app || true'
        }
    }
}

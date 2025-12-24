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
                    script {
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
                                echo "⚠️ Failed to obtain token, skipping remote cleanup"
                                exit 0
                            fi

                            AUTH="Authorization: Bearer $TOKEN"

                            # Get all tags
                            TAGS_JSON=$(curl -s -H "$AUTH" \
                              "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO/tags/?page_size=100")

                            ALL_TAGS=$(echo "$TAGS_JSON" | jq -r '.results[].name')
                            NUMERIC_TAGS=$(echo "$ALL_TAGS" | grep -E '^[0-9]+$' | sort -n || true)
                            
                            # Keep 5 latest numeric tags + latest
                            KEEP_NUMERIC=$(echo "$NUMERIC_TAGS" | tail -5)
                            
                            echo "Keeping numeric tags:"
                            echo "$KEEP_NUMERIC"
                            echo "Keeping tag: latest"

                            for TAG in $NUMERIC_TAGS; do
                                if echo "$KEEP_NUMERIC" | grep -qx "$TAG"; then
                                    continue
                                fi
                                echo "Deleting remote tag: $TAG"
                                curl -s -X DELETE \
                                  -H "$AUTH" \
                                  "https://hub.docker.com/v2/repositories/$DOCKER_USER/$REPO/tags/$TAG/" \
                                  -o /dev/null -w "HTTP %{http_code}\\n" || true
                            done

                            echo "✓ Remote Docker Hub cleanup completed (kept 5 latest numeric tags + latest)"
                        '''
                    }
                }
            }
        }

        stage("Clean Local Images") {
            steps {
                echo "========== Cleaning local images (keep 3 latest + latest) =========="
                script {
                    sh '''
                        set -e
                        echo "Current local images for sayedshahrukh93/auth-app:"
                        docker images "sayedshahrukh93/auth-app" --format "table {{.Repository}}\\t{{.Tag}}\\t{{.CreatedAt}}"
                        
                        # Get all numeric tags
                        ALL_TAGS=$(docker images "sayedshahrukh93/auth-app" --format "{{.Tag}}" | grep -E '^[0-9]+$' | sort -n || true)
                        
                        if [ -n "$ALL_TAGS" ]; then
                            TOTAL=$(echo "$ALL_TAGS" | wc -l)
                            KEEP=3
                            
                            if [ $TOTAL -gt $KEEP ]; then
                                REMOVE_COUNT=$((TOTAL - KEEP))
                                echo "Found $TOTAL numeric tags, keeping $KEEP, removing $REMOVE_COUNT old tags"
                                
                                # Get oldest tags to remove
                                TAGS_TO_REMOVE=$(echo "$ALL_TAGS" | head -n $REMOVE_COUNT)
                                
                                for TAG in $TAGS_TO_REMOVE; do
                                    echo "Removing local tag: sayedshahrukh93/auth-app:$TAG"
                                    docker rmi "sayedshahrukh93/auth-app:$TAG" 2>/dev/null || echo "Could not remove $TAG (might be in use)"
                                done
                            else
                                echo "Only $TOTAL numeric tags found, keeping all"
                            fi
                        fi
                        
                        echo "✓ Local cleanup completed (kept 3 latest numeric tags + latest)"
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
                echo "=== Final Cleanup ==="
                
                # Remove local build tag
                echo "Removing local build tag auth-app:${BUILD_NUMBER}"
                docker rmi auth-app:${BUILD_NUMBER} 2>/dev/null || true
                
                # Clean up test container
                docker stop test-app 2>/dev/null || true
                docker rm test-app 2>/dev/null || true
                
                # Remove dangling images
                docker image prune -f
                
                echo "=== Final state ==="
                echo "Local auth-app images:"
                docker images auth-app --format "table {{.Repository}}\\t{{.Tag}}" 2>/dev/null || true
                echo ""
                echo "Docker Hub auth-app images:"
                docker images "sayedshahrukh93/auth-app" --format "table {{.Repository}}\\t{{.Tag}}\\t{{.CreatedAt}}" || true
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

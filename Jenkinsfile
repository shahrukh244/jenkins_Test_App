pipeline {
    agent {label "Jenkins_Worker_Node-01"}
    
    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        buildDiscarder(logRotator(numToKeepStr:'10'))
    }
    
    environment {
        IMAGE_NAME = "auth-app"
        IMAGE_TAG = "${BUILD_NUMBER}"
        DOCKER_COMPOSE_FILE = "docker-compose.yml"
    }
    
    stages {
        stage("Code") {
            steps {
                echo "========== Cloning Code =========="
                git url: "https://github.com/shahrukh244/jenkins_Test_App.git", 
                    branch: "main"
                echo "✓ Cloned Successfully"
            }
        }
        
        stage("Build") {
            steps {
                echo "========== Building Docker Image =========="
                sh 'docker build -t ${IMAGE_NAME}:${IMAGE_TAG} .'
                sh 'docker images | grep ${IMAGE_NAME}'
                echo "✓ Docker Build Successful"
            }
        }
        
        stage("Test") {
            steps {
                echo "========== Running Basic Verification =========="
                // Simple container startup test since repo doesn't show pytest tests
                sh """
                    docker run -d --name test-app -p 5000:5000 ${IMAGE_NAME}:${IMAGE_TAG}
                    sleep 10
                    curl -f http://localhost:5000 || (echo '✓ Basic test failed!'; docker logs test-app; exit 1)
                    echo '✓ Application endpoint test passed'
                """
                sh 'docker stop test-app || true'
                sh 'docker rm test-app || true'
            }
        }
        
        stage("Deploy") {
            steps {
                echo "========== Deploying Application =========="
                sh 'docker-compose down || true'
                // Use the --build flag as specified in repo instructions
                sh "IMAGE_NAME=${IMAGE_NAME} IMAGE_TAG=${IMAGE_TAG} docker-compose up -d --build"
                sh 'docker-compose ps'
                echo "✓ Deployment Successful"
            }
        }
        
        stage("Verify Deployment") {
            steps {
                echo "========== Verifying Deployment =========="
                sh '''
                    echo "Waiting for app to fully start..."
                    sleep 15
                    echo "Checking application status..."
                    curl -f http://localhost:5000/ || (echo "App not responding!"; exit 1)
                    echo "✓ App is responding correctly at http://localhost:5000"
                '''
            }
        }
    }
    
    post {
        always {
            echo "Cleaning up test resources..."
            sh 'docker stop test-app || true'
            sh 'docker rm test-app || true'
            // Safer cleanup - only prune stopped containers
            sh 'docker container prune -f'
            echo "Pipeline finished!"
        }
        success {
            echo "✓ PIPELINE PASSED - App deployed at http://localhost:5000"
        }
        failure {
            echo "✗ PIPELINE FAILED - Check logs"
            sh 'docker-compose logs || true'
        }
    }
}

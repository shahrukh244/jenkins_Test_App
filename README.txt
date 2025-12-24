
# To Run Only 'Dockerfile'. Which Will Create Only Images. Use Blow Command.
docker build -t authapp:latest .


# To Run 'docker_compose-local.yaml' File. Which Will Create Images And Run The Docker Container Aswell.
# To Verify App at Given URL
# http://localhost:5000 Or http://<IP-OF-NODE>:5000
docker-compose up -d --build
docker-compose down
IMAGE_TAG=v2 docker-compose up -d --build



# To Create Jenkins Pipeline Use 'jenkinsfile.groovy' File.
# Create Jenkins Pipeline
# Copy And Past 'Jenkinsfile' Script Into Jenkins Pipeline Script.



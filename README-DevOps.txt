
# To Run Only 'Dockerfile'. Which Will Create Only Images. Use Blow Command.
docker build -t authapp:latest .


# To Run 'docker-compose.yaml' File. Which Will Create Images And Run The Docker Container Aswell.
# To Verify App at Given URL
# http://localhost:5000 Or http://<IP-OF-NODE>:5000
docker-compose up -d --build


# To Create Jenkins Pipeline Use 'Jenkinsfile' File.
# Create Jenkins Pipeline
# Copy And Past 'Jenkinsfile' Script Into Jenkins Pipeline Script.



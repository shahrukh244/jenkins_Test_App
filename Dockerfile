FROM sayedshahrukh93/python:3.9-slim

WORKDIR /app

# Copy requirements.txt and install dependencies
COPY AuthApp/requirements/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy the Flask application AuthApp
COPY AuthApp/flask-app/ .

EXPOSE 5000

# Define environment variables
ENV FLASK_APP=app.py
ENV FLASK_RUN_HOST=0.0.0.0
ENV FLASK_RUN_PORT=5000
ENV PYTHONUNBUFFERED=1

# Default command (will be overridden by docker-compose if needed)
CMD ["flask", "run"]

from flask import Flask, render_template, request, redirect, url_for, session, flash
from werkzeug.security import generate_password_hash, check_password_hash
import re
import os
import pymysql  # Using PyMySQL as an example, ensure it's in requirements.txt

app = Flask(__name__)
# IMPORTANT: This secret key is now loaded from environment variables in Kubernetes
# For local development, you can set it like this:
app.secret_key = os.environ.get('FLASK_SECRET_KEY', 'your_super_secret_and_long_key_here_CHANGE_ME')

# --- MySQL Database Configuration ---
# In Docker Compose, these are:
#   host: mysql
#   database: flask_db
#   user: flask_user
#   password: flask_password
DB_HOST = os.environ.get('MYSQL_HOST', 'mysql')
DB_DATABASE = os.environ.get('MYSQL_DATABASE', 'flask_db')
DB_USER = os.environ.get('MYSQL_USER', 'flask_user')
DB_PASSWORD = os.environ.get('MYSQL_PASSWORD', 'flask_password')

def get_db_connection():
    """Establishes a connection to the MySQL database."""
    try:
        conn = pymysql.connect(
            host=DB_HOST,
            user=DB_USER,
            password=DB_PASSWORD,
            database=DB_DATABASE,
            cursorclass=pymysql.cursors.DictCursor  # Return results as dictionaries
        )
        return conn
    except pymysql.Error as e:
        print(f"DATABASE CONNECTION ERROR: {e}", flush=True)
        flash("Database connection error. Please try again later.", 'danger')
        return None

# --- User Management Functions (MySQL) ---

def get_user(username):
    """Retrieves user data from the MySQL database by username."""
    conn = get_db_connection()
    if conn is None:
        return None
    try:
        with conn.cursor() as cursor:
            sql = "SELECT id, firstname, username, email, password FROM users WHERE username = %s"
            cursor.execute(sql, (username,))
            user = cursor.fetchone()
            if user:
                user['password_hash'] = user.pop('password')
            return user
    except pymysql.Error as e:
        print(f"DATABASE ERROR fetching user: {e}", flush=True)
        return None
    finally:
        if conn and conn.open:
            conn.close()

def add_user(username, email, password_hash):
    """Adds a new user to the MySQL database."""
    conn = get_db_connection()
    if conn is None:
        return False
    try:
        with conn.cursor() as cursor:
            sql = "INSERT INTO users (firstname, username, email, password) VALUES (%s, %s, %s, %s)"
            cursor.execute(sql, (username, username, email, password_hash))
        conn.commit()
        return True
    except pymysql.Error as e:
        print(f"DATABASE ERROR during add_user: {e}", flush=True)
        conn.rollback()
        return False
    finally:
        if conn and conn.open:
            conn.close()

def update_user_password(username, new_password_hash):
    """Updates a user's password in the MySQL database."""
    conn = get_db_connection()
    if conn is None:
        return False
    try:
        with conn.cursor() as cursor:
            sql = "UPDATE users SET password = %s WHERE username = %s"
            cursor.execute(sql, (new_password_hash, username))
        conn.commit()
        return True
    except pymysql.Error as e:
        print(f"DATABASE ERROR updating password: {e}", flush=True)
        conn.rollback()
        return False
    finally:
        if conn and conn.open:
            conn.close()

# --- Validation Functions ---

def is_valid_password(password):
    """Basic password strength validation."""
    return len(password) >= 6

def is_valid_email(email):
    """Basic email format validation."""
    return re.match(r"[^@]+@[^@]+\.[^@]+", email) is not None

# --- Flask Routes ---

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/login', methods=['GET', 'POST'])
def login():
    if 'username' in session:
        flash("You are already logged in.", "info")
        return redirect(url_for('dashboard'))

    if request.method == 'POST':
        username = request.form['username'].strip()
        password = request.form['password']

        user = get_user(username)
        if user and check_password_hash(user['password_hash'], password):
            session['username'] = username
            flash("Logged in successfully!", "success")
            return redirect(url_for('dashboard'))
        else:
            flash("Invalid username or password.", "danger")
            return redirect(url_for('login'))
    return render_template('login.html')

@app.route('/dashboard')
def dashboard():
    if 'username' not in session:
        flash("Please log in first to access your dashboard.", "warning")
        return redirect(url_for('login'))
    return render_template('dashboard.html', username=session['username'])

@app.route('/logout')
def logout():
    session.pop('username', None)
    flash("You have been logged out.", "info")
    return redirect(url_for('index'))

@app.route('/signup', methods=['GET', 'POST'])
def signup():
    if 'username' in session:
        flash("You are already logged in. Please log out to create a new account.", "info")
        return redirect(url_for('dashboard'))

    if request.method == 'POST':
        username = request.form['username'].strip()
        email = request.form['email'].strip()
        password = request.form['password']
        confirm_password = request.form['confirm_password']

        if get_user(username):
            flash("Username already exists. Please choose a different one.", "danger")
            return redirect(url_for('signup'))

        if not is_valid_email(email):
            flash("Invalid email format.", "danger")
            return redirect(url_for('signup'))

        if password != confirm_password:
            flash("Passwords do not match.", "danger")
            return redirect(url_for('signup'))

        if not is_valid_password(password):
            flash("Password must be at least 6 characters long.", "danger")
            return redirect(url_for('signup'))

        if add_user(username, email, generate_password_hash(password)):
            flash(f"Account for {username} created successfully! Please log in.", "success")
            return redirect(url_for('login'))
        else:
            flash("Failed to create account due to a database error. Please try again.", "danger")
            return redirect(url_for('signup'))

    return render_template('signup.html')

@app.route('/forgot-password', methods=['GET', 'POST'])
def forgot_password():
    if request.method == 'POST':
        form_type = request.form.get('form_type')

        if form_type == 'verify_user':
            username = request.form['username'].strip()
            email = request.form['email'].strip()

            user = get_user(username)
            if user:
                flash("Username verified. Please reset your password.", "info")
                return render_template('forgot_password.html', show_reset_form=True, username=username, email=email)
            else:
                flash("Invalid username or email. Please try again.", "danger")
                return redirect(url_for('forgot_password'))

        elif form_type == 'reset_password':
            username = request.form['username'].strip()
            email = request.form['email'].strip()
            new_password = request.form['new_password']
            confirm_new_password = request.form['confirm_new_password']

            if not is_valid_password(new_password):
                flash("New password must be at least 6 characters long.", "danger")
                return render_template('forgot_password.html', show_reset_form=True, username=username, email=email)

            if new_password != confirm_new_password:
                flash("New passwords do not match!", "danger")
                return render_template('forgot_password.html', show_reset_form=True, username=username, email=email)

            user = get_user(username)
            if user:
                if update_user_password(username, generate_password_hash(new_password)):
                    flash("Your password has been successfully reset. Please log in.", "success")
                    return redirect(url_for('login'))
                else:
                    flash("Error resetting password due to a database issue. Please try again.", "danger")
                    return render_template('forgot_password.html', show_reset_form=True, username=username, email=email)
            else:
                flash("User not found during password reset. Please start the process again.", "danger")
                return redirect(url_for('forgot_password'))

    return render_template('forgot_password.html', show_reset_form=False)

# Optional: Debug route to list all registered routes
@app.route('/routes')
def list_routes():
    from flask import url_for
    output = []
    for rule in app.url_map.iter_rules():
        options = {}
        for arg in rule.arguments:
            options[arg] = "[{}]".format(arg)
        line = "{} -> {}".format(rule.endpoint, url_for(rule.endpoint, **options))
        output.append(line)
    return "<pre>" + "\n".join(sorted(output)) + "</pre>"

if __name__ == '__main__':
    app.run(debug=True)


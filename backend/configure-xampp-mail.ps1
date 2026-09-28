# OSHC SmartGuide - XAMPP/Mercury Mail environment for this PowerShell session.
# Run this from the backend folder before starting Spring Boot.
$env:MAIL_HOST = "127.0.0.1"
$env:MAIL_PORT = "25"
$env:MAIL_USERNAME = ""
$env:MAIL_PASSWORD = ""
$env:MAIL_SMTP_AUTH = "false"
$env:MAIL_SMTP_STARTTLS = "false"
$env:MAIL_SMTP_STARTTLS_REQUIRED = "false"
$env:MAIL_SMTP_SSL_TRUST = "127.0.0.1"
$env:MAIL_FROM = "your-sender@example.com"
$env:FRONTEND_URL = "http://localhost:5173"
Write-Host "XAMPP/Mercury SMTP environment loaded. Set MAIL_FROM to the sender address configured in Mercury."

# OSHC SmartGuide - local MailHog SMTP configuration
# Run this PowerShell script in the backend folder before starting Spring Boot.
$env:MAIL_HOST = "127.0.0.1"
$env:MAIL_PORT = "1025"
$env:MAIL_USERNAME = ""
$env:MAIL_PASSWORD = ""
$env:MAIL_SMTP_AUTH = "false"
$env:MAIL_SMTP_STARTTLS = "false"
$env:MAIL_SMTP_STARTTLS_REQUIRED = "false"
$env:MAIL_SMTP_SSL_TRUST = "127.0.0.1"
$env:MAIL_FROM = "oshc-smartguide@localhost"
$env:FRONTEND_URL = "http://localhost:5173"

Write-Host ""
Write-Host "OSHC SmartGuide MailHog SMTP settings loaded for this PowerShell session." -ForegroundColor Green
Write-Host "SMTP: 127.0.0.1:1025"
Write-Host "MailHog UI: http://localhost:8025"
Write-Host "Frontend: http://localhost:5173"
Write-Host ""
Write-Host "Now run: mvn spring-boot:run"

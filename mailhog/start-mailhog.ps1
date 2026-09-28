# Start MailHog using Docker Desktop.
# Docker Desktop must be installed and running.
Set-Location $PSScriptRoot
docker compose up -d
Write-Host ""
Write-Host "MailHog started." -ForegroundColor Green
Write-Host "SMTP: 127.0.0.1:1025"
Write-Host "Inbox: http://localhost:8025"

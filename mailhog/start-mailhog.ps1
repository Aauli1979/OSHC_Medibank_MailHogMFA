# Start only MailHog from the project's single Compose file.
param([ValidateSet("docker", "podman")][string]$Engine = "docker")
$ErrorActionPreference = "Stop"
$composeFile = Join-Path (Split-Path $PSScriptRoot -Parent) "compose.yaml"
& $Engine compose -f $composeFile up -d mailhog
if ($LASTEXITCODE -ne 0) { throw "MailHog could not be started." }
Write-Host "MailHog started. Default inbox: http://localhost:8025; SMTP: 127.0.0.1:1025"
Write-Host "Custom ports can be configured in the root .env file."

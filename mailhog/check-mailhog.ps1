# Check whether MailHog SMTP and web ports are reachable.
$ports = @(1025, 8025)
foreach ($port in $ports) {
    $ok = Test-NetConnection -ComputerName 127.0.0.1 -Port $port -InformationLevel Quiet
    if ($ok) {
        Write-Host "OK   127.0.0.1:$port" -ForegroundColor Green
    } else {
        Write-Host "FAIL 127.0.0.1:$port" -ForegroundColor Red
    }
}

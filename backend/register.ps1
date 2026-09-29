param([string]$Email = "test@example.com", [string]$Password = "TestPassword123!")
$base = "http://localhost:8080"
$csrf = Invoke-RestMethod -Uri "$base/api/auth/csrf" -SessionVariable session
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
$body = @{ email = $Email; password = $Password } | ConvertTo-Json
$result = Invoke-RestMethod -Uri "$base/api/auth/register" -Method POST -WebSession $session -Headers $headers -ContentType "application/json" -Body $body
$result
Write-Host "Registration requires TOTP verification. Keep the authenticator URI private."
$code = Read-Host "Set up your authenticator using otpauthUri above, then enter its six-digit code"
$csrf = Invoke-RestMethod -Uri "$base/api/auth/csrf" -WebSession $session
$headers = @{}
$headers[$csrf.headerName] = $csrf.token
Invoke-RestMethod -Uri "$base/api/auth/register/mfa/verify" -Method POST -WebSession $session -Headers $headers -ContentType "application/json" -Body (@{code=$code} | ConvertTo-Json)

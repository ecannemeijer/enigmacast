# Skip the CodeIgniter front controller for /enigmacast/.
# Without this, the request becomes:
# Can't find a route for 'get: enigmacast/dist/enigmacast.json'.

$ErrorActionPreference = 'Stop'
$marker = 'EnigmaCast-static-begin'
$block = @"
	# EnigmaCast-static-begin
	RewriteRule ^enigmacast(/|$) - [L]
	# EnigmaCast-static-end
"@

$roots = New-Object System.Collections.Generic.List[string]
[void]$roots.Add('C:\xampp\htdocs')
[void]$roots.Add('C:\xampp\htdocs\public')

$httpd = 'C:\xampp\apache\conf\httpd.conf'
if (Test-Path $httpd) {
    foreach ($line in Get-Content $httpd) {
        if ($line -match '^\s*DocumentRoot\s+"([^"]+)"') {
            $doc = $Matches[1] -replace '/', '\'
            if (-not $roots.Contains($doc)) { [void]$roots.Add($doc) }
        }
    }
}

$utf8 = New-Object System.Text.UTF8Encoding $false
foreach ($root in $roots) {
    $htaccess = Join-Path $root '.htaccess'
    if (-not (Test-Path $htaccess)) { continue }
    $text = [IO.File]::ReadAllText($htaccess)
    if ($text.Contains($marker)) { continue }
    if ($text -notmatch 'RewriteEngine\s+On') { continue }
    $updated = [regex]::Replace($text, '(?m)^([ \t]*RewriteEngine\s+On[^\r\n]*)', ('$1' + "`r`n" + $block), 1)
    [IO.File]::WriteAllText($htaccess, $updated, $utf8)
    Write-Output "Updated $htaccess"
}

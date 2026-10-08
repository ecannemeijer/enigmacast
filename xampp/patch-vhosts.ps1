# Append one VirtualHost for EnigmaCast, using the same local DNS suffix as the other sites.
$ErrorActionPreference = 'Stop'

$xampp = 'C:\xampp'
$httpdPath = Join-Path $xampp 'apache\conf\httpd.conf'
$vhostsPath = Join-Path $xampp 'apache\conf\extra\httpd-vhosts.conf'
$snippetPath = Join-Path $PSScriptRoot 'enigmacast.conf'
$utf8 = New-Object System.Text.UTF8Encoding $false
$knownSuffixes = @('local', 'test', 'localhost', 'lan', 'dev', 'home', 'loc', 'intra')

if (-not (Test-Path $httpdPath)) { throw "Ontbreekt: $httpdPath" }
if (-not (Test-Path $vhostsPath)) { throw "Ontbreekt: $vhostsPath" }
if (-not (Test-Path $snippetPath)) { throw "Ontbreekt: $snippetPath" }

function Get-ServerToken([System.Text.RegularExpressions.Match]$match) {
    if ($match.Groups[1].Success) { return $match.Groups[1].Value.Trim() }
    return $match.Groups[2].Value.Trim()
}

$vhosts = [IO.File]::ReadAllText($vhostsPath)
$namePattern = '(?m)^[ \t]*Server(?:Name|Alias)[ \t]+(?:"([^"]+)"|(\S+))'
$counts = @{}
foreach ($match in [regex]::Matches($vhosts, $namePattern)) {
    $name = (Get-ServerToken $match).ToLower()
    if ($name -eq 'localhost' -or $name -eq '127.0.0.1' -or $name -eq '*') { continue }
    if ($name -notmatch '\.([a-z0-9-]+)$') { continue }
    $suffix = $Matches[1]
    if ($knownSuffixes -notcontains $suffix) { continue }
    if (-not $counts.ContainsKey($suffix)) { $counts[$suffix] = 0 }
    $counts[$suffix]++
}

$suffix = 'local'
if ($counts.Count -gt 0) {
    $suffix = ($counts.GetEnumerator() | Sort-Object Value -Descending | Select-Object -First 1).Key
}

$serverName = "enigmacast.$suffix"
foreach ($match in [regex]::Matches($vhosts, '(?m)^[ \t]*ServerName[ \t]+(?:"([^"]+)"|(\S+))')) {
    $name = Get-ServerToken $match
    if ($name -match 'enigmacast') {
        $serverName = $name
        break
    }
}

$snippet = [IO.File]::ReadAllText($snippetPath).Replace('__SERVER_NAME__', $serverName).Trim()
$blockPattern = '(?s)# EnigmaCast-vhost-begin.*?# EnigmaCast-vhost-end'
$vhosts = [regex]::Replace($vhosts, $blockPattern, '')

$existing = $null
foreach ($match in [regex]::Matches($vhosts, '(?s)<VirtualHost\b[^>]*>.*?</VirtualHost>')) {
    if ($match.Value -match '(?m)^[ \t]*ServerName[ \t]+.*enigmacast' -or $match.Value -match 'htdocs/enigmacast') {
        $existing = $match
        break
    }
}
if ($existing) {
    $vhosts = $vhosts.Remove($existing.Index, $existing.Length).Insert($existing.Index, $snippet)
} else {
    $vhosts = $vhosts.TrimEnd() + "`r`n`r`n" + $snippet + "`r`n"
}
[IO.File]::WriteAllText($vhostsPath, $vhosts, $utf8)

$httpd = [IO.File]::ReadAllText($httpdPath)
$httpd = [regex]::Replace($httpd, '(?m)^#[ \t]*(Include[ \t]+conf/extra/httpd-vhosts\.conf)[ \t]*\r?$', '$1')
$httpd = [regex]::Replace($httpd, '(?m)^[ \t]*#[ \t]*EnigmaCast[ \t]*\r?\n', '')
$httpd = [regex]::Replace($httpd, '(?m)^[ \t]*Include[ \t]+conf/extra/httpd-enigmacast\.conf[ \t]*\r?\n', '')
if ($httpd -notmatch '(?m)^[ \t]*Include[ \t]+conf/extra/httpd-vhosts\.conf[ \t]*$') {
    $httpd = $httpd.TrimEnd() + "`r`nInclude conf/extra/httpd-vhosts.conf`r`n"
}
[IO.File]::WriteAllText($httpdPath, $httpd, $utf8)

$hostsPath = Join-Path $env:SystemRoot 'System32\drivers\etc\hosts'
$listed = $false
foreach ($line in Get-Content -Path $hostsPath) {
    if ($line -match ('^\s*127\.0\.0\.1\s+.*\b' + [regex]::Escape($serverName) + '\b')) {
        $listed = $true
        break
    }
}
if (-not $listed) {
    Add-Content -Path $hostsPath -Value "127.0.0.1 $serverName" -Encoding Ascii
}

Write-Output $serverName

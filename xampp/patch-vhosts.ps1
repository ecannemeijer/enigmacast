# Put the EnigmaCast VirtualHost in httpd-vhosts.conf and make sure Apache loads that file.
$ErrorActionPreference = 'Stop'

$xampp = 'C:\xampp'
$httpdPath = Join-Path $xampp 'apache\conf\httpd.conf'
$vhostsPath = Join-Path $xampp 'apache\conf\extra\httpd-vhosts.conf'
$snippetPath = Join-Path $PSScriptRoot 'enigmacast.conf'
$utf8 = New-Object System.Text.UTF8Encoding $false

if (-not (Test-Path $httpdPath)) { throw "Ontbreekt: $httpdPath" }
if (-not (Test-Path $vhostsPath)) { throw "Ontbreekt: $vhostsPath" }
if (-not (Test-Path $snippetPath)) { throw "Ontbreekt: $snippetPath" }

$snippet = [IO.File]::ReadAllText($snippetPath).Trim()
$begin = '# EnigmaCast-vhost-begin'
$end = '# EnigmaCast-vhost-end'

$httpd = [IO.File]::ReadAllText($httpdPath)
$httpd = [regex]::Replace($httpd, '(?m)^#[ \t]*(Include[ \t]+conf/extra/httpd-vhosts\.conf)[ \t]*\r?$', '$1')
$httpd = [regex]::Replace($httpd, '(?m)^[ \t]*#[ \t]*EnigmaCast[ \t]*\r?\n', '')
$httpd = [regex]::Replace($httpd, '(?m)^[ \t]*Include[ \t]+conf/extra/httpd-enigmacast\.conf[ \t]*\r?\n', '')
if ($httpd -notmatch '(?m)^[ \t]*Include[ \t]+conf/extra/httpd-vhosts\.conf[ \t]*$') {
    $httpd = $httpd.TrimEnd() + "`r`nInclude conf/extra/httpd-vhosts.conf`r`n"
}
[IO.File]::WriteAllText($httpdPath, $httpd, $utf8)

$vhosts = [IO.File]::ReadAllText($vhostsPath)
$pattern = '(?s)' + [regex]::Escape($begin) + '.*?' + [regex]::Escape($end)
if ([regex]::IsMatch($vhosts, $pattern)) {
    $vhosts = [regex]::Replace($vhosts, $pattern, $snippet, 1)
} else {
    $match = [regex]::Match($vhosts, '(?m)^[ \t]*<VirtualHost\b')
    if ($match.Success) {
        $vhosts = $vhosts.Insert($match.Index, $snippet + "`r`n`r`n")
    } else {
        $vhosts = $vhosts.TrimEnd() + "`r`n`r`n" + $snippet + "`r`n"
    }
}
[IO.File]::WriteAllText($vhostsPath, $vhosts, $utf8)
Write-Output "VirtualHost geschreven in $vhostsPath"

[CmdletBinding()]
param(
    [string]$Bind,
    [string]$Peer,
    [int]$Port = 0,
    [switch]$Remove,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$ownedGroup = 'STALCRAFT Legacy'

function Fail([string]$Message) {
    throw $Message
}

function Read-KitProperties([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Fail "Не найден файл настроек: $Path"
    }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or $trimmed.StartsWith('!')) { continue }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) { continue }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        $values[$key] = $value
    }
    return $values
}

function Parse-IPv4([string]$Value, [string]$Label) {
    $address = $null
    if (-not [Net.IPAddress]::TryParse($Value, [ref]$address) -or
        $address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $address.ToString() -ne $Value) {
        Fail "$Label должен быть точным IPv4-адресом."
    }
    return ,$address.GetAddressBytes()
}

function Is-PrivateIPv4([byte[]]$Bytes) {
    return ($Bytes[0] -eq 10) -or
        ($Bytes[0] -eq 172 -and $Bytes[1] -ge 16 -and $Bytes[1] -le 31) -or
        ($Bytes[0] -eq 192 -and $Bytes[1] -eq 168) -or
        ($Bytes[0] -eq 100 -and $Bytes[1] -ge 64 -and $Bytes[1] -le 127)
}

function Find-LocalAdapter([string]$Address) {
    foreach ($adapter in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        foreach ($unicast in $adapter.GetIPProperties().UnicastAddresses) {
            if ($unicast.Address.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and
                $unicast.Address.ToString() -eq $Address) {
                return $adapter
            }
        }
    }
    return $null
}

function Test-ExactSingleValue($Actual, [string]$Expected) {
    $items = @($Actual | ForEach-Object { [string]$_ })
    return ($items.Count -eq 1 -and $items[0] -ieq $Expected)
}

function Test-RuleMatches($Rule, $Expected) {
    $app = $Rule | Get-NetFirewallApplicationFilter
    $address = $Rule | Get-NetFirewallAddressFilter
    $portFilter = $Rule | Get-NetFirewallPortFilter
    return ($Rule.Group -eq $ownedGroup -and $Rule.Enabled -eq 'True' -and
        $Rule.Direction -eq 'Inbound' -and $Rule.Action -eq 'Allow' -and
        $Rule.Profile -eq 'Any' -and $app.Program -ieq $Expected.Program -and
        $portFilter.Protocol -in @('TCP', '6') -and
        (Test-ExactSingleValue $portFilter.LocalPort $Expected.LocalPort) -and
        (Test-ExactSingleValue $address.LocalAddress $Expected.LocalAddress) -and
        (Test-ExactSingleValue $address.RemoteAddress $Expected.RemoteAddress))
}

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

try {
    # Packaged kit root is the parent of its tools directory.
    $kitRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
    $kitFile = Join-Path $kitRoot 'kit.properties'
    $kit = Read-KitProperties $kitFile
    if ($kit['role'] -ne 'server') { Fail 'Правило брандмауэра можно настроить только для комплекта role=server.' }

    $javaPath = Join-Path $kitRoot 'runtime\java\bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaPath -PathType Leaf)) { Fail "Не найден Java комплекта: $javaPath" }
    $javaPath = (Resolve-Path -LiteralPath $javaPath).Path

    $connectionPath = Join-Path $kitRoot 'connection.properties'
    $connection = Read-KitProperties $connectionPath
    if ($Port -eq 0) {
        $portText = [string]$connection['port']
        if (-not $portText) { $portText = [string]$kit['port'] }
        if (-not $portText) { $portText = '25576' }
        if (-not [int]::TryParse($portText, [ref]$Port)) { Fail 'В connection.properties задан неверный порт.' }
    }
    if ($Port -lt 1 -or $Port -gt 65535) { Fail 'Порт должен быть в диапазоне 1–65535.' }

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $rootHash = [BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($kitRoot.ToLowerInvariant()))).Replace('-', '').Substring(0, 16).ToLowerInvariant()
    } finally { $sha.Dispose() }
    $ruleName = "STALCRAFT-Legacy-$rootHash-TCP-$Port"

    if ($Remove) {
        $removePlan = [ordered]@{ Name = $ruleName; Group = $ownedGroup }
        if ($DryRun) {
            [ordered]@{ operation = 'remove'; dryRun = $true; plannedRule = $removePlan; message = 'Проверочный режим: правило брандмауэра не читалось и не изменялось.' } | ConvertTo-Json -Depth 4
            exit 0
        }
        if (-not (Test-IsAdministrator)) { Fail 'Для изменения брандмауэра откройте PowerShell от имени администратора и повторите команду.' }
        $existing = @(Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)
        if ($existing.Count -eq 0) {
            [ordered]@{ operation = 'remove'; removed = $false; ruleName = $ruleName; message = 'Правило уже отсутствует.' } | ConvertTo-Json -Depth 4
            exit 0
        }
        if ($existing.Count -ne 1 -or $existing[0].Group -ne $ownedGroup) {
            Fail 'Правило с таким именем не принадлежит группе STALCRAFT Legacy; удаление запрещено.'
        }
        Remove-NetFirewallRule -InputObject $existing[0]
        [ordered]@{ operation = 'remove'; removed = $true; ruleName = $ruleName; message = 'Правило удалено.' } | ConvertTo-Json -Depth 4
        exit 0
    }

    if (-not $PSBoundParameters.ContainsKey('Bind') -or [string]::IsNullOrWhiteSpace($Bind)) {
        $Bind = [string]$connection['bind']
    }
    if ([string]::IsNullOrWhiteSpace($Bind)) { Fail 'Укажите локальный адрес сервера через -Bind или connection.properties (bind=...).' }
    $bindBytes = Parse-IPv4 $Bind 'Bind'
    $peerBytes = $null
    $adapter = Find-LocalAdapter $Bind
    if (-not $adapter) { Fail "Адрес bind $Bind не назначен сетевому интерфейсу этого компьютера." }
    $isRadmin = (($adapter.Name + ' ' + $adapter.Description).IndexOf('Radmin', [StringComparison]::OrdinalIgnoreCase) -ge 0)
    $bindIs26 = ($bindBytes[0] -eq 26)
    if (-not (Is-PrivateIPv4 $bindBytes) -and -not ($bindIs26 -and $isRadmin)) {
        Fail 'Bind должен быть частным IPv4 из RFC1918 или 100.64/10; сеть 26/8 разрешена только на интерфейсе Radmin VPN.'
    }

    if ([string]::IsNullOrWhiteSpace($Peer)) {
        $Peer = Read-Host 'Введите IPv4-адрес клиента, которому разрешить подключение'
    }
    $peerBytes = Parse-IPv4 $Peer 'Peer'
    if (-not (Is-PrivateIPv4 $peerBytes) -and -not ($peerBytes[0] -eq 26 -and $isRadmin)) {
        Fail 'Peer должен быть частным IPv4 из RFC1918 или 100.64/10; сеть 26/8 разрешена только при bind через Radmin VPN.'
    }

    $plannedRule = [ordered]@{
        Name = $ruleName
        Group = $ownedGroup
        Program = $javaPath
        Direction = 'Inbound'
        Action = 'Allow'
        Enabled = 'True'
        Profile = 'Any'
        Protocol = 'TCP'
        LocalPort = [string]$Port
        LocalAddress = $Bind
        RemoteAddress = $Peer
    }

    if ($DryRun) {
        $operation = if ($Remove) { 'remove' } else { 'create' }
        [ordered]@{ operation = $operation; dryRun = $true; plannedRule = $plannedRule; message = 'Проверочный режим: правила брандмауэра не читались и не изменялись.' } | ConvertTo-Json -Depth 5
        exit 0
    }

    if (-not (Test-IsAdministrator)) { Fail 'Для изменения брандмауэра откройте PowerShell от имени администратора и повторите команду.' }
    $existing = @(Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue)
    if ($existing.Count -gt 0) {
        if ($existing.Count -eq 1 -and (Test-RuleMatches $existing[0] $plannedRule)) {
            [ordered]@{ operation = 'create'; created = $false; ruleName = $ruleName; message = 'Совпадающее правило уже настроено.' } | ConvertTo-Json -Depth 4
            exit 0
        }
        Fail 'Правило с этим именем уже существует, но его параметры отличаются. Удалите только это правило командой -Remove после проверки, затем повторите создание.'
    }

    New-NetFirewallRule -Name $ruleName -DisplayName "STALCRAFT Legacy server $Bind`:$Port from $Peer" `
        -Group $ownedGroup -Program $javaPath -Direction Inbound -Action Allow -Enabled True `
        -Profile Any -Protocol TCP -LocalPort $Port -LocalAddress $Bind -RemoteAddress $Peer | Out-Null
    [ordered]@{ operation = 'create'; created = $true; plannedRule = $plannedRule; message = 'Создано узкое правило TCP для выбранного клиента.' } | ConvertTo-Json -Depth 5
} catch {
    [Console]::Error.WriteLine(('Ошибка: ' + $_.Exception.Message))
    exit 1
}

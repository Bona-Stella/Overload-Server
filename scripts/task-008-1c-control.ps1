[CmdletBinding()]
param(
  [Parameter(Mandatory=$true)]
  [ValidateSet('snapshot','decorate-owner','remove-settings','restore-settings','disconnect-next-read','revoke-session','stop')]
  [string]$Action,
  [string]$UserId = '',
  [ValidatePattern('^[a-zA-Z0-9-]*$')]
  [string]$Evidence = ''
)
# Test-only local file control; the running JVM owns the disposable Testcontainers DataSource.

$ErrorActionPreference = 'Stop'
function Invoke-Task008Command([string]$Action, [string]$UserId = '', [string]$Evidence = '') {
  $task008Directory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build\task-008-1c'))
  $task008ExistingResult = Join-Path $task008Directory 'result.json'
  $task008PreviousTicks = if (Test-Path -LiteralPath $task008ExistingResult) { (Get-Item -LiteralPath $task008ExistingResult).LastWriteTimeUtc.Ticks } else { 0 }
  $task008Command = @{ id=[Guid]::NewGuid().ToString(); action=$Action }
  if ($UserId) { $task008Command.userId=$UserId }
  $task008Temporary = Join-Path $task008Directory 'command.tmp'
  $task008Target = Join-Path $task008Directory 'command.json'
  $task008Command | ConvertTo-Json -Compress | Set-Content -LiteralPath $task008Temporary -Encoding utf8NoBOM
  Move-Item -LiteralPath $task008Temporary -Destination $task008Target
  for ($task008Attempt=0; $task008Attempt -lt 100; $task008Attempt++) {
    $task008ResultPath = Join-Path $task008Directory 'result.json'
    if ((Test-Path -LiteralPath $task008ResultPath) -and (Get-Item -LiteralPath $task008ResultPath).LastWriteTimeUtc.Ticks -gt $task008PreviousTicks) {
      $task008ResultText = Get-Content -LiteralPath $task008ResultPath -Raw -ErrorAction SilentlyContinue
      if (!$task008ResultText) { Start-Sleep -Milliseconds 100; continue }
      $task008Result = $task008ResultText | ConvertFrom-Json
      if ($task008Result.id -eq $task008Command.id) {
        if (!$task008Result.ok) { throw "Test command failed: $($task008Result.failureType)" }
        if ($Evidence) { Copy-Item -LiteralPath $task008ResultPath -Destination (Join-Path $task008Directory "$Evidence.json") }
        return $task008Result.snapshot
      }
    }
    Start-Sleep -Milliseconds 100
  }
  throw 'Timed out waiting for isolated backend control'
}

if ($UserId) { $null = [Guid]::Parse($UserId) }
$task008Ready = Join-Path ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\build\task-008-1c'))) 'ready.json'
if (!(Test-Path -LiteralPath $task008Ready)) { throw 'Isolated browser environment is not running' }
$task008Metadata = Get-Content -LiteralPath $task008Ready -Raw | ConvertFrom-Json
if ($task008Metadata.initialUsers -ne 0 -or $task008Metadata.clockType -ne 'java.time.Clock$SystemClock') { throw 'Expected an isolated system-clock environment' }
Invoke-Task008Command $Action $UserId $Evidence

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$mainOutput = Join-Path $root "target\classes"
$testOutput = Join-Path $root "target\test-classes"
New-Item -ItemType Directory -Force -Path $mainOutput, $testOutput | Out-Null

$mainFiles = Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object FullName
$testFiles = Get-ChildItem (Join-Path $root "src\test\java") -Recurse -Filter *.java | ForEach-Object FullName

javac --release 17 -d $mainOutput $mainFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
javac --release 17 -cp $mainOutput -d $testOutput $testFiles
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java -ea -cp "$mainOutput;$testOutput" com.matchbook.OrderBookSelfTest
exit $LASTEXITCODE

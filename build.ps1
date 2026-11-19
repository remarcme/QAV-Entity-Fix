param(
    [Parameter(Mandatory=$true)][string]$ServerDirectory,
    [string]$JdkBin = ""
)
$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$librariesDirectory = Join-Path $ServerDirectory 'libraries'
if (!(Test-Path -LiteralPath $librariesDirectory)) { throw 'The Paper server libraries directory is missing.' }
$classpath = ((Get-ChildItem -LiteralPath $librariesDirectory -Filter '*.jar' -Recurse).FullName -join ';').Replace('\','/')
$buildDirectory = Join-Path $projectDirectory 'build'
$classesDirectory = Join-Path $buildDirectory 'classes'
$testsDirectory = Join-Path $buildDirectory 'tests'
$distDirectory = Join-Path $projectDirectory 'dist'
New-Item -ItemType Directory -Force -Path $classesDirectory,$testsDirectory,$distDirectory | Out-Null
$javacTool = if ($JdkBin) { Join-Path $JdkBin 'javac.exe' } else { 'javac' }
$javaTool = if ($JdkBin) { Join-Path $JdkBin 'java.exe' } else { 'java' }
$jarTool = if ($JdkBin) { Join-Path $JdkBin 'jar.exe' } else { 'jar' }
function Invoke-JavaTool($tool, $arguments, $fileName) {
    $argumentFile = Join-Path $buildDirectory $fileName
    [IO.File]::WriteAllText($argumentFile, $arguments, [Text.UTF8Encoding]::new($false))
    & $tool "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { throw "$tool failed with exit code $LASTEXITCODE" }
}
$mainSource = (Join-Path $projectDirectory 'src/main/java/io/github/remarcme/qavfix/QAVEntityRepair.java').Replace('\','/')
$testSource = (Join-Path $projectDirectory 'src/test/java/io/github/remarcme/qavfix/QAVEntityRepairTest.java').Replace('\','/')
$classesDirectory = $classesDirectory.Replace('\','/')
$testsDirectory = $testsDirectory.Replace('\','/')
Invoke-JavaTool $javacTool "-encoding UTF-8 -cp `"$classpath`" -d `"$classesDirectory`" `"$mainSource`"" 'compile.args'
Invoke-JavaTool $javacTool "-encoding UTF-8 -cp `"$classpath;$classesDirectory`" -d `"$testsDirectory`" `"$testSource`"" 'test.args'
Invoke-JavaTool $javaTool "-cp `"$classpath;$classesDirectory;$testsDirectory`" io.github.remarcme.qavfix.QAVEntityRepairTest" 'run.args'
Copy-Item -LiteralPath (Join-Path $projectDirectory 'src/main/resources/plugin.yml') -Destination $classesDirectory
$noticeDirectory = Join-Path $classesDirectory 'META-INF'
New-Item -ItemType Directory -Force -Path $noticeDirectory | Out-Null
Copy-Item -LiteralPath (Join-Path $projectDirectory 'LICENSE') -Destination $noticeDirectory
$artifact = Join-Path $distDirectory 'QAVEntityRepair-1.1.0.jar'
& $jarTool --create --file $artifact -C $classesDirectory .
if ($LASTEXITCODE -ne 0) { throw 'JAR creation failed.' }
Write-Output "Built and tested: $artifact"

param([string]$Java = "java", [string]$Ecj = "lib\ecj.jar", [string]$Midp = "lib\midp.jar", [switch]$Fonts, [switch]$En)
# Builds IsraelMaps.jar / .jad.
#   -Java  java.exe of any JRE 8 or newer (default: the one on PATH)
#   -Ecj   the Eclipse batch compiler jar (ecj-*.jar from https://download.eclipse.org/eclipse/downloads/)
#   -Midp  a jar with the MIDP 2.0 / CLDC 1.1 API classes to compile against (for example freej2me.jar, or the
#          midpapi20.jar + cldcapi11.jar of a wireless toolkit merged into one jar)
#   -Fonts regenerate the bitmap fonts from the Windows fonts (needs Pillow); the generated fonts are in fonts\f
#   -En    also build the English edition
# Python 3 is needed for the packaging tools (python -I tools\...).
$m = Split-Path -Parent $MyInvocation.MyCommand.Path
if (-not (Test-Path "$m\stubbin\javax\microedition\location")) {
  & $Java -jar $Ecj -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -g:none -cp $Midp -d "$m\stubbin" (Get-ChildItem "$m\stub" -Recurse -Filter *.java | % FullName)
}
if (Test-Path "$m\bin") { Remove-Item -Recurse -Force "$m\bin" }
Write-Output "compile"
& $Java -jar $Ecj -source 1.8 -target 1.8 -encoding UTF-8 -nowarn -g:none -cp "$Midp;$m\stubbin" -d "$m\bin" (Get-ChildItem "$m\src\mp" -Filter *.java | % FullName)
if ($LASTEXITCODE -ne 0) { Write-Output "COMPILE FAILED"; exit 1 }
# CLDC 1.1 needs StackMap attributes in the class files (the "preverify" step)
python -I "$m\tools\cldc.py" "$m\bin"
if ($LASTEXITCODE -ne 0) { Write-Output "CLDC FAILED"; exit 1 }
if ($Fonts -or -not (Test-Path "$m\fonts\f\ur")) { python -I "$m\tools\mkfonts.py" "$m\fonts" "$m\tools" }
python -I "$m\tools\mkpal.py" "$m\pal"
python -I "$m\tools\mkicon.py" "$m\icon.png" "$m\icon-big.png"
python -I "$m\tools\mkjar.py" "$m\bin" "$m\fonts" "$m\icon.png" "$m\pal" "$m\IsraelMaps.jar" "$m\IsraelMaps.jad"
python -I "$m\tools\check.py" "$m\IsraelMaps.jar" | Select-Object -First 1
if ($En) { python -I "$m\tools\mkjar.py" "$m\bin" "$m\fonts" "$m\icon.png" "$m\pal" "$m\IsraelMaps-EN.jar" "$m\IsraelMaps-EN.jad" --en }

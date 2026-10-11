# Generates the audio fixtures used by run_e2e.py, plus fixtures/manifest.json holding each
# fixture's ground truth (who speaks each turn). Uses the two built-in Windows SAPI voices
# (Microsoft David = male, Microsoft Zira = female) and ffmpeg to join turns.
#
#   powershell -ExecutionPolicy Bypass -File e2e\make_fixtures.ps1 [-Ffmpeg <path to ffmpeg.exe>]

param([string]$Ffmpeg = $env:FFMPEG_PATH)
if (-not $Ffmpeg) { $Ffmpeg = "ffmpeg" }

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech
$out = Join-Path $PSScriptRoot "fixtures"
$tmp = Join-Path $out "_turns"
New-Item -ItemType Directory -Force $out, $tmp | Out-Null

$MALE = "Microsoft David Desktop"
$FEMALE = "Microsoft Zira Desktop"

function Speak([string]$voice, [string]$text, [string]$path, [string]$ssmlProsody = "") {
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $s.SelectVoice($voice)
    $s.SetOutputToWaveFile($path)
    if ($ssmlProsody) {
        $s.SpeakSsml("<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'><voice name='$voice'><prosody $ssmlProsody>$text</prosody></voice></speak>")
    } else {
        $s.Speak($text)
    }
    $s.Dispose()
}

# Joins turn WAVs with 0.8s of silence after each, resampled to 16kHz mono MP3.
function Join-Turns([string[]]$wavs, [string]$dest) {
    $ffArgs = @("-loglevel", "error", "-y")
    $filter = ""
    $labels = ""
    for ($i = 0; $i -lt $wavs.Count; $i++) {
        $ffArgs += @("-i", $wavs[$i])
        $filter += "[${i}:a]aresample=16000,apad=pad_dur=0.8[a$i];"
        $labels += "[a$i]"
    }
    $filter += "${labels}concat=n=$($wavs.Count):v=0:a=1[out]"
    $ffArgs += @("-filter_complex", $filter, "-map", "[out]", "-ac", "1", "-ar", "16000", "-b:a", "64k", $dest)
    & $Ffmpeg @ffArgs
    if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed building $dest" }
}

# Each conversation: list of @(role, voice, text). Roles are the ground truth the E2E checks against.
$conversations = [ordered]@{
    "conv_male_consultant_female_patient" = @(
        @("HEALTH_CONSULTANT", $MALE,   "Good morning. How have you been feeling since our last session?"),
        @("PATIENT",           $FEMALE, "Honestly, not great. I have been very anxious at work, I cannot sleep, and my chest feels tight before every meeting."),
        @("HEALTH_CONSULTANT", $MALE,   "That sounds exhausting. When did you last feel calm or happy?"),
        @("PATIENT",           $FEMALE, "Last weekend I visited my parents and looked at old photos from my childhood. I felt warm and nostalgic, I really miss those days."),
        @("HEALTH_CONSULTANT", $MALE,   "What happens when you come back to the city after a visit like that?"),
        @("PATIENT",           $FEMALE, "I feel sad and lonely again. Sometimes I just sit in my room and cry, and then I get frustrated with myself for being so weak."),
        @("HEALTH_CONSULTANT", $MALE,   "Thank you for sharing that. Let us try a short breathing exercise together.")
    )
    "conv_female_consultant_male_patient_patient_first" = @(
        @("PATIENT",           $MALE,   "Sorry I am late. Traffic was terrible and honestly I am still really angry about what happened at work today."),
        @("HEALTH_CONSULTANT", $FEMALE, "That is alright, take a moment. Can you tell me what happened?"),
        @("PATIENT",           $MALE,   "My manager blamed me in front of everyone for a mistake I did not make. I wanted to shout at him."),
        @("HEALTH_CONSULTANT", $FEMALE, "That sounds very unfair. How are you feeling right now, sitting here with me?"),
        @("PATIENT",           $MALE,   "A bit calmer now. Talking about it helps. I think I just need some peace and quiet this evening."),
        @("HEALTH_CONSULTANT", $FEMALE, "That is a healthy idea. Let us plan something relaxing for tonight.")
    )
    "conv_single_voice_both_roles" = @(
        @("HEALTH_CONSULTANT", $MALE, "Hello, how are you feeling today?"),
        @("PATIENT",           $MALE, "I feel very stressed and overwhelmed, there is too much pressure at home."),
        @("HEALTH_CONSULTANT", $MALE, "I understand. What part of it worries you the most?"),
        @("PATIENT",           $MALE, "Money. We have bills to pay and I cannot sleep at night thinking about it."),
        @("HEALTH_CONSULTANT", $MALE, "Let us talk about some small steps you can take this week."),
        @("PATIENT",           $MALE, "Okay. That would help, I think.")
    )
    "conv_single_turn" = @(
        ,@("PATIENT", $FEMALE, "I have been feeling really low and tired for the past few weeks.")
    )
}

$manifest = [ordered]@{ conversations = [ordered]@{}; voice = [ordered]@{} }
foreach ($name in $conversations.Keys) {
    $wavs = @()
    $roles = @()
    $i = 0
    foreach ($turn in $conversations[$name]) {
        $wav = Join-Path $tmp "$name-$i.wav"
        Speak $turn[1] $turn[2] $wav
        $wavs += $wav
        $roles += $turn[0]
        $i++
    }
    Join-Turns $wavs (Join-Path $out "$name.mp3")
    $manifest.conversations[$name] = [ordered]@{
        file = "$name.mp3"
        roles = $roles
        distinctVoices = (($conversations[$name] | ForEach-Object { $_[1] }) | Select-Object -Unique).Count
    }
}

# Single-speaker voice-mode clips. high/low arousal differ only in delivery (pitch, rate, volume), same voice.
Speak $FEMALE "Last weekend I visited my parents and looked at old photos from my childhood. I felt warm and nostalgic, I really miss those days." (Join-Path $out "voice_nostalgic.wav")
Speak $FEMALE "I cannot believe it, I got the job! This is the best day of my life, I am so excited!" (Join-Path $out "voice_high_arousal.wav") "pitch='+40%' rate='fast' volume='loud'"
Speak $FEMALE "I am sitting quietly by the window. Everything is slow and peaceful today." (Join-Path $out "voice_low_arousal.wav") "pitch='-20%' rate='slow' volume='soft'"
$manifest.voice = [ordered]@{
    nostalgic = "voice_nostalgic.wav"; high_arousal = "voice_high_arousal.wav"; low_arousal = "voice_low_arousal.wav"
    silence = "silence.wav"; not_audio = "not_audio.mp3"; empty = "empty.mp3"
}

# Edge-case files: 3s of digital silence, a text file disguised as .mp3, and a zero-byte file.
& $Ffmpeg -loglevel error -y -f lavfi -i "anullsrc=r=16000:cl=mono" -t 3 (Join-Path $out "silence.wav")
Set-Content -Path (Join-Path $out "not_audio.mp3") -Value "this is plain text, not audio" -Encoding ascii
New-Item -ItemType File -Force (Join-Path $out "empty.mp3") | Out-Null

Remove-Item -Recurse -Force $tmp
$manifest | ConvertTo-Json -Depth 5 | Out-File -Encoding utf8 (Join-Path $out "manifest.json")
Get-ChildItem $out | Select-Object Name, Length | Format-Table -AutoSize

#!/usr/bin/env bash
# 生成可控测试视频：每槽一个知识点（OCR=标题+关键词，ASR=TTS口播稿）
# 用法: gen_videos.sh <output.mp4> <slot_ids 如 "1-10"> <slot_seconds>
set -euo pipefail

OUT="$1"; SLOTSPEC="$2"; SLOT_SEC="${3:-120}"
TD="$(cd "$(dirname "$0")" && pwd)"
# knowledge_30.json 位于 ../data/（与本脚本所在 scripts/ 平级）
TDW=$(cygpath -m "$TD/../data")
FF="${FFMPEG:-ffmpeg}"
FONT="C\\:/Windows/Fonts/msyh.ttc"
WORK="$TD/work_$(basename "$OUT" .mp4)"
WORKW=$(cygpath -m "$WORK")
mkdir -p "$WORK"

# 解析槽 id 范围
START=${SLOTSPEC%-*}; END=${SLOTSPEC#*-}

# 1) TTS 每槽语音 (PowerShell System.Speech, 16kHz mono)
# 中文经 UTF-8 文件传递，避免管道编码乱码
ids=$(seq "$START" "$END")
for i in $ids; do
  if [ -s "$WORK/tts_$i.wav" ]; then continue; fi
  IDX0=$((i-1))
  powershell.exe -NoProfile -Command "(Get-Content -Raw -Encoding UTF8 '$TDW/knowledge_30.json' | ConvertFrom-Json).slots[$IDX0].speech" | tr -d '\r' > "$WORK/tts_$i.txt"
  powershell.exe -NoProfile -Command "
    Add-Type -AssemblyName System.Speech
    \$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    \$s.SelectVoice('Microsoft Huihui Desktop')
    \$s.Rate = 2
    \$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    \$s.SetOutputToWaveFile('$WORKW/tts_$i.wav', \$fmt)
    \$s.Speak((Get-Content -Raw -Encoding UTF8 '$WORKW/tts_$i.txt'))
    \$s.Dispose()"
  echo "TTS slot $i: $(du -h "$WORK/tts_$i.wav" | cut -f1)"
done

# 2) 每槽音频: TTS + 静音补齐到 SLOT_SEC (44.1k stereo)
# concat 列表内写相对路径（Windows ffmpeg 不认 /d/ 风格路径）
CONCAT="$WORK/list.txt"; : > "$CONCAT"
for i in $ids; do
  "$FF" -y -loglevel error -i "$WORK/tts_$i.wav" -af "apad" -t "$SLOT_SEC" -ar 44100 -ac 2 -c:a pcm_s16le "$WORK/seg_$i.wav"
  echo "file 'seg_$i.wav'" >> "$CONCAT"
done
(cd "$WORK" && "$FF" -y -loglevel error -f concat -safe 0 -i list.txt -c:a pcm_s16le audio_all.wav)

# 3) 画面: 白底 + 每槽 drawtext(标题+关键词)，写入 filter script 避免 Windows 命令行长度限制
TOTAL=$(( (END - START + 1) * SLOT_SEC ))
FILTERS="$WORK/filters.txt"; : > "$FILTERS"
IDX=0
for i in $ids; do
  IDX0=$((i-1))
  TITLE=$(powershell.exe -NoProfile -Command "(Get-Content -Raw -Encoding UTF8 '$TDW/knowledge_30.json' | ConvertFrom-Json).slots[$IDX0].title" | tr -d '\r')
  KWS=$(powershell.exe -NoProfile -Command "((Get-Content -Raw -Encoding UTF8 '$TDW/knowledge_30.json' | ConvertFrom-Json).slots[$IDX0].keywords) -join '  '" | tr -d '\r')
  T0=$(( IDX * SLOT_SEC )); T1=$(( T0 + SLOT_SEC ))
  ESC_TITLE=$(printf '%s' "$TITLE" | sed 's/\\/\\\\/g; s/:/\\:/g')
  ESC_KWS=$(printf '%s' "$KWS" | sed 's/\\/\\\\/g; s/:/\\:/g')
  cat >> "$FILTERS" <<EOF
drawtext=fontfile='$FONT':text='$ESC_TITLE':fontcolor=black:fontsize=64:x=round((w-text_w)/2):y=round(h*0.3):enable='between(t,$T0+0.1,$T1-0.6)',
drawtext=fontfile='$FONT':text='$ESC_KWS':fontcolor=0x333333:fontsize=56:x=round((w-text_w)/2):y=round(h*0.58):enable='between(t,$T0+0.1,$T1-0.6)',
EOF
  IDX=$((IDX+1))
done
# 去掉最后一个逗号换行
sed -i '$ s/,$//' "$FILTERS"
printf "color=c=white:s=1280x720:r=2:d=%d,format=yuv420p,%s" "$TOTAL" "$(cat "$FILTERS" | tr -d '\n')" > "$FILTERS.final"

"$FF" -y -loglevel error -filter_complex_script "$FILTERS.final" -c:v libx264 -preset veryfast -crf 16 -t "$TOTAL" "$WORK/video_noaudio.mp4"

# 4) 合流
"$FF" -y -loglevel error -i "$WORK/video_noaudio.mp4" -i "$WORK/audio_all.wav" -c:v copy -c:a aac -b:a 96k -shortest "$OUT"
echo "DONE: $OUT  $(du -h "$OUT" | cut -f1)  时长 ${TOTAL}s"

#!/usr/bin/env python3
"""Create deterministic media for decoded-audio subtitle synchronization tests."""
from pathlib import Path
import math,os,random,struct,subprocess,tempfile,wave
root=Path(__file__).resolve().parent.parent
assets=root/'tests/assets'
rng=random.Random(29471)
segments=[];at=2.0
while at<175:
    length=rng.uniform(.65,2.6)
    segments.append((at,min(178,at+length)))
    at+=length+rng.uniform(.45,2.4)
def stamp(seconds):
    ms=round(seconds*1000)
    return f'{ms//3600000:02}:{ms//60000%60:02}:{ms//1000%60:02},{ms%1000:03}'
(assets/'sync-offset.srt').write_text('\n\n'.join(f'{i+1}\n{stamp(a+2.3)} --> {stamp(b+2.3)}\nSynchronization test line {i+1}.' for i,(a,b) in enumerate(segments))+'\n')
with tempfile.TemporaryDirectory() as temp:
    wav=Path(temp)/'speech-pattern.wav';rate=16000
    with wave.open(str(wav),'wb') as output:
        output.setnchannels(1);output.setsampwidth(2);output.setframerate(rate)
        segment=0
        for second in range(185):
            block=bytearray()
            for n in range(rate):
                t=second+n/rate
                while segment<len(segments) and t>=segments[segment][1]:segment+=1
                active=segment<len(segments) and segments[segment][0]<=t<segments[segment][1]
                x=int(10000*(math.sin(t*2*math.pi*743)+.25*math.sin(t*2*math.pi*1373))) if active else 0
                block.extend(struct.pack('<h',x))
            output.writeframes(block)
    subprocess.run([os.environ.get('LUMATV_FFMPEG','ffmpeg'),'-hide_banner','-loglevel','error','-y','-f','lavfi','-i','color=c=0x172238:s=160x90:r=24:d=185','-i',str(wav),'-c:v','libx264','-preset','ultrafast','-crf','32','-pix_fmt','yuv420p','-c:a','aac','-b:a','32k','-shortest',str(assets/'sync-pattern.mkv')],check=True)
print('Created a 185-second deterministic AV fixture and shifted subtitles.')

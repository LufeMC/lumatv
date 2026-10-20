#!/usr/bin/env python3
"""Run production data logic locally. Build the application first."""
from pathlib import Path
import hashlib,os,subprocess,urllib.request
repo=Path(__file__).resolve().parent.parent
java=Path(os.environ.get('JAVA_HOME','/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))/'bin'
sdk=Path(os.environ.get('ANDROID_HOME','/opt/homebrew/share/android-commandlinetools'))
android=sdk/'platforms/android-36/android.jar'
folder=repo/'out/host';folder.mkdir(parents=True,exist_ok=True)
cache=repo/'.test-libs';cache.mkdir(exist_ok=True)
jar=cache/'android-json.jar'
expected='dfb7bae2f404cfe0b72b4d23944698cb716b7665171812a0a4d0f5926c0fac79'
if not jar.exists():
 url='https://repo.maven.apache.org/maven2/com/vaadin/external/google/android-json/0.0.20131108.vaadin1/android-json-0.0.20131108.vaadin1.jar'
 jar.write_bytes(urllib.request.urlopen(url,timeout=20).read())
if hashlib.sha256(jar.read_bytes()).hexdigest()!=expected:raise SystemExit('The test JSON dependency checksum does not match.')
classes=folder/'classes';classes.mkdir(exist_ok=True)
cp=os.pathsep.join(map(str,[jar,classes,repo/'out/classes',android,*sorted((repo/'out/player/jars').glob('*.jar'))]))
subprocess.run([str(java/'javac'),'--release','8','-cp',cp,'-d',str(classes),str(repo/'tests/host/HostLogicTests.java')],check=True)
subprocess.run([str(java/'java'),'-cp',cp,'io.freemytv.HostLogicTests'],check=True)

#!/usr/bin/env python3
"""Optional Android framework smoke harness. Uses the same signing key as the target release."""
from pathlib import Path
from zipfile import ZipFile
import argparse
import os
import shutil
import subprocess

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--output',required=True)
args=p.parse_args()
root=Path(__file__).resolve().parent
out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
sdk=Path(os.environ.get('ANDROID_SDK_ROOT') or os.environ['ANDROID_HOME'])
tools=sdk/'build-tools/35.0.0';jar=sdk/'platforms/android-35/android.jar'
java=Path(os.environ['JAVA_HOME'])/'bin'
for name in ['BGFN_KEYSTORE','BGFN_STORE_PASSWORD','BGFN_KEY_PASSWORD','BGFN_KEY_ALIAS']:
 if not os.environ.get(name):raise SystemExit('Set '+name+' (same key as target release).')
def run(*cmd):
 subprocess.run([str(s) for s in cmd],check=True)
classes=out/'classes';classes.mkdir(exist_ok=True)
dex=out/'dex';dex.mkdir(exist_ok=True)
run(java/'javac','--release','8','-cp',jar,'-d',classes,*root.glob('*.java'))
run(tools/'d8','--min-api','23','--lib',jar,'--output',dex,*classes.rglob('*.class'))
raw=out/'raw.apk'
run(tools/'aapt','package','-f','-M',root/'AndroidManifest.xml','-I',jar,'-F',raw,*(['-A',os.environ['BGFN_UI_TEST_ASSETS']] if os.environ.get('BGFN_UI_TEST_ASSETS') else []))
with ZipFile(raw,'a') as z:z.write(dex/'classes.dex','classes.dex')
aligned=out/'aligned.apk';dest=out/'BGFN-validation.apk'
run(tools/'zipalign','-f','-p','4',raw,aligned)
run(tools/'apksigner','sign','--ks',os.environ['BGFN_KEYSTORE'],'--ks-key-alias',os.environ['BGFN_KEY_ALIAS'],'--ks-pass','env:BGFN_STORE_PASSWORD','--key-pass','env:BGFN_KEY_PASSWORD','--v1-signing-enabled','true','--v2-signing-enabled','true','--v3-signing-enabled','false','--v4-signing-enabled','false','--out',dest,aligned)
run(tools/'apksigner','verify',dest)
print(dest)

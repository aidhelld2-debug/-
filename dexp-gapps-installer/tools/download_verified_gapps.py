#!/usr/bin/env python3
"""Create an Android TV side-load installer from verified public source APKs.

Original Google APKs stay byte-for-byte unchanged, including signatures.
Only the wrapper app is built/signed by Gradle.
"""
from pathlib import Path
import hashlib
import io
import json
import os
import shutil
import zipfile
import requests

OUT=Path('dexp-gapps-installer/app/src/main/assets')
EXPECTED_ACCOUNT='3324ee2f67d23848ae3d9086f20ada10f2cb0dbb6d202067b918cc4fb4d167be'

def get(url, limit=400*1024*1024):
    with requests.get(url,timeout=90,stream=True) as response:
        response.raise_for_status()
        content=io.BytesIO()
        for chunk in response.iter_content(1024*1024):
            content.write(chunk)
            if content.tell()>limit:raise ValueError('Download size limit exceeded')
        return content.getvalue()

def save(name, blob):
    dst=OUT/name
    dst.parent.mkdir(parents=True,exist_ok=True)
    dst.write_bytes(blob)
    if not zipfile.is_zipfile(dst):raise ValueError('not APK/zip: '+str(dst))
    print('ASSET',dst,len(blob),hashlib.sha256(blob).hexdigest(),flush=True)

api='https://api.github.com/repos/mrhaydendp/Fire-Tools/releases/tags/25.09.1'
info=requests.get(api,timeout=30).json()
assert info.get('tag_name')=='25.09.1',info
assets=[a for a in info['assets'] if a['name']=='Fire-Tools.zip']
assert len(assets)==1
release=get(assets[0]['browser_download_url'])
print('Fire Tools archive',len(release),hashlib.sha256(release).hexdigest())
with zipfile.ZipFile(io.BytesIO(release)) as pack:
    lookup={
      'gsf':'Fire-Tools/Gapps/Google Services Framework 10-6494331.apk',
      'gms':'Fire-Tools/Gapps/Google Play Services 24.35.30.apkm',
      'store':'Fire-Tools/Gapps/Google Play Store 42.6.23-23.apkm'
    }
    save('gsf/gsf.apk',pack.read(lookup['gsf']))
    splits={
       'gms':['base.apk','split_config.xxxhdpi.apk','split_config.en.apk','split_config.ru.apk'],
       'store':['base.apk','split_config.armeabi_v7a.apk','split_phonesky_data_loader.apk',
                'split_phonesky_data_loader.config.armeabi_v7a.apk',
                'split_config.en.apk','split_config.ru.apk']
    }
    for group,files in splits.items():
        with zipfile.ZipFile(io.BytesIO(pack.read(lookup[group]))) as src:
            for name in files:
                if name not in src.namelist():raise KeyError('Missing split: '+group+'/'+name)
                save(group+'/'+name,src.read(name))

url='https://raw.githubusercontent.com/Sajedur0/FRP/refs/heads/main/Google_Account_Manager_7.1.2.apk'
account=get(url,limit=10*1024*1024)
sha=hashlib.sha256(account).hexdigest()
if sha!=EXPECTED_ACCOUNT:
    raise ValueError('AccountManager SHA-256 does not match APKMirror: '+sha)
save('account/account.apk',account)
print('All four original Google packages prepared for DEXP armeabi-v7a.')

"""Portable official distributions; downloads are verified before extraction."""
from pathlib import Path
import subprocess, hashlib, zipfile, concurrent.futures, json

ROOT=Path(__file__).resolve().parents[1]
DEST=ROOT/'.tools'; DL=DEST/'downloads';DL.mkdir(parents=True,exist_ok=True)
def download(name,url,size,parts,expected,algorithm):
    target=DL/(name+'.zip')
    if target.exists() and target.stat().st_size==size:
        digest=hashlib.new(algorithm,target.read_bytes()).hexdigest()
        if digest==expected:
            print(name,'already verified',flush=True)
            extract(name,target);return
    span=(size+parts-1)//parts
    def part(n):
        start=n*span;end=min(size-1,start+span-1);file=DL/f'{name}.part{n:02}'
        if not file.exists() or file.stat().st_size!=end-start+1:
            subprocess.run(['curl.exe','-fsSL','--retry','3','--max-time','240','--range',f'{start}-{end}','-o',str(file),url],check=True)
        if file.stat().st_size!=end-start+1:raise RuntimeError(f'{name} range length mismatch {n}')
        print(name,'part',n+1,'/',parts,flush=True)
        return file
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(parts,16)) as pool:files=list(pool.map(part,range(parts)))
    with target.open('wb') as out:
        for f in files:
            with f.open('rb') as inp:
                while block:=inp.read(1024*1024):out.write(block)
    if hashlib.new(algorithm,target.read_bytes()).hexdigest()!=expected:raise RuntimeError(name+' checksum mismatch')
    extract(name,target)
def extract(name,target):
    with zipfile.ZipFile(target) as z:z.extractall(DEST)
    print(name,'extracted',flush=True)
if __name__=='__main__':
    download('node','https://nodejs.org/dist/v22.23.3/node-v22.23.3-win-x64.zip',35574076,4,'2b0ff57b049cda1bbcea2240eec20467018713c1efe1f7360c2681859b90ed71','sha256')
    # Apache Maven Central publishes MD5 alongside the immutable distribution.
    download('maven','https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip',9278421,4,'db835acd6c5f6f4e18f77c7cfd19035e','md5')
    download('mysql','https://cdn.mysql.com/Downloads/MySQL-8.4/mysql-8.4.11-winx64.zip',281191914,32,'2e833921898a9a030ea6bfe81bd811bc','md5')

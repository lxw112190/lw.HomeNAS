"""Read back Unicode console cells in an isolated hidden console, plus UTF-8 pipe/file checks.
Does not change the user's console, install a service, or claim a Server 2012 R2 VM test.
"""
import argparse
import ctypes
from ctypes import wintypes as w
import json
import msvcrt
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import requests
from smoke import Harness, ROOT


class Coord(ctypes.Structure):
    _fields_=[('x',w.SHORT),('y',w.SHORT)]


def worker(exe,report):
    k=ctypes.WinDLL('kernel32',use_last_error=True)
    signatures={
        'CreateConsoleScreenBuffer':(w.HANDLE,[w.DWORD,w.DWORD,ctypes.c_void_p,w.DWORD,ctypes.c_void_p]),
        'SetConsoleScreenBufferSize':(w.BOOL,[w.HANDLE,Coord]),
        'SetConsoleCursorPosition':(w.BOOL,[w.HANDLE,Coord]),
        'FillConsoleOutputCharacterW':(w.BOOL,[w.HANDLE,w.WCHAR,w.DWORD,Coord,ctypes.POINTER(w.DWORD)]),
        'ReadConsoleOutputCharacterW':(w.BOOL,[w.HANDLE,w.LPWSTR,w.DWORD,Coord,ctypes.POINTER(w.DWORD)]),
        'SetConsoleOutputCP':(w.BOOL,[w.UINT]),'SetConsoleCP':(w.BOOL,[w.UINT]),
        'GetConsoleOutputCP':(w.UINT,[]),'GetConsoleCP':(w.UINT,[]),
    }
    for name,(result,args) in signatures.items():
        fn=getattr(k,name);fn.restype=result;fn.argtypes=args
    handle=k.CreateConsoleScreenBuffer(0xc0000000,3,None,1,None)
    if handle==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
    screen=os.fdopen(msvcrt.open_osfhandle(handle,os.O_WRONLY|os.O_BINARY),'wb',buffering=0)
    size=Coord(200,200);assert k.SetConsoleScreenBufferSize(handle,size)
    def reset():
        used=w.DWORD();assert k.FillConsoleOutputCharacterW(handle,' ',40000,Coord(0,0),ctypes.byref(used))
        assert k.SetConsoleCursorPosition(handle,Coord(0,0))
    def cells():
        buffer=ctypes.create_unicode_buffer(40001);used=w.DWORD()
        assert k.ReadConsoleOutputCharacterW(handle,buffer,40000,Coord(0,0),ctypes.byref(used))
        return buffer[:used.value]
    h=Harness(exe)
    try:
        for cp in (437,936,65001):
            assert k.SetConsoleOutputCP(cp) and k.SetConsoleCP(cp)
            reset();result=subprocess.run([str(exe),'--invalid-option'],stdout=screen,stderr=screen,timeout=15)
            h.check(result.returncode!=0 and '启动失败：Usage:' in cells(),f'stderr Unicode console cells are correct at code page {cp}')
            h.check(k.GetConsoleOutputCP()==cp and k.GetConsoleCP()==cp,f'executable leaves caller code pages unchanged at {cp}')
        assert k.SetConsoleOutputCP(936) and k.SetConsoleCP(936)
        reset();h.process=subprocess.Popen([str(exe),'--config',str(h.config),'--allow-insecure-lan'],stdout=screen,stderr=screen,cwd=h.home)
        for _ in range(120):
            if h.process.poll() is not None:raise RuntimeError('Console test server exited')
            try:
                if requests.get(h.url+'server',timeout=.3).status_code==200:break
            except requests.RequestException:pass
            time.sleep(.1)
        else:raise TimeoutError('Console test server startup')
        output=cells()
        h.check(all(text in output for text in ('电脑管理：','数据目录：','默认管理员用户名：admin','按 Ctrl+C 停止服务；关闭浏览器不影响服务。')),'real server startup banner has correct Chinese console cells')
        h.check(str(h.home/'数据') in output,'Unicode data path is correct in console cells')
        h.stop()
        reset();chosen='ConsoleOnlyTest1234'
        result=subprocess.run([str(exe),'--config',str(h.config),'--initialize-admin'],input=(chosen+'\n'+chosen+'\n').encode('utf-8'),stdout=screen,stderr=screen,timeout=20)
        output=cells()
        h.check(result.returncode==0 and '请再次输入：' in output and '已设置管理员 admin 密码' in output,'interactive password prompts use Unicode console output')
        h.check(chosen not in output,'password prompt output does not echo redirected test input')
        # Real PowerShell 5.1 launch wrappers, confined to this disposable installation.
        stage=h.home/'带空格的 部署';(stage/'config').mkdir(parents=True);(stage/'deploy').mkdir()
        shutil.copy2(exe,stage/'NasServer.exe');shutil.copy2(ROOT/'deploy/service.ps1',stage/'deploy/service.ps1')
        config=json.loads(h.config.read_text(encoding='utf-8'));config.update(schema_version=999,data_dir='../data')
        (stage/'config/server.json').write_text(json.dumps(config),encoding='utf-8')
        for action in ('Console','AdminPassword'):
            reset()
            result=subprocess.run(['powershell.exe','-NoProfile','-ExecutionPolicy','Bypass','-File',str(stage/'deploy/service.ps1'),'-Action',action],stdout=screen,stderr=screen,timeout=25)
            h.check(result.returncode==1 and '启动失败：Unsupported configuration schema' in cells(),f'PowerShell 5.1 {action} wrapper preserves Unicode error and exit code')
        # Pipes/files are UTF-8 rather than the current console's 936 code page.
        bad=subprocess.run([str(exe),'--invalid-option'],capture_output=True,timeout=15)
        h.check(bad.returncode!=0 and bad.stderr.decode('utf-8').startswith('启动失败：'),'redirected stderr is strict UTF-8')
        h.extra_args=['--allow-insecure-lan'];h.start();h.stop()
        logs=sorted(h.home.glob('server-*.log'))
        h.check('电脑管理：' in logs[-1].read_text(encoding='utf-8'),'redirected real startup log is strict UTF-8')
        data={'count':len(h.checks),'checks':h.checks,'scope':'Windows 10 hidden console readback, real PowerShell 5.1, UTF-8 pipes/files; Server 2012 R2 physical test pending'}
        report.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    finally:
        h.stop();screen.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--exe',type=Path,default=ROOT/'out/Release/NasServer.exe');parser.add_argument('--worker',type=Path)
    args=parser.parse_args()
    if args.worker:
        try:worker(args.exe.resolve(),args.worker)
        except Exception:
            import traceback
            args.worker.with_suffix('.error.txt').write_text(traceback.format_exc(),encoding='utf-8');raise
    else:
        folder=ROOT/'test-results'/('console-'+str(time.time_ns()));folder.mkdir();report=folder/'PASS.json'
        startup=subprocess.STARTUPINFO();startup.dwFlags|=subprocess.STARTF_USESHOWWINDOW;startup.wShowWindow=0
        result=subprocess.run([sys.executable,str(Path(__file__).resolve()),'--exe',str(args.exe.resolve()),'--worker',str(report)],capture_output=True,creationflags=subprocess.CREATE_NEW_CONSOLE,startupinfo=startup,timeout=120)
        if result.returncode or not report.exists():
            failure=report.with_suffix('.error.txt');print(failure.read_text(encoding='utf-8') if failure.exists() else result.stderr.decode('utf-8',errors='replace'));sys.exit(1)
        data=json.loads(report.read_text(encoding='utf-8'));print(json.dumps(data));print('Evidence:',report)

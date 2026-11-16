# lw.HomeNAS

[简体中文](README.md) | English

Turn a Windows PC into a home backup server for photos, videos, documents and recordings. The server uses **C++17 and cpp-httplib**, with a browser management UI and an Android client built around native Java and trusted local WebView pages.

**Verified public release: [v1.4.0](https://github.com/lxw112190/lw.HomeNAS/releases/tag/v1.4.0)**, published by [CI39](https://github.com/lxw112190/lw.HomeNAS/actions/runs/43659828054), with the combined Windows ZIP, fixed-signature APK and SHA256 files. Android builds, emulator checks and full ZIP acceptance passed; target-phone testing remains necessary.

**Next release prepared here: 1.4.1 / Android versionCode 27 / native protocol 15.** This edition refreshes usage, folder-permission and upgrade documentation, retaining 1.4.0 functionality, package identity and signing. It becomes public only after the new commit passes CI. See [release notes](docs/20261115-v1.4.1发布说明.md).

## Features

- **Reliable uploads:** bounded streaming chunks, durable acknowledged offsets, pause/resume, SHA256 verification and content deduplication within the same accessible destination. A file is backed up only after the server confirms `READY`.
- **Android backup:** select authorized photos/videos by item, day or month; select all and exclude individual items; choose a destination folder. Upload concurrency defaults to two and is capped at three. Automatic backup follows Android scheduling and power/network restrictions.
- **Capture-time timeline:** valid EXIF `DateTimeOriginal` takes priority, followed by file modification time and finally import time. Old images are indexed in the background. Jump by year/month and filter an inclusive date range. Missing EXIF is never inferred from a filename.
- **Folders first:** the primary organization view shows location breadcrumbs, item counts, photo/video counts and total bytes. Cross-page selections support batch moves. Folders support up to 16 levels with inherited permissions; thematic albums remain optional references across folders, without duplicating originals.
- **Library backup and migration:** download a consistent SQLite/configuration/classification snapshot from the Web UI. Offline migration copies an entire instance to a new empty directory and verifies the database, relationships and every copied file. The source remains intact.
- **Easier phone setup:** scan a connection QR from the Web settings page, keep up to five recently connected servers, and see actionable connection failures. Scanning fills an address draft; it never logs in, trusts a certificate or redirects an existing queue automatically.
- **Preview and organization:** image zoom/pan, on-demand metadata, native Android audio/video playback, video thumbnails through bundled FFmpeg, rename, recycle/restore and Web batch ZIP export. Administrator-only permanent deletion requires explicit confirmation.
- **Accounts and folder access:** administrator, member and viewer roles. Folder permissions limit visibility and editing. Administrators retain access to all data; viewer accounts cannot gain write access through folder grants.

## Install

Requirements: Windows 10/11 x64 and Android 10 or later.

1. Download and extract the **complete Windows ZIP** from a Release. For test builds, download the Actions artifact named `lw.HomeNAS-full-ciN`.
2. Run `NasServer.exe` in the foreground, or run `deploy/install-service.bat` as administrator to install/start the Windows service.
3. Open `http://127.0.0.1:8787/`. A fresh deployment uses **admin / admin123**; change the password after signing in. Existing accounts are preserved on upgrade.
4. The service validates and publishes the bundled `client-release` APK at startup. Download `HomeNAS-<version>.apk` from the login page and install it on your phone.
5. In Web settings, display the phone connection QR. In the Android app, tap **Scan to connect**, test the connection, then enter your username and password.

The phone and PC normally use the same home network. HTTP LAN access and external Host acceptance default to enabled in deployment packages. HTTP is unencrypted. HTTPS remains available and requires normal certificate and hostname verification; scanning a QR does not bypass it.

### Service scripts

| Script under `deploy` | Purpose |
| --- | --- |
| `install-service.bat` | Install and start the configured service |
| `start-service.bat` | Start |
| `stop-service.bat` | Stop |
| `restart-service.bat` | Restart |
| `uninstall-service.bat` | Uninstall while retaining data |
| `open_firewall.bat` | Add the managed rules for configured ports |
| `remove_firewall.bat` | Remove those managed rules |

Installation diagnostics: `deploy/logs/service-install.log`. Runtime diagnostics: `data/logs/service.log`. Scripts do not silently overwrite an unrelated existing service. See [deployment documentation](deploy/README.md).

## Configuration

The default file is `config/server.json` next to the executable. Relative paths resolve against the **configuration directory**, not the process working directory.

```json
{
  "data_dir": "../data",
  "web_dir": "../web",
  "ffmpeg_path": "../tools/ffmpeg/ffmpeg.exe",
  "http_port": 8787,
  "https_port": 9443,
  "allow_insecure_lan": true
}
```

This is a configuration fragment. Edit the corresponding fields in the supplied complete file. Restart after changing ports and update the managed firewall rules as needed.

## Capture dates and folders

Open the library's **Timeline** view to jump by year/month or apply a date range. Both Web and APK use the same server-side ordering. EXIF offsets are respected; an EXIF timestamp without an offset is interpreted using the server's local timezone. The calendar displays an indexing notice while older photos are being processed. Refresh once indexing finishes. Photos without usable EXIF, videos and other files use file time, then import time.

The default organization entry is **Folders**. Moving between logical folders does not rewrite or duplicate the stored original. Files can have multiple folder references; a move changes the selected source association and retains other associations. Optional **Thematic albums** are for cross-folder topics; their deletion removes references, not original files. Advanced album actions are under **More organization** when selecting from the timeline.

## Back up and move a library

### Database and classification snapshot

As an administrator, open Web **Storage & settings → Library backup and migration → Back up database and classification**. A ZIP download contains a consistent SQLite snapshot, portable configuration and a checksum manifest. It preserves accounts and folder/album relations but **does not contain photo/video originals**. Login sessions and the plaintext bootstrap password are excluded. Keep these private backups out of public repositories.

### Full move to another disk or computer

Stop the service or foreground process first. Run from the installation directory in PowerShell:

```powershell
.\NasServer.exe --migrate-to 'D:\HomeNAS-New'
```

The destination must be empty and outside the source application/data directories. The command copies the executable, Web UI, deployment files, library data, originals and unfinished upload files. It omits runtime logs, old backup snapshots and the initial-password note. It does not remove the source or overwrite an existing library. Directory links are rejected.

The command checks SHA256 digests, SQLite integrity, classification relationships and original-file records before marking the destination complete. An interrupted or failed copy remains incomplete and cannot be started. For an additional check **before first startup**:

```powershell
.\NasServer.exe --verify-library 'D:\HomeNAS-New'
```

Then start the new `NasServer.exe`. To move to another PC, copy the **entire completed migration directory**, verify it on that PC, and start/install it there. Uninstall the old service before installing the service at its new location. Sign in again on clients; active login sessions are not migrated. The TLS certificate is preserved; if the new address is absent from its SAN, configure a matching certificate and verify it again on the phone. Hostname verification remains enabled. Keep the old directory until the new instance is verified. A custom configuration can be supplied with `--config`.

### Restore a metadata snapshot

Extract its ZIP, retain the original files in the current source library, stop the source instance, then create a separate restored instance:

```powershell
.\NasServer.exe --restore-backup 'E:\Snapshots\Extracted' --migrate-to 'D:\HomeNAS-Restored'
```

The snapshot restores the database/configuration and classifications while originals come from the current source data directory. Missing or changed originals fail verification; metadata alone cannot recreate a deleted photo. Unfinished tasks from a metadata snapshot must be retried, with files reselected if authorization has expired; use full migration to preserve resumable upload progress. Restoration never overwrites the current library. Verification manifests describe the copy at migration time; a used, changing library will no longer match that old snapshot byte for byte.

## Folder permissions

New root folders are private to their owner. Children inherit by default and can narrow access to selected users. Permissions are read, organize or manage; child grants cannot exceed the parent or the account role. Breadcrumbs, subtree moves, full backup destination paths and per-folder direct-file statistics are supported. Only empty folders can be deleted.

Existing folders retain legacy shared visibility on upgrade; the default folder stays compatible with older clients. Files referenced by several folders use the **intersection** of those folders' permissions, and albums never add access. Confirm visibility changes before moving files/folders, changing permissions or adding references. Administrators and people with direct server-disk access remain outside this privacy boundary; this is not encryption.

Revoked/deleted destinations stop transfers without switching them to the shared default. Durable offsets remain available when original access is restored. Selecting another target requires a new task. Deduplication is limited to the same accessible destination; repeated uploads to separate folders create independent records and stored originals to avoid exposing private content identities or filenames.

## Upgrade an existing installation

1. Stop the service or foreground process and keep a complete backup of the current configuration and data.
2. Replace application files, Web assets, deployment tools and the bundled `client-release` directory. Preserve the existing `config` and `data`; fresh-install defaults must not overwrite existing accounts or settings.
3. Start the server to migrate the catalog and publish the bundled APK. The 1.4 folder features require a 1.4.0 or newer server and the matching updated Android client; a Web-resource update alone cannot add the native bridge methods to an older APK.
4. Install the same-signature APK over the previous version. Accounts and queues are retained; the new APK returns to its builtin UI once. An inaccessible destination stops rather than falling back to the shared default.
5. Existing folders retain their old shared policy. Review membership before restricting access. Never reopen an upgraded catalog with an older server binary. Keep the old installation until the upgraded instance is verified.

## Build and CI

Install Visual Studio 2022 with C++ desktop tools and a Windows SDK. Build `lw.HomeNAS.sln` as Release/x64 or run:

```powershell
.\tools\build.ps1
python tools/check-version.py
python tests/smoke.py --exe build/app/Release/NasServer.exe
python tests/library_upgrade.py
```

Python and Node are development/test tools, not Windows server runtime requirements. Android is built in CI; no local Android SDK is required for server development.

The **Build APK, server and Release** workflow in `.github/workflows/android.yml` builds Android and Windows in parallel from the same commit. Emulator tests follow the APK build. Final assembly reuses the tested server executable and pinned FFmpeg, verifies the fixed-signature APK, and tests the real combined ZIP.

- `lw.HomeNAS-Android-N`: signed APKs and metadata.
- `lw.HomeNAS-server-ciN`: standalone Windows package, without an APK.
- **`lw.HomeNAS-full-ciN`: recommended combined deployment package.**

Main pushes generate artifacts. A previously unpublished `vVERSION` is published only after all required checks pass; public Releases are never overwritten. Manual `publish=false` builds artifacts only. Disabling emulator tests skips the combined package and Release. The four persistent Android signing Secrets remain unchanged.

## Limits and license

No iOS client, RAID, end-to-end encryption or universal video transcoding is included. Folder ACLs do not provide separate disk quotas or encryption against server administrators. Device codecs determine playback support. Metadata snapshots are not full media backups. Recycle/restore is not a substitute for an independent second copy. Android physical-device behavior still needs testing on the target phone.

Project license: [LICENSE](LICENSE). Third-party components, pinned source hashes and FFmpeg licensing/source information: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). QR generation uses Project Nayuki's MIT library; Android scanning uses ZXing Embedded under Apache-2.0.

## Contact and support

- Author: 天天代码码天天
- QQ: 819069052
- QQ Group: 天天代码码天天 | Group ID: 264292622

If this project helps you, you can support its maintenance:

<img src="assets/sponsor.jpg" alt="Support maintenance" width="220">

### Web folder-tree uploads (1.4.2)

Inside Photos & Files, use Upload to current folder or Authorization (directory managers only). Destination pickers expand and load folders by level. Selecting a local folder opens a tree with file/subtree checkboxes. Preserve original folders is enabled by default and includes the selected root folder; disabling it flattens the selection into the destination. Empty directories are omitted. The existing 16-level limit and ACLs apply; creating children requires parent management permission. Matching directories are reused, failures retain already queued files, and queued destinations stay fixed. Refreshing requires selecting the same files again to resume.

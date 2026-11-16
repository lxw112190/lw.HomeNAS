# lw.HomeNAS

[简体中文](README.md) | English

Turn a Windows PC into a home backup server for photos, videos, documents and recordings. The server uses **C++17 and cpp-httplib**, with a browser management UI and an Android client built around native Java and trusted local WebView pages.

**Current source: 1.4.5 · Android versionCode 31 · native protocol 15.** Download publicly available packages from [Releases](https://github.com/lxw112190/lw.HomeNAS/releases). Source version, successful CI builds and target-phone verification are separate states.

[Downloads](https://github.com/lxw112190/lw.HomeNAS/releases) · [Actions and APKs](https://github.com/lxw112190/lw.HomeNAS/actions) · [Web usage](#web-usage) · [Deployment](deploy/README.md) · [API](docs/API.md)

## Features

- **Reliable uploads:** bounded streaming chunks, durable acknowledged offsets, pause/resume, SHA256 verification and content deduplication within the same accessible destination. A file is backed up only after the server confirms `READY`.
- **Android backup:** select authorized photos/videos by item, day or month; select all and exclude individual items; choose a destination folder. Upload concurrency defaults to two and is capped at three. Automatic backup follows Android scheduling and power/network restrictions.
- **Capture-time timeline:** valid EXIF `DateTimeOriginal` takes priority, followed by file modification time and finally import time. Old images are indexed in the background. Jump by year/month and filter an inclusive date range. Missing EXIF is never inferred from a filename.
- **Web overview and search:** disk capacity, low-space indication, file-category counts, scoped task states and shortcuts. Search follows the current location; folder searches include descendants. Lists provide first, previous, next and last navigation.
- **Folders first:** the primary organization view shows location breadcrumbs, item counts, photo/video counts and total bytes. Cross-page selections support batch moves. Folders support up to 16 levels with inherited permissions; thematic albums remain optional references across folders, without duplicating originals.
- **Library backup and migration:** download a consistent SQLite/configuration/classification snapshot from the Web UI. Offline migration copies an entire instance to a new empty directory and verifies the database, relationships and every copied file. The source remains intact.
- **Easier phone setup:** scan a connection QR from the Web settings page, keep up to five recently connected servers, and see actionable connection failures. Scanning fills an address draft; it never logs in, trusts a certificate or redirects an existing queue automatically.
- **Preview and organization:** image zoom/pan, on-demand metadata with the full wrapping filename on Android, native Android audio/video playback, video thumbnails through bundled FFmpeg, rename, recycle/restore and Web batch ZIP export. Administrator-only permanent deletion requires explicit confirmation.
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

## Web usage

### Overview

The overview shows committed files, retained-original bytes, free disk space and unfinished tasks, with shortcuts to photos, videos, other files, folders, albums, trash and backup tasks. **Refresh overview** updates the statistics and recent files.

File and trash counts follow the current account's visibility. Administrators see task records for all accounts; other users see only their own. The disk bar includes other applications and files, so it is separate from NAS original-file usage. A warning appears below 5% free space. Completed task records do not mean every file on a phone has been backed up.

### Select and upload folders

1. Open a destination in **Photos & Files**, then select **Upload to current folder**. Alternatively, choose the destination from the expandable folder tree in Backup tasks.
2. Select individual files or a local folder. A local folder opens a tree where individual files or entire branches can be included or excluded.
3. **Preserve original folders** is enabled by default and includes the selected root folder: `Trip/Day1/a.jpg` goes under `Trip → Day1` inside the destination. Disable it to flatten selected files into that destination.
4. Confirm the selection and keep the page open. The server verifies the complete SHA256 before committing a file. After refreshing, reselect the same files to resume.

Empty directories are omitted. The existing 16-level limit applies, and creating children requires parent management permission. Matching directories are reused; inaccessible directories stop import while retaining already queued files. Changing the target affects new tasks only; queued tasks and retries keep their original target.

### Filename search and pagination

| Current location | Search scope |
| --- | --- |
| Root folder or album list | All accessible files |
| Inside a folder | That folder and all accessible descendants, recursively |
| Inside an album | Accessible files belonging to that album only |

Clear the query to return to normal browsing. The default folder is a specific folder, not the global root. Search never expands permissions. Trashed files stay out of the active library; the Trash view searches deleted items separately.

Library, folder, album, trash and upload-task lists provide **First / Previous / Next / Last** navigation. Jumps retain current filters and search. The server seeks the last page within those filters and permissions without downloading the whole catalog to the browser. Preview's previous/next file controls still use the currently loaded page only.

### System icons and album covers

Web folders use the Windows shell folder icon from the NAS host. Files without thumbnails use extension-associated shell icons where available; these depend on software installed on the **server**, not on the phone or browser PC. Icon lookup does not open uploaded originals.

A manually selected album cover takes priority. Otherwise, the first accessible, non-trashed photo in album display order is used. Empty albums and unavailable thumbnails show a placeholder.

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

## APK preview filenames

Open **File and capture information** to read the full filename in the first row. It wraps, supports long-press text selection, and updates after renaming. The top title retains its compact ellipsis layout so long names cannot crowd out the preview or close button. Expanded metadata scrolls independently.

## Folder permissions

On the Web, open a manageable folder to adjust its level:

- **Promote folder** moves it one level up: `A/B` becomes root-level `B`. Root folders cannot be promoted again.
- **Demote folder** selects a manageable sibling as the new parent: moving `A/B` under sibling `C` produces `A/C/B`.

Descendants move together, preserving folder IDs, originals and queued upload targets. The default folder cannot be relocated. Confirmation shows the destination and inherited-access implications. Name conflicts, insufficient permissions, cycles and nesting beyond 16 levels are rejected. Use **Authorization** on the Web or **Folder permissions** in the APK to manage access.

New root folders are private to their owner. Children inherit by default and can narrow access to selected users. Permissions are read, organize or manage; child grants cannot exceed the parent or the account role. Breadcrumbs, subtree moves, full backup destination paths and per-folder direct-file statistics are supported. Only empty folders can be deleted.

Existing folders retain legacy shared visibility on upgrade; the default folder stays compatible with older clients. Files referenced by several folders use the **intersection** of those folders' permissions, and albums never add access. Confirm visibility changes before moving files/folders, changing permissions or adding references. Administrators and people with direct server-disk access remain outside this privacy boundary; this is not encryption.

Revoked/deleted destinations stop transfers without switching them to the shared default. Durable offsets remain available when original access is restored. Selecting another target requires a new task. Deduplication is limited to the same accessible destination; repeated uploads to separate folders create independent records and stored originals to avoid exposing private content identities or filenames.

## Upgrade an existing installation

1. Stop the service or foreground process and keep a complete backup of the current configuration and data.
2. Replace application files, Web assets, deployment tools and the bundled `client-release` directory. Preserve the existing `config` and `data`; fresh-install defaults must not overwrite existing accounts or settings.
3. Start the server to migrate the catalog and publish the bundled APK. The 1.4 folder features require a 1.4.0 or newer server and the matching updated Android client; a Web-resource update alone cannot add the native bridge methods to an older APK.
4. The current matching client source is 1.4.5 / code31 / nativeApi15. Install the same-signature APK over the previous version. Accounts and queues are retained; the new APK returns to its builtin UI once. An inaccessible destination stops rather than falling back to the shared default.
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

| Trigger | Result |
| --- | --- |
| Push to `main` | Build, verify and upload Actions artifacts; no Release publication |
| Pull request | Compile and check; no distributed signed APK or Release |
| Manual run | Artifacts only; disabling `emulator` skips the combined ZIP; no Release |
| **Push a `v*` tag** | Publish an unpublished version only when the tag matches VERSION and the tested commit, after all required checks pass |

After committing the source:

```powershell
python tools/check-version.py
git push origin main
git tag -a v1.4.5 -m "Release v1.4.5"
git push origin v1.4.5
```

Lightweight and annotated tags are supported. Published releases and assets remain unchanged; do not reuse published tags. Assets are uploaded to a draft and made public only after verification. Failed jobs can be retried within the same run; expired artifacts require a fresh complete build.

Keep VERSION, CMake, the server version and Android versionName aligned, and increment versionCode for APK native or builtin-page changes. The four persistent Android signing Secrets remain unchanged: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Optional `UI_SIGNING_KEY_BASE64` signs UI resource bundles; otherwise the combined package uses the APK's builtin pages. Keep private keys and real library data out of Git.

## Limits and license

Version 1.4.5 has local Windows-build, synthetic-library regression and real-Chrome page checks, including APK production-page layouts with long filenames at 320px and in landscape. These do not establish cloud-release, Android-build, emulator or physical-device success; consult the corresponding CI run and test the target phone.

No iOS client, RAID, end-to-end encryption or universal video transcoding is included. Folder ACLs do not provide separate disk quotas or encryption against server administrators. Device codecs determine playback support. Metadata snapshots are not full media backups. Recycle/restore is not a substitute for an independent second copy. Android physical-device behavior still needs testing on the target phone.

Project license: [LICENSE](LICENSE). Third-party components, pinned source hashes and FFmpeg licensing/source information: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md). QR generation uses Project Nayuki's MIT library; Android scanning uses ZXing Embedded under Apache-2.0.

## Contact and support

- Author: 天天代码码天天
- QQ: 819069052
- QQ Group: 天天代码码天天 | Group ID: 264292622

If this project helps you, you can support its maintenance:

<img src="assets/sponsor.jpg" alt="Support maintenance" width="220">

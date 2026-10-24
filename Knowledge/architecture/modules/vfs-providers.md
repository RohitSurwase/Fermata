# Modules: smb / sftp / gdrive (network VFS providers)

These three dynamic-feature modules are thin provider shims that register a networked
`VirtualFileSystem` from `me.aap.utils.vfs` into Fermata's `FermataVfsManager`. The heavy lifting
lives in `depends/utils/src/main/java/me/aap/utils/vfs/{smb,sftp,gdrive}`.

## smb — `me.aap.fermata.vfs.smb` (1 file)
- `Provider.java` extends `me.aap.fermata.vfs.VfsProviderBase`. Builds a settings form
  (`HOST`, `PORT`=445, `SHARE`, `DOMAIN`, `USER`, `PASSWD`) and calls
  `SmbFileSystem.addRoot(user, host, port, "/"+share, passwd, null, null)`.
- Backing: `utils/vfs/smb/SmbFileSystem.java` + `SmbRoot.java` using **smbj** 0.15.0. Sessions are
  pooled (`SmbRoot.SessionPool`).

## sftp — `me.aap.fermata.vfs.sftp` (1 file)
- `Provider.java` (`VfsProviderBase`) collects host/port(22)/user/password/private-key/passphrase.
- Backing: `utils/vfs/sftp/SftpFileSystem.java` + `SftpRoot.java` using **jsch (mwiede fork)** 2.28.7
  and **BouncyCastle** 1.86 (registered as the top security provider on pre-O devices).
  `SftpRoot.SessionPool` pools `SftpSession`s (max `SFTP_MAX_SESSIONS`=3) with an idle timer and
  keep-alive.

## gdrive — `me.aap.fermata.vfs.gdrive` (1 file)
- `Provider.java` registers the Google Drive file system. **Only built when Google Services are
  enabled** (`settings.gradle` skips `:gdrive` otherwise).
- Backing: `utils/vfs/gdrive/GdriveFileSystem.java` + `GdriveFolder.java` using
  `google-api-services-drive` + `google-api-client-android` + Play Services auth.

## Credential handling — AUDIT RELEVANT
- Credentials collected by SMB/SFTP providers are persisted by
  `me.aap.utils.vfs.NetFileSystemBase.createRoot(...)`. Passwords and key passphrases are written to
  `SharedPreferences` using `encrypt()` which is **hex-encoded UTF-16, not real encryption**
  (self-labeled `// Weak encryption`, `NetFileSystemBase.java:323-330`). `decrypt()` is the inverse.
  See `04_critical_findings_and_audit.md §Security`.
- SFTP `UserInfo.promptYesNo(...)` returns `true` unconditionally (`SftpRoot.java`), i.e. unknown
  host keys are auto-accepted (no host-key verification / TOFU prompt). Audit finding.

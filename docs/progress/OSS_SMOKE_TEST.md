# OSS Smoke Test

This smoke test verifies that the `OssSourceStorageService` can write, read, and delete an object in a real Aliyun OSS bucket.

It is disabled by default so local and CI test runs do not require cloud credentials.

## Required Environment

Set these variables before running the smoke test:

```powershell
$env:KNOWSOURCE_OSS_SMOKE_ENABLED = "true"
$env:KNOWSOURCE_STORAGE_OSS_ENDPOINT = "https://your-bucket.oss-cn-hangzhou.aliyuncs.com"
$env:KNOWSOURCE_STORAGE_OSS_BUCKET = "your-bucket"
$env:KNOWSOURCE_STORAGE_OSS_ACCESS_KEY_ID = "..."
$env:KNOWSOURCE_STORAGE_OSS_ACCESS_KEY_SECRET = "..."
$env:KNOWSOURCE_STORAGE_OSS_KEY_PREFIX = "knowsource/smoke"
```

The access key needs OSS object permissions for the configured prefix:

- `PutObject`
- `GetObject`
- `DeleteObject`

## Run

```powershell
.\mvnw.cmd "-Dtest=OssSourceStorageSmokeTest" test
```

When `KNOWSOURCE_OSS_SMOKE_ENABLED` is not `true`, the test is skipped.

## Production Switch

Use the OSS storage adapter in the application by setting:

```powershell
$env:KNOWSOURCE_STORAGE_TYPE = "oss"
```

The multipart document upload path will then persist source files as `oss://bucket/key`.

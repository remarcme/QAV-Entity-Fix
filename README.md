# QAV Entity Repair

Fixes broken vehicle interactions and leftover models caused by stale entity references after server restarts or chunk reloads.

Requires **QualityArmory Vehicles 2.5.1**. Tested on **Paper 26.3**. Other QAV versions are not supported.

## Download

[Download version 1.1.0](https://github.com/remarcme/QAV-Entity-Fix/raw/refs/heads/main/downloads/QAVEntityRepair-1.1.0.jar)

## Installation

1. Stop the server.
2. Put the JAR in the `plugins` folder.
3. Start the server.

Test driving, pickup and a server restart before using it more widely.

## Details

Repairs driver, model and passenger references using matching entity UUIDs. Adds no entities and runs no repeating tick task. It does not remove existing orphaned models.

Use `/qavrepair` to check loaded vehicles and view repair totals. Requires operator access or `qaventityrepair.admin`.

## Build

Using your Paper server's libraries and the matching JDK:

```powershell
./build.ps1 -ServerDirectory 'C:/path/to/server' -JdkBin 'C:/path/to/jdk/bin'
```

The build runs the tests and creates `dist/QAVEntityRepair-1.1.0.jar`.

Unofficial plugin. QAV is not included. [MIT License](LICENSE).

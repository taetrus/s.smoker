# WP-04 Windows test

Date: 2026-11-22

Procedure: `docs/wp-04-windows-check.md`. Script: `docs/wp-04-windows-check.ps1`.

The check ran two times. The first check found a problem with the `jre8` run. PR #13 corrected it.
The second check passed. The first result stays below, in section "Before PR #13".

## Environment

- OS: Windows 11 Pro 10.0.26300.
- Shell: PowerShell 5.1.26100.10068.
- Build: Maven 3.9.6, JDK 25.0.1 (Eclipse Adoptium). `mvn -B package -DskipTests`: BUILD SUCCESS.
- JRE 8: `C:\Program Files\Java\jdk1.8.0_341\bin\java.exe`, 1.8.0_341.
- JRE 17: `C:\Users\Karat\.jdks\temurin-17.0.5\bin\java.exe`, Temurin 17.0.5+8.
  `C:\Program Files` has no Java 17. Step 3 of the procedure found only JRE 8 there.
- Commit of the second check: `1bd82bf` (branch `wp-04-windows-check` with `master` at `bc20a1b`, PR #13 merged).
- Commit of the first check: `7b5ef6a` (on top of `b698343`, WP-04 merged in PR #11).
- Applications: spike.master and p2.product of `p.osgi.ds`. No product with the hardware simulator was available.

## Runs (second check, after PR #13)

Each `--out` folder is `C:\smoke\out\wp04-<run>`. The console output is in `C:\smoke\out\wp04-<run>.txt`.

| Run | Command | Expected result | Actual result | Result |
|---|---|---|---|---|
| `jre8` | spike.master, JRE 8, `crash.json` | `startup` FAIL with 28 `stdout` hits: 14 `FrameworkEvent ERROR`, 14 `was not resolved`. s001 does not run. CLI exit code 1. | As expected. 28 `stdout` hits: 14 `FrameworkEvent ERROR`, 14 `was not resolved`. s001 did not run. CLI exit code 1. | Pass |
| `crash17` | spike.master, JRE 17, `crash.json` | `startup` PASS. s001 ERROR. Run error `the application exited during step s001; exit code 7`. s001 has a hit with `crashing on purpose`. `currentStep` s001. CLI exit code 1. | As expected. The CLI output and `report.html` give `the application exited during step s001; exit code 7`. `run-result.json` gives `the application exited during step s001`: the CLI adds the exit code in its report, not in the file (`README.md`). | Pass |
| `hang17` | spike.master, JRE 17, `hang.json` | `startup` PASS. s001 HANG. The thread dump shows `AWT-EventQueue`. CLI exit code 1. | As expected. s001: `action did not complete within 3000 ms`. The `threadDump` of s001 contains `AWT-EventQueue-0`. | Pass |
| `pilot` | p2.product, JRE 8, `pilot.json`, no `--log` | PASS. No step has a hit. CLI exit code 0. | As expected. Steps `startup` and s001 to s005 PASS, 0 hits. | Pass |
| `approve` | `approve` on the empty folder `wp04-empty` | `approve: no run-result.json in <folder>`. No stack trace. Exit code 2. | As expected. `approve: no run-result.json in C:\smoke\out\wp04-empty`, exit code 2. The Java process writes no stack trace. The other lines in `wp04-approve.txt` come from PowerShell 5.1: it wraps each stderr line of a native command in a `NativeCommandError` record. | Pass |

### Extra `jre8` runs

I did the `jre8` run 2 more times with the same command. `--out` folders: `C:\smoke\out\wp04-jre8-r4` and `-r5`.

| Run | `startup` | Hits | `FrameworkEvent ERROR` | `was not resolved` | Interleaved `!ENTRY` lines in `app-output.log` |
|---|---|---|---|---|---|
| `wp04-jre8` | FAIL | 28 | 14 | 14 | 0 |
| `wp04-jre8-r4` | FAIL | 28 | 14 | 14 | 0 |
| `wp04-jre8-r5` | FAIL | 28 | 14 | 14 | 1 |

Run `r5` shows the correction. The console output still has an interleaved `!ENTRY` line, but the agent reads the entry:

```
!ENTRY org.eclipse.osgi 4 0 16:40:14.677 [AWT-EventQueue-0] INFO  com.kk.pde.ds.spike.master.MasterApp - App-1 building 2 frame(s); ...
2026-11-22 16:40:14.677
!MESSAGE Bundle initial@reference:file:org.eclipse.core.jobs_3.15.600.v20250513-1234.jar was not resolved.
```

`app-output.log` is the console output of the application. The interleaving is in the application output. The agent
keeps the lines of each thread apart (PR #13), so its hits are correct.

## Before PR #13 (first check, commit `7b5ef6a`)

4 of 5 runs passed. `crash17`, `hang17`, `pilot` and `approve` gave the same results as in the second check.
The `jre8` run failed.

| Run | Expected result | Actual result | Result |
|---|---|---|---|
| `jre8` | `startup` FAIL with 28 `stdout` hits: 14 `FrameworkEvent ERROR`, 14 `was not resolved`. s001 does not run. CLI exit code 1. | `startup` FAIL with 27 `stdout` hits: 14 `FrameworkEvent ERROR`, 13 `was not resolved`. The hit for `org.eclipse.core.jobs ... was not resolved` was missing. s001 did not run. CLI exit code 1. | **Fail** |

The second check wrote over `C:\smoke\out\wp04-jre8`. The extra runs of the first check are in
`C:\smoke\out\wp04-jre8-r1`, `-r2` and `-r3`.

Cause: two threads of the application write to the console at the same time. Equinox writes the `!ENTRY` line in parts.
A Logback line of `AWT-EventQueue-0` goes in between the parts:

```
!ENTRY org.eclipse.osgi 4 0 15:58:44.333 [AWT-EventQueue-0] INFO  com.kk.pde.ds.spike.master.MasterApp - App-1 building 2 frame(s); ...
2026-11-22 15:58:44.333
!MESSAGE Bundle initial@reference:file:org.eclipse.core.jobs_3.15.600.v20250513-1234.jar was not resolved.
```

The `!ENTRY` line had no date, so the agent did not read this entry as an Equinox entry. The startup step still gave FAIL.

The result changed from run to run. I did the `jre8` run 3 more times with the same command (no code change):

| Run | Hits | `FrameworkEvent ERROR` | `was not resolved` | Interleaved `!ENTRY` lines |
|---|---|---|---|---|
| `wp04-jre8` | 27 | 14 | 13 | 1 |
| `wp04-jre8-r1` | 27 | 14 | 13 | 1 |
| `wp04-jre8-r2` | 27 | 14 | 13 | 1 |
| `wp04-jre8-r3` | 28 | 14 | 14 | 0 |

In each run, the number of missing hits was equal to the number of interleaved `!ENTRY` lines.
In a run with only one Equinox entry, this could hide the error. PR #13 makes the console tee of the agent keep the
lines of each thread apart.

## Not tested

- F-02 on a product with the hardware simulator.
- JRE 11 at run time on a target application.

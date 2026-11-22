# WP-04 Windows check (procedure for Claude Code)

This procedure is for Claude Code on the Windows PC. It does the last part of the WP-04 completion criterion (`docs/development-plan.md`, WP-04): the runs on the pilot applications. Then it writes the test report and opens a pull request.

WP-04 is merged (PR #11). The Windows CI passed. The pilot runs are open.

## Rules

- Do the steps in the given order.
- Use the desktop session of the owner. Do not run the steps as a service.
- Do not touch the keyboard or the mouse while a run is in progress. The application windows take the focus.
- The Bash tool of Claude Code is Git Bash. Run each PowerShell command with `powershell.exe -NoProfile -Command "<command>"`, or run the script of step 4 with `powershell.exe -File`.
- Do not change files in `D:\dev\p.osgi.ds`. Do not change the scenario files in `C:\smoke`.
- Commit with the noreply address `6750412+taetrus@users.noreply.github.com`. Check it with `git config user.email` before the first commit.
- If a result is different from the expected result, do not change code. Record the result in the report. Keep the `--out` folder of that run.

## Procedure

1. Go to the s.smoker clone (for example `/d/dev/s.smoker`), and get the procedure branch:
   ```bash
   git fetch origin
   git checkout wp-04-windows-check
   git pull
   ```
2. Build the jars, and copy them to `C:\tools`:
   ```bash
   mvn -B package -DskipTests
   cp smoker-agent/target/smoker-agent.jar smoker-cli/target/smoker-cli.jar /c/tools/
   ```
   Expected: `BUILD SUCCESS`.
3. Find the `java.exe` of Java 17:
   ```bash
   powershell.exe -NoProfile -Command "Get-ChildItem 'C:\Program Files' -Filter java.exe -Recurse -ErrorAction SilentlyContinue | ForEach-Object { \$_.FullName + ' ' + (& \$_.FullName -version 2>&1 | Select-Object -First 1) }"
   ```
   Select the path whose version starts with `17`. If no Java 17 is installed, stop and tell the owner.
4. Run the 5 runs. Replace `<JAVA17>` with the path from step 3:
   ```bash
   powershell.exe -NoProfile -ExecutionPolicy Bypass -File docs/wp-04-windows-check.ps1 -Java17 "<JAVA17>"
   ```
   The script takes approximately 5 minutes. It writes the console output of each run to `C:\smoke\out\wp04-<run>.txt`, and the exit codes to `C:\smoke\out\wp04-summary.txt`.
5. Read each output file and the `run-result.json` of each run. Compare each result with the table:

   | Run | Output file | Expected result |
   |---|---|---|
   | `jre8`: spike.master, JRE 8, `crash.json` | `wp04-jre8.txt`, `wp04-jre8\crash\run-result.json` | Step `startup` is FAIL. It has 28 hits with source `stdout`: 14 with `FrameworkEvent ERROR`, 14 with `was not resolved`. Step s001 did not run. CLI exit code 1. |
   | `crash17`: spike.master, JRE 17, `crash.json` | `wp04-crash17.txt`, `wp04-crash17\crash\run-result.json` | Step `startup` is PASS. Step s001 is ERROR. The run error is `the application exited during step s001; exit code 7`. s001 has a hit with `crashing on purpose`. `currentStep` is `s001`. CLI exit code 1. |
   | `hang17`: spike.master, JRE 17, `hang.json` | `wp04-hang17.txt`, `wp04-hang17\hang\run-result.json` | Step `startup` is PASS. Step s001 is HANG. The thread dump shows `AWT-EventQueue`. CLI exit code 1. |
   | `pilot`: p2.product, JRE 8, `pilot.json`, no `--log` | `wp04-pilot.txt`, `wp04-pilot\pilot\run-result.json` | The run is PASS. No step has a hit. CLI exit code 0. |
   | `approve`: empty folder | `wp04-approve.txt` | The output is `approve: no run-result.json in <folder>`. No stack trace. Exit code 2. |

   To count the hits of a step, use `jq` if it is installed. Otherwise read the JSON file.
6. Write `docs/wp-04-windows-report.md`. Use the structure of `docs/wp-03-windows-report.md`:
   - Date, environment (OS, PowerShell, Maven, the JDK of the build, the JRE 8 and JRE 17 paths and versions), and the commit (`git rev-parse --short HEAD`).
   - A table with the 5 runs: the command, the expected result, the actual result, and "Pass" or "Fail".
   - For each run that failed: the first lines of the relevant output, and the path of its `--out` folder.
   - "Not tested": a product with the hardware simulator (F-02).
   - Write in Simplified Technical English: short sentences, active voice, one term for one thing.
7. Update the state in three places. Write "merged" and the result of the Windows check:
   - `CLAUDE.md`, the line that starts with `- WP-04 (log analysis) is implemented on branch`.
   - `CLAUDE.md`, the line `- Next work package in plan order: WP-05 (after WP-04 is merged).`
   - `docs/development-plan.md`, the paragraph `State on 2026-11-22: WP-04 is implemented on the branch`.

   If all 5 runs passed, write that WP-04 is complete and that F-08, F-09, F-22 and F-24 are closed. If a run failed, write that WP-04 is merged and that the Windows check found a problem. Refer to the report.
8. Add `docs/wp-04-windows-report.md` to the list in `CLAUDE.md`, section "Read first", after the WP-03 report.
9. Commit and push:
   ```bash
   git add docs/wp-04-windows-report.md CLAUDE.md docs/development-plan.md
   git commit -m "WP-04: Windows check on the pilot applications"
   git push
   ```
10. Open a pull request to `master`:
    ```bash
    gh pr create --base master --head wp-04-windows-check --title "WP-04: Windows check on the pilot applications" --body "Test report: docs/wp-04-windows-report.md. <one line per run: name and Pass or Fail>"
    ```
11. Give the owner the PR URL and the table of step 5 with the actual results.

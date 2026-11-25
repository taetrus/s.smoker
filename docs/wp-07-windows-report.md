# WP-07 Windows test

Date: 2026-11-25

Job: `wp07` in `.github/workflows/pilot-check.yml` on branch `pilot-check/wp-07`. Run 45648197103 on the self-hosted
runner `CARLOSCARMEN`. Artifact `wp07-check` (summary in `summary.txt`).

All checks passed. The application replaced the configured locale when Equinox received `-nl`, and the result gave a
warning (section "Locale and Equinox `-nl`"). The key `windowSize` changed the size of the first main window of
`p2.product`. That window is the clock frame, not the chatbot window (section "Window size").

## Environment

- OS: Windows 11 10.0. Windows culture `en-US`, UI culture `en-US`, time zone `Turkey Standard Time` (Java: `Asia/Istanbul`).
- Shell: PowerShell 5.1.
- Build: `mvn -B package -DskipTests` on the runner. The CLI ran on JDK 25.0.1 (`java` in `PATH`).
- JRE 8 of the application: `C:\Program Files\Java\jdk1.8.0_341\bin\java.exe`, 1.8.0_341.
- Commit: `381f3e4` (branch `pilot-check/wp-07`, product code equal to `master` 5a72a44, PR #27).
- Application: p2.product of `p.osgi.ds`
  (`D:\dev\p.osgi.ds\distribution\target\products\com.kk.pde.ds.product\win32\win32\x86_64`).
  Scenario `C:\smoke\pilot.json` with its golden `C:\smoke\pilot.golden.json`.
- Result folders: `D:\Dev\actions-runner\_work\_temp\wp07-check` (the runner deletes it at the next job) and
  `C:\smoke\out\wp07-merge-other`.

## Results

| Requirement | Check | Expected result | Actual result | Result |
|---|---|---|---|---|
| R-41 | `run --label base`, no condition keys | PASS. `run-result.json` has the label. `job-result.json` exists. | As expected. Steps `startup` and s001 to s005 PASS. Result fields: `en-US`, `Asia/Istanbul`, `MetalLookAndFeel`, `216x239`. No warning. | Pass |
| R-34 | `run --label cond --locale en-US --timeZone UTC --lookAndFeel system --windowSize 1024x700` | PASS. The result fields show the four values. No warning. | As expected. `en-US`, `UTC`, `com.sun.java.swing.plaf.windows.WindowsLookAndFeel`, `1024x700`. No warning. The golden of `pilot.json` was made without the keys. | Pass |
| R-34 | `run --label nl --locale en-US`, Equinox argument `-nl tr_TR` | The status does not change. The result has the warning `locale is tr-TR, not the configured en-US`. `run` prints it. | As expected. PASS, `locale` `tr-TR`, one warning with that text. The CLI printed `warning: locale is tr-TR, not the configured en-US`. | Pass |
| R-34 | `run --label cross --lookAndFeel crossplatform` | PASS, Metal look and feel. | As expected. `javax.swing.plaf.metal.MetalLookAndFeel`. | Pass |
| R-41, R-42 | `report --merge base cond nl cross --history history.json --run-id r1`, then `r2` | Exit code 0 two times. One page with four columns. Links relative to the page. Two letters for each step. The warning of `nl` below the scenario row. | As expected. First merge: `history: new file`. Header rows Java `1.8.0_341`, Locale (`tr-TR, Asia/Istanbul` for `nl`), `1 of 1 PASS`. Each step has `PP` in each history column. The page shows `nl: locale is tr-TR, not the configured en-US`. Links: `../base/report.html#pilot`. `history.json` has the runs `r1` and `r2`. | Pass |
| R-41 | Merge with `--out` on drive `C:`, job folders on drive `D:` | Exit code 0. Links are `file:` URIs. | As expected. `file:///D:/Dev/actions-runner/_work/_temp/wp07-check/base/report.html#pilot`. | Pass |
| R-41 | Merge with the job folders given as UNC paths (`\\localhost\D$\...`) | Exit code 0. Links are `file:` URIs of the UNC paths. | As expected. `file://localhost/D$/Dev/actions-runner/_work/_temp/wp07-check/base/report.html#pilot`. | Pass |
| R-40a | `check-release` on the product folder | Exit code 0. No agent option. | As expected. `check-release: 2 file(s) checked, no agent option`. | Pass |
| R-40a | `check-release` on a folder with a clean `eclipse.ini` | Exit code 0. | As expected. `1 file(s) checked, no agent option`. | Pass |
| R-40a | `check-release` on a folder with `-javaagent:<agent jar>=config=C:\smoke\x.cfg` in line 3 | Exit code 1. The line with file name and line number. | As expected. `...\hit\eclipse.ini:3: -javaagent:D:\...\smoker-agent.jar=config=C:\smoke\x.cfg`. | Pass |
| R-40a | `check-release` on a zip from `Compress-Archive` with `app\eclipse.ini` (`-javaagent:D:\tools\SMOKER-agent.jar`) | Exit code 1. | As expected. `...\product.zip!app\eclipse.ini:2: -javaagent:D:\tools\SMOKER-agent.jar`. The upper-case name matched. The entry name has `\`, as PowerShell 5.1 writes it. | Pass |
| R-40a | `check-release <folder>\*.zip` | No stack trace. | Exit code 1 with the hit of `product.zip`. The Java launcher on Windows expands `*` in an argument. The command received the file name. | Pass (see note) |
| R-40a | `check-release <folder>\a"b` | Exit code 2. No stack trace. | Exit code 2, `check-release: ...\release\ab does not exist`. PowerShell 5.1 removed the quote, so the command did not receive `"`. | Pass (see note) |
| Lint baseline | `lint --run base --write-baseline`, then `lint --run base --baseline` | Exit code 0 two times. 0 new. | As expected. 14 results (the same 14 components as the WP-09 check). `lint: 14 result(s), 14 in the baseline, 0 new, 0 baseline entries not found`. | Pass |

Note: the two path checks did not reach the path code of `check-release` with `*` or `"`. The launcher expanded `*`, and
PowerShell removed `"`. The unit tests cover a path that the JRE refuses (`ReleaseCheckTest.argumentThatIsNotAPathGives2`).

## Locale and Equinox `-nl`

The agent sets the default locale in `premain`. Equinox sets it again from `-nl` in its `main` method. In the run `nl`
the agent log has `[smoker] locale=en-US`, and `run-result.json` has `locale` `tr-TR`. The warning tells the user that
the key had no effect. To keep the locale of a product with `-nl`, remove `-nl` from the application command or give
`-nl` the same value as `locale`.

## Window size

`p2.product` shows two frames: the clock frame (`OSGi Clock`, 216x239) and the chatbot window (`OSGi Chatbot`).
The clock frame shows first. The agent changes the size of the first main window (design section 6), so in the run
`cond` the clock frame became 1024x700. The chatbot window kept its size. The screenshots of the run show this
(`cond\pilot\startup.png`). The run gave no warning, because the window that the agent changed has the configured size.
An application with a login frame or a small helper frame before the real main window has the same effect.
This is the design behaviour. A key that selects the window by title is a possible change for the field phase.

## Not tested

- A JRE 21 for the application: the job found no JRE 21 in `C:\Program Files\Java`, `C:\Program Files\Eclipse Adoptium`
  or `C:\Program Files\Microsoft`. "JRE 21 at run time on a target application" stays not verified.
- The links of the merged pages in a browser. The job checked the link text only.
- `check-release` with `*` or `"` in the path that the command receives (see the note).
- A product with the hardware simulator.
- The GitLab jobs (WP-07 task 4, field phase).

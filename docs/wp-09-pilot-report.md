# WP-09 pilot measurement

Date: 2026-11-15

## Environment

- OS: macOS (Darwin 27.2.0).
- JDK: Temurin 21 (`openjdk version "21.0.12.1" 2026-08-18 LTS`).
- Pilot application: p.osgi.ds, commit `b9dd341`.
- Product: `distribution/target/products/com.kk.pde.ds.product/macosx/cocoa/x86_64/Eclipse.app/Contents/Eclipse`.
- The run used a copy of the product folder. It did not use the p.osgi.ds tree.
- Scenario: `docs/wp-01-pilot/pilot.json` (schemaVersion 2, no goldens, 5 steps).
- The application started with `java -jar`. It did not use the native launcher.

## Commands

1. Build: `mvn -B -q package -DskipTests`
2. Run, started in the product copy folder:

```
java -jar smoker-cli/target/smoker-cli.jar run \
  --agent smoker-agent/target/smoker-agent.jar \
  --scenarios <scratch>/scenarios --out <scratch>/out \
  --app 'java {agent} -jar plugins/org.eclipse.osgi_3.23.0.v20250228-0640.jar -configuration configuration -consoleLog'
```

3. Lint:

```
java -jar smoker-cli/target/smoker-cli.jar lint --run <scratch>/out --emit-patch <scratch>/pilot.patch
```

## Run status

The scenario status is NO_GOLDEN. No step failed. The CLI reported "1 scenario(s), 0 failed".

| Step | Action | Status |
|------|--------|--------|
| s001 | CLICK `Settings` button | NO_GOLDEN |
| s002 | SET_TEXT in the Model field of the Settings dialog | NO_GOLDEN |
| s003 | CLICK `Cancel` in the Settings dialog | NO_GOLDEN |
| s004 | Chat input | NO_GOLDEN |
| s005 | CLICK `Clear` | NO_GOLDEN |

All 5 steps ran. The Settings dialog opened. The lint therefore measures the Settings dialog and the main window.

## Lint table

```
window         | class          | field               | type           | text            | automatic name                 | proposed name          | source
[OSGi Chatbot] | ChatFrame      | refreshModelsButton | JButton        | Refresh Models  | @ChatFrame.refreshModelsButton | chat.btn.refreshModels | field
[OSGi Chatbot] | ChatFrame      | setDefaultButton    | JButton        | Set Default     | @ChatFrame.setDefaultButton    | chat.btn.setDefault    | field
[OSGi Chatbot] | ChatFrame      | -                   | JButton        | Settings        | -                              | chat.btn.settings      | text
[OSGi Chatbot] | ChatFrame      | modelCombo          | JComboBox      | -               | @ChatFrame.modelCombo          | chat.cmb.model         | field
[OSGi Chatbot] | ChatPanel      | textPane            | JTextPane      | -               | @ChatPanel.textPane            | chat.txt.text          | field
[OSGi Chatbot] | InputPanel     | clearButton         | JButton        | Clear           | @InputPanel.clearButton        | input.btn.clear        | field
[OSGi Chatbot] | InputPanel     | sendButton          | JButton        | Send            | @InputPanel.sendButton         | input.btn.send         | field
[OSGi Chatbot] | InputPanel     | textArea            | JTextArea      | -               | @InputPanel.textArea           | input.txt.text         | field
[Settings]     | SettingsDialog | -                   | JButton        | Cancel          | -                              | settings.btn.cancel    | text
[Settings]     | SettingsDialog | -                   | JButton        | Save            | -                              | settings.btn.save      | text
[Settings]     | SettingsDialog | testButton          | JButton        | Test Connection | @SettingsDialog.testButton     | settings.btn.test      | field
[Settings]     | SettingsDialog | apiKeyField         | JPasswordField | -               | @SettingsDialog.apiKeyField    | settings.pwd.apiKey    | field
[Settings]     | SettingsDialog | baseUrlField        | JTextField     | -               | @SettingsDialog.baseUrlField   | settings.txt.baseUrl   | field
[Settings]     | SettingsDialog | modelField          | JTextField     | -               | @SettingsDialog.modelField     | settings.txt.model     | field
```

## Summary line

```
14 unnamed interactive component(s), 14 with a proposed name (100 %: 11 from fields, 3 from text), 11 with an automatic name
```

## Patch

```
// com.kk.pde.ds.chatbot.ChatFrame
modelCombo.setName("chat.cmb.model");
refreshModelsButton.setName("chat.btn.refreshModels");
setDefaultButton.setName("chat.btn.setDefault");
// JButton "Settings" has no field: setName("chat.btn.settings") where it is created

// com.kk.pde.ds.chatbot.ChatPanel
textPane.setName("chat.txt.text");

// com.kk.pde.ds.chatbot.InputPanel
clearButton.setName("input.btn.clear");
sendButton.setName("input.btn.send");
textArea.setName("input.txt.text");

// com.kk.pde.ds.chatbot.SettingsDialog
apiKeyField.setName("settings.pwd.apiKey");
baseUrlField.setName("settings.txt.baseUrl");
modelField.setName("settings.txt.model");
testButton.setName("settings.btn.test");
// JButton "Cancel" has no field: setName("settings.btn.cancel") where it is created
// JButton "Save" has no field: setName("settings.btn.save") where it is created
```

## Result against the 80 % criterion

- Unnamed interactive components: 14.
- With a proposed name: 14 (11 from fields, 3 from text).
- Percentage: 100 %.
- The criterion is 80 %. The criterion is met.
- The 11 components with an automatic name stay in the denominator, as the design requires (section 9 of the design document).
- Proposals from fields and from text both count. They are counted separately above.

## Components without a proposal

None. All 14 components have a proposal.

Three components have no field. The proposal comes from the text only: `Settings` (ChatFrame), `Cancel` and `Save` (SettingsDialog). The patch cannot name a field for them. A developer must call `setName` where the button is created.

## Open: Windows

The Windows part is open. It is the scenario with automatic names. It belongs to the next Windows session (`docs/wp-01-handoff.md`, section 5).

## Limits of this measurement

- The measurement covers the 2 windows that the 5 scenario steps showed: `OSGi Chatbot` and `Settings`.
- Other windows of the product were not opened. They are not measured.
- The run used macOS and JDK 21. It did not use JRE 8, 11 or 17.

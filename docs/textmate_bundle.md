# TextMate bundle

[//]: # (excludeFromIndex)

The TextMate-format bundle contains a syntax definition for initial language support in
popular editors that understand TextMate grammars: TextMate, Visual Studio Code, Sublime Text, etc.

- [Download TextMate Bundle for Lyng](https://lynglang.com/distributables/lyng-textmate.zip)

> Note for IntelliJ-based IDEs (IntelliJ IDEA, Fleet, etc.): although you can import TextMate
> bundles there (Settings/Preferences → Editor → TextMate Bundles), we strongly recommend using the
> dedicated plugin instead — it provides much better support (formatting, smart enter, background
> analysis, etc.). See: [IDEA Plugin](#/docs/idea_plugin.md).

## Visual Studio Code

VS Code uses TextMate grammars packaged as extensions. A minimal local extension is easy to set up:

1) Download and unzip the bundle above. Inside you will find the grammar file (usually
   `*.tmLanguage.json` or `*.tmLanguage` plist).
2) Create a new folder somewhere, e.g. `lyng-textmate-vscode/` with the following structure:

```
lyng-textmate-vscode/
  package.json
  syntaxes/
    lyng.tmLanguage.json   # copy the grammar file here (rename if needed)
```

3) Put this minimal `package.json` into that folder (adjust file names if needed):

```
{
  "name": "lyng-textmate",
  "displayName": "Lyng (TextMate grammar)",
  "publisher": "local",
  "version": "0.0.1",
  "engines": { "vscode": "^1.70.0" },
  "contributes": {
    "languages": [
      { "id": "lyng", "aliases": ["Lyng"], "extensions": [".lyng"] }
    ],
    "grammars": [
      {
        "language": "lyng",
        "scopeName": "source.lyng",
        "path": "./syntaxes/lyng.tmLanguage.json"
      }
    ]
  }
}
```

4) Open a terminal in `lyng-textmate-vscode/` and run:

```
code --install-extension .
```

   Alternatively, open the folder in VS Code and press F5 to run an Extension Development Host.
5) Reload VS Code. Files with the `.lyng` extension should now get Lyng highlighting.

## Sublime Text 3/4

1) Download and unzip the bundle.
2) In Sublime Text, use “Preferences → Browse Packages…”, then copy the unzipped bundle
   to a folder like `Packages/Lyng/`.
3) Open a `.lyng` file; Sublime should pick up the syntax automatically. If not, use
   “View → Syntax → Lyng”.

## TextMate 2

1) Download and unzip the bundle.
2) Double‑click the `.tmBundle`/grammar package or drag it onto TextMate to install, or place
   it into `~/Library/Application Support/TextMate/Bundles/`.
3) Restart TextMate if needed and open a `.lyng` file.


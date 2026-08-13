# Privacy Policy

**TableKit collects nothing.**

That is the whole policy, but here is what it means in detail.

## What the plugin sends

Nothing. TableKit requests no network permissions and makes no network calls of any
kind. It has no telemetry, no analytics, no crash reporting, no update pings, no
license checks, and no "anonymous usage statistics".

## What the plugin downloads

Nothing at runtime. The query engine and its native libraries are bundled inside the
plugin archive. TableKit never fetches code, data, or configuration while it runs.

## What happens to your files

They stay on your disk. Files you open are read locally and queried in place by the
embedded engine. Rows are held in memory only while they are on screen. When you use
Export, the result is written to the path you choose and nowhere else.

## What is stored

Nothing beyond what your IDE stores for any editor - which files were open, and where
you scrolled. That data never leaves your machine.

## Verifying this

The source code is published at https://github.com/SpdVpr/tablekit precisely so that
this page does not have to be taken on trust. The plugin declares only
`com.intellij.modules.platform` and ships no networking library.

## Contact

Questions or concerns: https://github.com/SpdVpr/tablekit/issues

Last updated: 2026-08-13

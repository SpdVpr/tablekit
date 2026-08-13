# Changelog

All notable changes to TableKit are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Added
- **Parquet, CSV, TSV and JSON Lines viewer.** Files are queried in place by an
  embedded DuckDB engine; only the rows on screen are ever in memory, so a
  multi-gigabyte file opens as fast as a small one.
- **Excel (.xlsx, .xlsm) viewer** with a sheet switcher. The reader is written
  on the JDK alone - no third-party spreadsheet library is shipped.
- **Avro viewer**: the file's own schema decides the column types, logical types
  (date, time, timestamp, decimal) arrive as themselves, nested records, arrays
  and maps as JSON, and deflate-compressed files are read. The reader is ours
  for the same reason as the Excel one.
- **Sorting** by clicking a column header, pushed down to the engine as
  `ORDER BY` rather than sorting in the IDE's heap.
- **Filtering**: a filter box that searches every column at once, plus a
  per-column menu (contains, equals, only nulls, only non-nulls) on the header's
  context menu. The status bar shows how many rows are left.
- **Schema at a glance**: every column header shows its type, and nested values
  (struct, list, map) are rendered as JSON instead of driver objects.
- **Column statistics** from the header's context menu: rows, nulls and their
  share, distinct values, range, average for numbers, and the ten most frequent
  values with proportional bars. Computed over whatever the filters leave.
- **Export** of the rows currently shown - filters and sorting included, every
  row rather than the page on screen - to CSV, TSV, JSON Lines or Parquet. The
  engine writes the file, so this doubles as format conversion. Nested values
  become JSON in text formats and stay nested in Parquet and JSON Lines.
- **Row numbers** that count the current view, so with a filter in force row 1
  is the first row on screen.
- File type registration for `.parquet`, `.parq`, `.pq`, `.xlsx`, `.xlsm` and
  `.avro`, which is also what makes the IDE suggest the plugin. ORC is not
  registered until it can be read - being suggested for a file the plugin then
  refuses to open is worse than not being suggested.
- `.csv`, `.tsv` and `.jsonl` open in TableKit as a second editor tab; whatever
  editor already owns them stays the default.
- Readable error screens for unreadable files - never an exception balloon.

### Interaction
- Search field, action toolbar (statistics, export, reload) and a context menu on the grid
  carrying the same actions.
- Active filters shown as removable chips; an empty grid explains itself and offers to
  clear them; a file that failed to open offers to try again.
- **Show Value** (double click or Ctrl+Enter) opens a cell in full and re-indents nested
  JSON.
- Striped rows, per-format file icons, row numbers that count the current view.
- Ctrl+F focuses the filter, Escape clears it, F5 rereads the file.
- **Open in TableKit** on the project view and editor tab menus for CSV, TSV and JSON
  Lines, where TableKit is the second tab rather than the default editor.
- Excel dates formatted without a time are read as dates, not as midnight timestamps.
- Column statistics show the distribution of ordered columns as a histogram and the
  leading values of categorical ones.

### Verified
IntelliJ Plugin Verifier reports the plugin **compatible** with IntelliJ IDEA 2024.2,
2024.3, 2025.1, 2025.2 and 2026.2 - the whole range it declares - and eligible to be
enabled and disabled without restarting the IDE.

### Performance
Targets from PLAN.md in brackets.

A 1.24 GB Parquet file, 25 million rows:

| | |
|---|---|
| Open (schema + row count) | 17 ms (< 2 s) |
| First page, mid file, end of file | 14 / 23 / 27 ms |
| First page of a full sort | 940 ms |
| Heap after sorting | 37 MB (< 300 MB) |

A 786 MB CSV file, 12 million rows. The engine re-reads a CSV for every query,
so a page deep in the file costs a scan rather than a seek:

| | |
|---|---|
| Open (sniff + row count) | 814 ms |
| First page | 35 ms |
| Page at the end of the file | 1.7 s |
| Counting a filtered result | 1.2 s |

A 200 000 row Excel workbook, which is loaded rather than read in place:

| | |
|---|---|
| Open (two passes + load) | 1.5 s |
| Any page afterwards | 3 ms |
| Sorted page | 13 ms |

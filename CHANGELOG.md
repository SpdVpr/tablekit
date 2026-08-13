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
- **Sorting** by clicking a column header, pushed down to the engine as
  `ORDER BY` rather than sorting in the IDE's heap.
- **Filtering**: a filter box that searches every column at once, plus a
  per-column menu (contains, equals, only nulls, only non-nulls) on the header's
  context menu. The status bar shows how many rows are left.
- **Schema at a glance**: every column header shows its type, and nested values
  (struct, list, map) are rendered as JSON instead of driver objects.
- File type registration for `.parquet`, `.parq`, `.pq`, `.xlsx`, `.xlsm`,
  `.avro`, `.orc`, which is also what makes the IDE suggest the plugin.
- `.csv`, `.tsv` and `.jsonl` open in TableKit as a second editor tab; whatever
  editor already owns them stays the default.
- Readable error screens for unreadable files - never an exception balloon.

### Performance
Measured on a 1.24 GB Parquet file with 25 million rows (targets from PLAN.md
in brackets):

| | |
|---|---|
| Open (schema + row count) | 14 ms (< 2 s) |
| First page | 18 ms |
| Page at the end of the file | 45 ms |
| First page of a full sort | 931 ms |
| Heap after sorting | 32 MB (< 300 MB) |

# Projekt: TableKit: Excel & Parquet Viewer

JetBrains IDE plugin: prohlížeč tabulkových datových souborů (Parquet, Avro, XLSX,
CSV/TSV, JSONL) s embedded DuckDB enginem. Vendor **Kitworks**, plugin ID
`com.kitworks.tablekit` (po prvním uploadu na Marketplace navždy neměnné).

> Strategické a marketplace dokumenty (plán stavby po fázích, listing, postup publikace)
> žijí v privátním repu vedle — lokálně `E:\tableKit-ops`, na GitHubu `SpdVpr/tablekit-ops`.
> Tenhle soubor drží jen technický kontext a konvence.

## Tech stack a kompatibilita

| Oblast | Volba |
|---|---|
| Jazyk / build | Kotlin 2.3 (apiVersion 1.9) + Gradle 9.3 + IntelliJ Platform Gradle Plugin 2.18 |
| Engine | DuckDB JDBC, embedded, in-process |
| Kompatibilita | sinceBuild 242 (2024.2+), bez untilBuild; žádná závislost na Ultimate ani na Kotlin pluginu |
| Editor | vlastní `FileEditor` s virtualizovaným gridem, **ne** Document model IDE |

## Klíčová technická rozhodnutí (a proč)

- **Vlastní FileEditor mimo Document model.** IDE dokumenty mají ~20 MB limit a
  synchronní načítání — přesně to zamrzává. Streamujeme přes DuckDB, v paměti je jen
  viditelné okno (40 stránek po 200 řádcích, LRU).
- **Filtry a řazení jako SQL pushdown**, ne třídění v JVM. Grid nikdy nedrží víc než pár
  tisíc řádků.
- **xlsx i avro čtou vlastní čtečky na čistém JDK** (`java.util.zip` + `javax.xml.stream`;
  u Avro vlastní OCF dekodér). fastexcel, POI i `org.apache.avro` táhnou
  `commons-compress`, StAX, Jackson a slf4j — všechno věci, které IntelliJ sám bundluje
  ve verzích, které neřídíme. Druhá kopie na classpath je `NoSuchMethodError` na
  neotestované verzi IDE. POI a Avro se používají **jen v testech** jako generátory
  fixture souborů.
- **Zero network permissions.** Žádná telemetrie, žádné stahování za běhu (DuckDB
  extensions se bundlují, nestahují). Je to prodejní argument i rychlejší review.
- **`.csv`/`.tsv`/`.jsonl` nikdy nepřebírají default editor** — jen alternativní záložka
  a akce „Open in TableKit". Válku s CSV Editorem nechceme.
- **Neregistrovat file type, který neumíme otevřít.** ORC byl odregistrován právě proto:
  IDE by plugin nabídlo a uživatel by dostal chybovou obrazovku.
- **Kotlin je připnutý na 2.3.x** a `instrumentCode` vypnuté — detaily v komentářích
  v `build.gradle.kts` a `settings.gradle.kts`.

## Příkazy

```bash
./gradlew test                 # testy (headless platform testy)
./gradlew buildPlugin          # ZIP do build/distributions
./gradlew verifyPlugin         # IntelliJ Plugin Verifier proti deklarovanému rozsahu
./gradlew signPlugin           # podepsaný ZIP; bez env proměnných se přeskočí
./gradlew runIde               # sandbox IDE, otevře demo/
./gradlew test -Ptablekit.demo=true --tests '*DemoDataTest'   # vygeneruje demo data
```

JDK 21 je potřeba na PATH (`jvmToolchain(21)`).

## Konvence pro tuto codebase

- Kód a identifikátory anglicky, komunikace s uživatelem česky.
- Commity na `master`, push po každém celku.
- Verifikace úměrná změně: u perf-kritických částí (grid, DuckDB) testy s generovanými
  velkými soubory; u drobných úprav necommitovat přes celé test suite.
- Výkonnostní cíle jsou tvrdé požadavky, ne přání: otevření 1GB parquetu < 2 s, plynulý
  scroll, paměť < 300 MB. Zamrzání IDE je kategorie, kterou jdeme zabít; nesmíme ho
  způsobovat sami.

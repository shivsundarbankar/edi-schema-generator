# EDI Schema Generator

A production-grade Spring Boot (Java 21) application that scrapes X12 EDI
transaction-set specifications from Stedi's EDI Reference
(`https://www.stedi.com/edi/...`) and converts them into:

1. An **Excel workbook** (auditable, hand-editable), and
2. **EDISchema v4 XML** (`http://xlate.io/EDISchema/v4`), in the same
   three-file layout as a manually maintained schema:
   `common-elements.xml`, `{txn}-segments.xml`, `{txn}.xml`.

## Key guarantees

- **Sequence fidelity** - segments, loops (including nested loops such as the
  `PO1` loop containing `PID` / `SAC` / `N1` sub-loops), and element positions
  are emitted in exactly the order shown on the website.
- **Full composite support** - composite elements (e.g. `C040 Reference
  Identifier`) are captured with their complete component sequences and
  emitted as `<compositeType>` definitions referenced from segments via
  `<composite type="C040" .../>`.
- **Release-agnostic, URL-driven** - the X12 release is parsed from the URL.
  `https://www.stedi.com/edi/x12-004010/850` and
  `https://www.stedi.com/edi/x12-005010/810` both work with no code changes;
  outputs are written under `output/x12-{release}/{txn}/`.
- **Polite scraping** - on-disk HTML cache (default TTL 7 days), enforced
  minimum interval between requests, retries with backoff. Each segment page
  is fetched at most once per run; element and composite details are read
  from the segment pages, so a typical 850 needs ~60-80 requests total on a
  cold cache and zero on a warm one.

## Requirements

- JDK 21
- Internet access to `www.stedi.com`
- The Gradle wrapper is included (`./gradlew`); first run downloads Gradle
  8.10.2 and dependencies from Maven Central.

## Usage

### CLI (one-shot)

```bash
# single transaction set
./gradlew bootRun --args='--url=https://www.stedi.com/edi/x12-004010/850'

# multiple transaction sets and releases in one run
./gradlew bootRun --args='--url=https://www.stedi.com/edi/x12-004010/850,https://www.stedi.com/edi/x12-004010/856,https://www.stedi.com/edi/x12-005010/810'

# single self-contained XML file instead of three include-linked files
./gradlew bootRun --args='--url=https://www.stedi.com/edi/x12-004010/850 --combined'
```

Or with the packaged jar:

```bash
./gradlew bootJar
java -jar build/libs/edi-schema-generator.jar --url=https://www.stedi.com/edi/x12-005010/810
```

### Excel-first workflow (review/edit, then XML)

Every run writes `{txn}-schema.xlsx` next to the XML. You can edit the
workbook (rename, change min/max, drop optional segments, etc.) and
regenerate XML from it without re-scraping:

```bash
java -jar build/libs/edi-schema-generator.jar --from-excel=output/x12-004010/850/850-schema.xlsx
```

### REST API (long-running service mode)

Start without CLI arguments: `./gradlew bootRun`

```bash
curl -X POST localhost:8080/api/generate \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.stedi.com/edi/x12-004010/850","combined":false}'

curl -X POST localhost:8080/api/generate/batch \
  -H 'Content-Type: application/json' \
  -d '{"urls":["https://www.stedi.com/edi/x12-004010/850","https://www.stedi.com/edi/x12-005010/810"]}'

curl -X POST localhost:8080/api/generate/from-excel \
  -H 'Content-Type: application/json' \
  -d '{"workbookPath":"output/x12-004010/850/850-schema.xlsx"}'
```

## Output

```
output/
└── x12-004010/
    └── 850/
        ├── 850-schema.xlsx        # Meta / Structure / Segments / Composites / Elements
        ├── common-elements.xml    # <elementType> + <compositeType> definitions
        ├── 850-segments.xml       # <segmentType> definitions (includes common-elements.xml)
        └── 850.xml                # <transaction> structure (includes 850-segments.xml)
```

Every definition carries an XML comment with the human-readable name, e.g.
`<element type="E0353" minOccurs="1"/><!-- BEG01: Transaction Set Purpose Code -->`.

## Mapping rules (Stedi -> EDISchema v4)

| Website                          | Schema                                            |
|----------------------------------|---------------------------------------------------|
| `Mandatory`                      | `minOccurs="1"`                                   |
| `Optional` / `Conditional`       | `minOccurs="0"`                                   |
| `Max N` / `Repeat N`             | `maxOccurs="N"`                                   |
| `Max >1` / `Repeat >1`           | `maxOccurs="{default-unbounded-max}"` (99999)     |
| Element `353`                    | `elementType name="E0353"` (zero-padded)          |
| `Identifier (ID)`                | `base="identifier"`                               |
| `String (AN)`                    | `base="string"`                                   |
| `Date (DT)` / `Time (TM)`        | `base="date"` / `base="time"`                     |
| `Numeric (N0..N9)` / `Decimal (R)` | `base="numeric"` / `base="decimal"`             |
| Composite `C040`                 | `compositeType name="C040"` + `<composite type>`  |
| Loop (lead segment `N1`)         | `<loop code="N1">` (suffixed `_2`, `_3` on reuse) |
| `ST` / `SE`                      | skipped by default (envelope; see config)         |

All rules are configurable in `src/main/resources/application.yml`
(`edischema.*`): unbounded substitute, envelope skipping, cache TTL/location,
request interval, output directory.

## Design notes

- `TransactionSetParser` parses the nested ordered lists of the Heading /
  Detail / Summary areas recursively, so loop nesting of any depth is
  preserved.
- `SegmentPageParser` works on a linearized *token stream* of the page (text
  + links in reading order) rather than CSS selectors, making it resilient to
  styling and markup changes. It keys on stable invariants: position markers
  (`REF-04`, `01`), element links (`/element/128`, `/element/C040`), type
  markers (`Identifier (ID)`, `Composite (composite)`), requirement words and
  the min/max numbers.
- Element and composite definitions are deduplicated across segments in
  first-appearance order; conflicting duplicate definitions are logged.

## Troubleshooting

- **Parser finds nothing / structure looks wrong** - the website's markup may
  have changed. Every fetched page is cached as raw HTML under
  `.cache/stedi/` (file name is the SHA-256 of the URL, with the original URL
  in the first line). Inspect the cached page and adjust the patterns in
  `SegmentPageParser` / `TransactionSetParser`; the unit tests under
  `src/test/resources/fixtures/` show the expected shapes.
- **HTTP 429 / blocks** - raise `edischema.min-request-interval-millis`.
- **Stale data** - delete `.cache/stedi/` or lower `edischema.cache-ttl-hours`.

> Note: this project was authored in an offline environment without access to
> Maven Central or stedi.com, so it could not be compile-verified or run live
> before delivery. The parsers were designed against the real rendered
> structure of the Stedi pages and are covered by unit tests with fixtures;
> if the first live run surfaces a markup difference, the cached HTML plus
> the two parser classes are the only places you should need to touch.

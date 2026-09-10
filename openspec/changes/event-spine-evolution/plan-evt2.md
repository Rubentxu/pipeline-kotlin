# EVT-2 Implementation Plan (executed; receipts machine-derived)
1. ports + cursor codec + pagination (EventHistoryPorts.kt) — done ec908bcd
2. total EnvelopeProjector + SequenceAssigner + projecting sink — done
3. shared EventHistoryReader (InMemory/SQLite parity) — done
4. contract suite 6/6 (parity/restart/pagination/filters/codec/sequence-law) — done ec908bcd
5. CLI events subcommand — done a84b73f9
6. real-binary UAT (new process, cursor continuation) — done, GREEN
7. P4-EX x2 consecutive — done (exit 0, 10/10 each)
